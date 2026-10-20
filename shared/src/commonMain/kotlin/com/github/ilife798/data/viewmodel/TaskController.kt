package com.github.ilife798.data.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.ilife798.RunNotifications
import com.github.ilife798.data.api.IlifeApi
import com.github.ilife798.data.model.AppState
import com.github.ilife798.data.model.MissionInfo
import com.github.ilife798.update.requestNotificationPermission
import com.github.ilife798.util.currentTimeFormatted
import com.github.ilife798.util.currentTimeMillis
import com.github.ilife798.util.getDayOfWeek
import com.github.ilife798.util.getTodayStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// 积分任务：合并多渠道任务列表、执行并停止、运行日志。
class TaskController(
    private val scope: CoroutineScope,
    private val apiProvider: () -> IlifeApi,
    private val getState: () -> AppState,
    private val setState: ((AppState) -> AppState) -> Unit,
    private val getLoading: () -> Boolean,
    private val onLoadingChange: (Boolean) -> Unit,
    private val onError: (String?) -> Unit,
    private val onToast: (String) -> Unit,
    private val onLogError: (String, Exception) -> Unit,
    private val onResetScoreCooldown: () -> Unit,
    private val onReloadScores: () -> Unit,
) {
    private val api: IlifeApi get() = apiProvider()
    private val rateLimiter = TokenRateLimiter()

    var missions by mutableStateOf<List<MissionInfo>>(emptyList())
        private set

    var tasksRefreshing by mutableStateOf(false)
        private set

    private var taskJob: Job? = null
    private var lastMissionsLoadTime = 0L

    fun reset() {
        taskJob?.cancel()
        taskJob = null
        lastMissionsLoadTime = 0L
        missions = emptyList()
        tasksRefreshing = false
    }

    /** [runMissions] 的执行结果，携带累加后的总收益。 */
    private data class MissionRunResult(
        val gained: Int,
    )

    private data class MergedMissions(
        val missions: List<MissionInfo>,
        val weekMask: Int,
        val dailyAdId: String,
        val dailyScore: Int,
        val dailyToken: String,
        val validScore: Int?,
        val totalScore: Int?,
    )

    // 按时间倒序分页拉取今日积分记录，统计各 adId 次数（遇到早于今日的记录即停止）
    private suspend fun fetchTodayScoreCounts(
        token: String,
        todayStart: Long,
    ): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val pageSize = 50
        var page = 0
        while (page < 5) {
            val records =
                try {
                    api.getScoreList(token, page = page, size = pageSize).records
                } catch (e: Exception) {
                    onLogError("fetchTodayScoreCounts", e)
                    break
                }
            if (records.isEmpty()) break
            var reachedEarlier = false
            for (score in records) {
                val timeMs = score.time.toLongOrNull() ?: continue
                if (timeMs < todayStart) {
                    reachedEarlier = true
                    continue
                }
                if (score.score > 0 && score.adId.isNotEmpty()) {
                    counts[score.adId] = (counts[score.adId] ?: 0) + 1
                }
            }
            if (reachedEarlier || records.size < pageSize) break
            page++
        }
        return counts
    }

    // 合并两个登录渠道（appToken 1,1 与 token 1,5）的任务列表，按 adId 去重并记录来源 token
    private suspend fun fetchMergedMissions(): MergedMissions {
        val account = getState().account
        val appToken = account.appToken
        val pointsToken = account.token
        val tokens =
            buildList {
                if (appToken.isNotEmpty()) add(appToken)
                if (pointsToken.isNotEmpty() && pointsToken != appToken) add(pointsToken)
            }
        val merged = LinkedHashMap<String, MissionInfo>()
        var weekMask = 0
        var dailyAdId = ""
        var dailyScore = 5
        var dailyToken = ""
        var validScore: Int? = null
        var totalScore: Int? = null
        val todayStart = getTodayStart(currentTimeMillis())
        for (tok in tokens) {
            val result =
                try {
                    api.getMissionList(tok)
                } catch (e: Exception) {
                    onLogError("fetchMergedMissions.missionLst", e)
                    continue
                }
            if (dailyToken.isEmpty() && result.dailyAdId.isNotBlank()) {
                dailyAdId = result.dailyAdId
                dailyScore = result.dailyScore
                dailyToken = tok
            }
            weekMask = weekMask or result.weekMask
            result.validScore?.let { v -> validScore = validScore?.let { maxOf(it, v) } ?: v }
            result.totalScore?.let { v -> totalScore = totalScore?.let { maxOf(it, v) } ?: v }

            val todayCount = fetchTodayScoreCounts(tok, todayStart)

            for (m in result.missions) {
                if (m.adId.isBlank()) continue
                val done = maxOf(m.dailyCompleted, todayCount[m.adId] ?: 0)
                val existing = merged[m.adId]
                if (existing == null) {
                    merged[m.adId] =
                        MissionInfo(
                            adId = m.adId,
                            name = m.name,
                            score = m.score,
                            limit = m.limit,
                            dailyCompleted = done,
                            isDailySignin = m.isDailySignin,
                            sourceToken = tok,
                        )
                } else {
                    val keepExisting = existing.limit > 0 || m.limit <= 0
                    merged[m.adId] =
                        existing.copy(
                            name = existing.name.ifBlank { m.name },
                            score = maxOf(existing.score, m.score),
                            limit = if (keepExisting) existing.limit else m.limit,
                            dailyCompleted = maxOf(existing.dailyCompleted, done),
                            sourceToken = if (keepExisting) existing.sourceToken else tok,
                        )
                }
            }
        }
        return MergedMissions(
            missions = merged.values.toList(),
            weekMask = weekMask,
            dailyAdId = dailyAdId,
            dailyScore = dailyScore,
            dailyToken = dailyToken,
            validScore = validScore,
            totalScore = totalScore,
        )
    }

    fun loadMissions() {
        val account = getState().account
        if (account.appToken.isEmpty() && account.token.isEmpty()) return
        val now = currentTimeMillis()
        if (now - lastMissionsLoadTime < LOAD_COOLDOWN_MS) return
        lastMissionsLoadTime = now
        scope.launch { reloadMissionsAndReturn() }
    }

    // 拉取任务列表与完成状态，成功时更新 missions/weekMask/积分；账号已切换则返回 null
    private suspend fun reloadMissionsAndReturn(): MergedMissions? {
        val startApp = getState().account.appToken
        val startPoints = getState().account.token
        return try {
            val merged = fetchMergedMissions()
            // 账号已切换/退出登录，丢弃过期结果
            if (getState().account.appToken != startApp || getState().account.token != startPoints) return null
            missions = merged.missions
            setState {
                it.copy(
                    weekMask = merged.weekMask,
                    points =
                        it.points.copy(
                            available = merged.validScore ?: it.points.available,
                            total = merged.totalScore ?: it.points.total,
                        ),
                )
            }
            merged
        } catch (e: Exception) {
            onLogError("loadMissions", e)
            null
        }
    }

    // 任务是否已全部完成：每日签到 + 所有有上限任务均达上限
    private fun isAllTasksCompleted(
        missions: List<MissionInfo>,
        weekMask: Int,
    ): Boolean {
        if (missions.isEmpty()) return false
        val regular = missions.filter { !it.isDailySignin && it.limit > 0 && it.score > 0 }
        val allDone = regular.all { it.dailyCompleted >= it.limit }
        val weekDay = getDayOfWeek()
        val signInDone =
            missions.firstOrNull { it.isDailySignin }?.let {
                (weekMask and (1 shl (weekDay - 1))) != 0
            } ?: true
        return allDone && signInDone
    }

    // 当前任务的完成判定（供 UI 使用）
    fun areAllTasksCompleted(): Boolean = isAllTasksCompleted(missions, getState().weekMask)

    // 桌面快捷方式“运行积分任务”：未登录/运行中/已完成时给出提示
    fun runFromShortcut() {
        val account = getState().account
        if (account.appToken.isEmpty() && account.token.isEmpty()) {
            onToast("请先登录")
            return
        }
        if (getLoading()) {
            onToast("积分任务正在运行")
            return
        }
        if (getState().taskCompleted) {
            onToast("今日任务已完成")
            return
        }
        scope.launch {
            // 拉取最新任务与完成状态，全部完成时不再运行
            lastMissionsLoadTime = currentTimeMillis()
            val merged = reloadMissionsAndReturn()
            if (merged != null && isAllTasksCompleted(merged.missions, merged.weekMask)) {
                setState { it.copy(taskCompleted = true) }
                onToast("今日任务已完成")
                return@launch
            }
            runAll()
        }
    }

    fun runAll() {
        if (getState().taskCompleted || getLoading()) return
        val uid = getState().account.uid
        val account = getState().account
        if ((account.appToken.isEmpty() && account.token.isEmpty()) || uid.isEmpty()) {
            onError("需要积分登录才能执行任务")
            return
        }
        val startApp = account.appToken
        val startPoints = account.token
        val isCurrentAccount = { getState().account.appToken == startApp && getState().account.token == startPoints }
        lastMissionsLoadTime = 0L
        onResetScoreCooldown()
        onToast("开始运行积分任务")
        taskJob =
            scope.launch {
                onLoadingChange(true)
                onError(null)
                setState { it.copy(taskLogs = emptyList()) }
                rateLimiter.clear()
                // 等通知权限弹窗关闭（无论是否授权）后再开始，保证实时动态/前台服务通知能正常展示
                requestNotificationPermission()
                RunNotifications.updateTask(0)
                try {
                    addTaskLog("正在加载任务列表...")
                    val merged = fetchMergedMissions()
                    if (merged.validScore != null || merged.totalScore != null) {
                        setState {
                            it.copy(
                                points =
                                    it.points.copy(
                                        available = merged.validScore ?: it.points.available,
                                        total = merged.totalScore ?: it.points.total,
                                    ),
                            )
                        }
                    }
                    val missionList = merged.missions
                    missions = missionList
                    var gained = 0

                    gained += runDailySignin(merged, uid)

                    val validMissions =
                        missionList.filter {
                            !it.isDailySignin && it.limit > 0 && it.score > 0 &&
                                it.sourceToken.isNotEmpty()
                        }
                    addTaskLog("共 ${validMissions.size} 个可执行任务")
                    val execResult = runMissions(validMissions, uid, gained)
                    gained = execResult.gained

                    if (isActive && isCurrentAccount()) {
                        addTaskLog("任务完成，共获得 $gained 积分")
                        onToast("积分任务已完成，共获得 $gained 积分")
                        setState {
                            it.copy(
                                taskCompleted = true,
                                points = it.points.copy(available = (it.points.available ?: 0) + gained),
                            )
                        }
                        onReloadScores()
                    }
                } catch (_: CancellationException) {
                    if (isCurrentAccount()) addTaskLog("任务已停止")
                } catch (e: Exception) {
                    if (isCurrentAccount()) {
                        addTaskLog("任务执行异常: ${e.message}")
                        onError("任务执行失败: ${e.message}")
                    }
                } finally {
                    RunNotifications.removeTask()
                    if (isCurrentAccount()) onLoadingChange(false)
                    if (taskJob === currentCoroutineContext()[Job]) taskJob = null
                }
            }
    }

    /**
     * 每日签到阶段。今日已签或缺少签到凭证时直接跳过。
     * 返回本轮新增积分（成功才计入），调用方累加到总收益。
     */
    private suspend fun runDailySignin(
        merged: MergedMissions,
        uid: String,
    ): Int {
        if (merged.dailyAdId.isBlank() || merged.dailyToken.isEmpty()) return 0
        val signToken = merged.dailyToken
        val weekDay = getDayOfWeek()
        val alreadySigned = (merged.weekMask and (1 shl (weekDay - 1))) != 0
        if (alreadySigned) {
            addTaskLog("每日签到: 今日已签到")
            return 0
        }
        val wait = rateLimiter.reserveDelay(signToken, currentTimeMillis())
        if (wait > 0) {
            addTaskLog("等待 ${wait / 1000} 秒...")
            delay(wait.milliseconds)
        }
        addTaskLog("每日签到: 执行中...")
        val signResult = withNetworkRetry { api.signIn(signToken, uid, weekDay, merged.dailyAdId) }
        if (signResult.success) {
            RunNotifications.updateTask(merged.dailyScore)
            addTaskLog("每日签到: 成功 +${merged.dailyScore}分")
            return merged.dailyScore
        }
        if (signResult.code == -98) {
            addTaskLog("每日签到: 请求频繁，等待60秒重试...")
            delay(60.seconds)
            val retryResult = withNetworkRetry { api.signIn(signToken, uid, weekDay, merged.dailyAdId) }
            if (retryResult.success) {
                RunNotifications.updateTask(merged.dailyScore)
                addTaskLog("每日签到: 成功 +${merged.dailyScore}分")
                return merged.dailyScore
            }
            addTaskLog("每日签到: 失败 ${retryResult.message}")
        } else {
            addTaskLog("每日签到: 失败 ${signResult.message}")
        }
        return 0
    }

    /** 逐任务执行阶段。返回累计收益，供调用方继续累加。 */
    private suspend fun runMissions(
        validMissions: List<MissionInfo>,
        uid: String,
        startGained: Int,
    ): MissionRunResult {
        var gained = startGained
        for (mission in validMissions) {
            if (!currentCoroutineContext().isActive) break
            val completed = mission.dailyCompleted
            val maxCount = mission.limit
            if (completed >= maxCount) {
                addTaskLog("[${mission.name}] 已完成 $completed/$maxCount，跳过")
                continue
            }
            try {
                gained = runMissionRounds(mission, uid, gained)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 单个任务失败不中断整轮，继续后续任务
                addTaskLog("[${mission.name}] 执行异常: ${e.message}")
            }
        }
        return MissionRunResult(gained)
    }

    /** 单个任务的多轮执行，返回累加后的收益。请求频繁时跳出该任务的剩余轮次。 */
    private suspend fun runMissionRounds(
        mission: MissionInfo,
        uid: String,
        startGained: Int,
    ): Int {
        var gained = startGained
        val execToken = mission.sourceToken
        val maxCount = mission.limit
        for (round in (mission.dailyCompleted + 1)..maxCount) {
            if (!currentCoroutineContext().isActive) break
            addTaskLog("[${mission.name}] ($round/$maxCount) 执行中...")
            delay(30.seconds)
            var execResult = withNetworkRetry { api.executeMission(execToken, uid, mission.adId) }
            if (execResult.code == -98) {
                addTaskLog("[${mission.name}] 请求频繁，等待60秒重试...")
                delay(60.seconds)
                execResult = withNetworkRetry { api.executeMission(execToken, uid, mission.adId) }
            }
            if (execResult.success) {
                gained += mission.score
                RunNotifications.updateTask(gained)
                addTaskLog("[${mission.name}] ($round/$maxCount) 成功 +${mission.score}分")
                missions =
                    missions.map {
                        if (it.adId == mission.adId) it.copy(dailyCompleted = it.dailyCompleted + 1) else it
                    }
            } else if (execResult.code == -98) {
                addTaskLog("[${mission.name}] 请求频繁，跳过剩余任务")
                break
            } else {
                addTaskLog("[${mission.name}] ($round/$maxCount) 失败: ${execResult.message}")
            }
        }
        return gained
    }

    fun stop() {
        addTaskLog("正在停止...")
        taskJob?.cancel()
        taskJob = null
        onLoadingChange(false)
        RunNotifications.removeTask()
        onToast("积分任务已停止")
    }

    fun setCompleted() {
        setState { it.copy(taskCompleted = true) }
    }

    // 下拉刷新：运行中只更新完成情况并保留日志；未运行时刷新任务列表并清空隐藏日志
    fun refresh() {
        if (tasksRefreshing) return
        tasksRefreshing = true
        scope.launch {
            try {
                if (getLoading()) {
                    val completed = isAllTasksCompleted(missions, getState().weekMask)
                    setState { it.copy(taskCompleted = completed) }
                } else {
                    lastMissionsLoadTime = currentTimeMillis()
                    val merged = reloadMissionsAndReturn() ?: return@launch
                    setState {
                        it.copy(
                            taskCompleted = isAllTasksCompleted(merged.missions, merged.weekMask),
                            taskLogs = emptyList(),
                        )
                    }
                }
            } finally {
                tasksRefreshing = false
            }
        }
    }

    // 网络重试统一走 NetworkErrors.kt 的共享实现，此处只负责把重试写进任务日志
    private suspend fun <T> withNetworkRetry(block: suspend () -> T): T =
        withNetworkRetry(
            onRetry = { seconds, attempt ->
                addTaskLog("网络异常，$seconds 秒后重试($attempt/${NETWORK_RETRY_ATTEMPTS - 1})...")
            },
            block = block,
        )

    private fun addTaskLog(message: String) {
        val time = currentTimeFormatted("HH:mm:ss")
        setState { it.copy(taskLogs = it.taskLogs + "[$time] $message") }
    }
}
