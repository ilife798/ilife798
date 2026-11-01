package com.github.ilife798.data.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.ilife798.RunNotifications
import com.github.ilife798.data.api.IlifeApi
import com.github.ilife798.data.model.Account
import com.github.ilife798.data.model.AppState
import com.github.ilife798.data.model.MissionInfo
import com.github.ilife798.update.requestNotificationPermission
import com.github.ilife798.util.currentTimeFormatted
import com.github.ilife798.util.currentTimeMillis
import com.github.ilife798.util.getDayOfWeek
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

    private class Earnings(
        val account: Account,
        val before: Int?,
    ) {
        var verified = 0
        var queryFailed = false
    }

    private suspend fun pointsFor(account: Account): com.github.ilife798.data.model.PointsInfo {
        val tokens = listOf(account.token to false, account.appToken to true).filter { it.first.isNotEmpty() }
        var failure: Exception? = null
        for ((token, app) in tokens) {
            try {
                val result = api.getPointsSummary(token, app)
                check(result.total != null) { "缺少累计积分，无法核验到账" }
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IllegalStateException("没有可用的积分查询登录")
    }

    private suspend fun verifyPoints(earnings: Earnings): Int {
        val account = getState().account
        if (account.appToken != earnings.account.appToken || account.token != earnings.account.token) throw CancellationException("账号已切换")
        try {
            val points = pointsFor(earnings.account)
            if (getState().account.appToken != earnings.account.appToken ||
                getState().account.token != earnings.account.token
            ) {
                throw CancellationException("账号已切换")
            }
            val before = checkNotNull(earnings.before) { "缺少本轮初始积分" }
            val delta = checkNotNull(points.total) - before
            check(delta >= earnings.verified) { "累计积分减少，无法确认本轮到账" }
            earnings.verified = delta
            earnings.queryFailed = false
            setState { it.copy(points = points) }
            RunNotifications.updateTask(delta)
            addTaskLog("到账核验：本轮期间累计积分增加 $delta 分（包含其他渠道入账）")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            earnings.queryFailed = true
            addTaskLog("到账核验失败，保留此前已核验 ${earnings.verified} 分，请稍后刷新")
        }
        return earnings.verified
    }

    private data class MergedMissions(
        val missions: List<MissionInfo>,
        val weekMask: Int,
        val dailyAdId: String,
        val dailyScore: Int,
        val dailyToken: String,
        val validScore: Int?,
        val totalScore: Int?,
    )

    // 合并两个登录渠道（appToken 1,1 与 token 1,5）的任务列表，按 adId 去重并记录来源 token
    private suspend fun fetchMergedMissions(): MergedMissions {
        val account = getState().account
        val appToken = account.appToken
        val pointsToken = account.token
        val tokens =
            buildList {
                if (pointsToken.isNotEmpty()) add(pointsToken)
                if (appToken.isNotEmpty() && appToken != pointsToken) add(appToken)
            }
        val merged = LinkedHashMap<String, MissionInfo>()
        var weekMask = 0
        var dailyAdId = ""
        var dailyScore = 5
        var dailyToken = ""
        var validScore: Int? = null
        var totalScore: Int? = null
        var loadedChannels = 0
        for (tok in tokens) {
            val result =
                try {
                    api.getMissionList(tok, tok == appToken)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onLogError("fetchMergedMissions.missionLst", e)
                    continue
                }
            loadedChannels++
            if (dailyToken.isEmpty() && result.dailyAdId.isNotBlank()) {
                dailyAdId = result.dailyAdId
                dailyScore = result.dailyScore
                dailyToken = tok
            }
            weekMask = weekMask or result.weekMask
            result.validScore?.let { v -> validScore = validScore?.let { maxOf(it, v) } ?: v }
            result.totalScore?.let { v -> totalScore = totalScore?.let { maxOf(it, v) } ?: v }

            for (m in result.missions) {
                if (m.adId.isBlank()) continue
                val done = m.dailyCompleted
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
                }
            }
        }
        check(loadedChannels > 0) { "未能读取任何渠道的任务进度，请检查登录或稍后重试" }
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
                var runEarnings: Earnings? = null
                try {
                    addTaskLog("正在加载任务列表...")
                    val merged = fetchMergedMissions()
                    if (!isCurrentAccount()) throw CancellationException("账号已切换")
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
                    val initialPoints = pointsFor(account)
                    if (!isCurrentAccount()) throw CancellationException("账号已切换")
                    val earnings = Earnings(account, initialPoints.total)
                    runEarnings = earnings
                    setState { it.copy(points = initialPoints) }
                    var gained = 0

                    gained = runDailySignin(merged, uid, earnings)

                    val validMissions =
                        missionList.filter {
                            !it.isDailySignin && it.limit > 0 && it.score > 0 &&
                                it.sourceToken.isNotEmpty() && listOf("免费权益", "借贷", "贷款").none { name -> name in it.name }
                        }
                    addTaskLog("共 ${validMissions.size} 个可执行任务")
                    runMissions(validMissions, uid, gained, earnings)
                    gained = verifyPoints(earnings)

                    if (isActive && isCurrentAccount()) {
                        val latest = reloadMissionsAndReturn()
                        val resultText = if (earnings.queryFailed) "任务请求结束，到账核验失败，已核验 $gained 分" else "任务请求结束，已核验本轮期间增加 $gained 分"
                        addTaskLog(resultText)
                        onToast(resultText)
                        setState {
                            it.copy(
                                taskCompleted =
                                    !earnings.queryFailed && latest != null && isAllTasksCompleted(latest.missions, latest.weekMask),
                            )
                        }
                        onReloadScores()
                    }
                } catch (_: CancellationException) {
                    if (isCurrentAccount()) addTaskLog("任务已停止")
                } catch (e: Exception) {
                    if (isCurrentAccount()) {
                        runEarnings?.let { verifyPoints(it) }
                        addTaskLog("任务执行异常: ${e.message}")
                        onError("任务执行失败: ${e.message}")
                    }
                } finally {
                    RunNotifications.removeTask()
                    if (isCurrentAccount()) {
                        onLoadingChange(false)
                        onReloadScores()
                    }
                    if (taskJob === currentCoroutineContext()[Job]) taskJob = null
                }
            }
    }

    /**
     * 每日签到阶段。今日已签或缺少签到凭证时直接跳过。
     * 返回截至当前已核验的累计增量，不按任务标称分数累计。
     */
    private suspend fun runDailySignin(
        merged: MergedMissions,
        uid: String,
        earnings: Earnings,
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
        val signResult = api.signIn(signToken, uid, weekDay, merged.dailyAdId, merged.dailyScore, signToken == earnings.account.appToken)
        if (signResult.success) {
            addTaskLog("每日签到请求成功，正在核验到账")
            return verifyPoints(earnings)
        }
        if (signResult.code == -98) {
            addTaskLog("每日签到: 请求频繁，等待60秒重试...")
            delay(60.seconds)
            val retryResult =
                api.signIn(
                    signToken,
                    uid,
                    weekDay,
                    merged.dailyAdId,
                    merged.dailyScore,
                    signToken == earnings.account.appToken,
                )
            if (retryResult.success) {
                addTaskLog("每日签到请求成功，正在核验到账")
                return verifyPoints(earnings)
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
        earnings: Earnings,
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
                gained = runMissionRounds(mission, uid, gained, earnings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 网络结果不明确时停止本轮，先核对到账，避免重复提交。
                addTaskLog("[${mission.name}] 请求结果不明确，停止本轮并刷新积分")
                throw e
            }
        }
        return MissionRunResult(gained)
    }

    /** 单个任务的多轮执行，返回累加后的收益。请求频繁时跳出该任务的剩余轮次。 */
    private suspend fun runMissionRounds(
        mission: MissionInfo,
        uid: String,
        startGained: Int,
        earnings: Earnings,
    ): Int {
        var gained = startGained
        val execToken = mission.sourceToken
        val maxCount = mission.limit
        for (round in (mission.dailyCompleted + 1)..maxCount) {
            if (!currentCoroutineContext().isActive) break
            addTaskLog("[${mission.name}] ($round/$maxCount) 执行中...")
            delay(30.seconds)
            var execResult = api.executeMission(execToken, uid, mission.adId, mission.score, execToken == earnings.account.appToken)
            if (execResult.code == -98) {
                addTaskLog("[${mission.name}] 请求频繁，等待60秒重试...")
                delay(60.seconds)
                execResult = api.executeMission(execToken, uid, mission.adId, mission.score, execToken == earnings.account.appToken)
            }
            if (execResult.success) {
                addTaskLog("[${mission.name}] ($round/$maxCount) 请求成功，正在核验到账")
                gained = verifyPoints(earnings)
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

    private fun addTaskLog(message: String) {
        val time = currentTimeFormatted("HH:mm:ss")
        setState { it.copy(taskLogs = it.taskLogs + "[$time] $message") }
    }
}
