package com.github.ilife798.data.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.ilife798.RunNotifications
import com.github.ilife798.data.api.CODE_RATE_LIMITED
import com.github.ilife798.data.api.IlifeApi
import com.github.ilife798.data.api.PointsQueryException
import com.github.ilife798.data.model.Account
import com.github.ilife798.data.model.AppState
import com.github.ilife798.data.model.MissionInfo
import com.github.ilife798.data.model.PointsInfo
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

    /*
     * 到账核验状态（借鉴下游 dong-dong6/ilife798 v1.3.15）。
     *
     * 不用客户端自己累加 `gained += score`——那样算的只是「请求成功的标称分」，
     * 请求成功但服务端没入账时会虚高。改为回查 acc/score/acc-score 的 totalScore
     * 真实增量，能区分「请求成功但没到账」与「真的到账了」。
     */
    private class Earnings(
        val account: Account,
        val before: Int?,
    ) {
        var verified = 0
        var queryFailed = false
    }

    /*
     * 按通道优先级查积分汇总：积分 token 优先，缺失时回退 appToken。
     *
     * 服务端对 acc-score 有频控（-98）：刚跑完一轮任务、或刚在另一账号上高频查询后
     * 切号立即运行，很容易命中。此前一次限流就让整轮任务无法开始（issue #5），
     * 这里对 -98 做短退避重试；其他业务码（-99 会话过期等）不重试，直接换下一个 token。
     */
    private suspend fun pointsFor(account: Account): PointsInfo {
        val tokens = listOf(account.token to false, account.appToken to true).filter { it.first.isNotEmpty() }
        var failure: Exception? = null
        tokenLoop@ for ((token, app) in tokens) {
            for (attempt in 0 until POINTS_QUERY_ATTEMPTS) {
                try {
                    val result = api.getPointsSummary(token, app)
                    check(result.total != null) { "缺少累计积分，无法核验到账" }
                    return result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e
                    val retryable = (e as? PointsQueryException)?.code == CODE_RATE_LIMITED
                    if (!retryable || attempt == POINTS_QUERY_ATTEMPTS - 1) continue@tokenLoop
                    addTaskLog("积分查询被限流，${POINTS_QUERY_RETRY_DELAY_MS / 1000} 秒后重试…")
                    delay(POINTS_QUERY_RETRY_DELAY_MS)
                }
            }
        }
        throw failure ?: IllegalStateException("没有可用的积分查询登录")
    }

    // 回查累计积分增量，作为本轮实际到账数。核验失败时保留此前已核验值，不吞掉进度。
    private suspend fun verifyPoints(earnings: Earnings): Int {
        val account = getState().account
        if (account.appToken != earnings.account.appToken || account.token != earnings.account.token) {
            throw CancellationException("账号已切换")
        }
        try {
            val points = pointsFor(earnings.account)
            if (getState().account.appToken != earnings.account.appToken ||
                getState().account.token != earnings.account.token
            ) {
                throw CancellationException("账号已切换")
            }
            val before = earnings.before
            if (before == null) {
                // 本轮开始时未获取到积分基准（issue #5 降级路径）：无法计算增量，
                // 但仍展示查询到的当前累计，保留 queryFailed 让结尾提示如实反映。
                earnings.queryFailed = true
                setState { it.copy(points = points) }
                addTaskLog("本轮开始时没有积分基准，无法核验增量（当前累计 ${points.total ?: "未知"} 分）")
                return earnings.verified
            }
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
        val dailyIsApp: Boolean,
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
        var dailyIsApp = true
        var validScore: Int? = null
        var totalScore: Int? = null
        var loadedChannels = 0
        for (tok in tokens) {
            // token 与通道必须配对：appToken 走 1,1，积分 token 走 1,5。
            // 上游此前对两个 token 都按 1,1 请求，不仅列表可能取不全，
            // 更关键的是后面 score-send 会拿错通道签名，服务端判「验签失败」。
            // 积分 token 与 appToken 相同的场景（只登录了 App 渠道）按 App 通道处理。
            val isApp = tok == appToken
            val result =
                try {
                    api.getMissionList(tok, isApp)
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
                dailyIsApp = isApp
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
                            isAppChannel = isApp,
                        )
                } else {
                    val keepExisting = existing.limit > 0 || m.limit <= 0
                    merged[m.adId] =
                        existing.copy(
                            name = existing.name.ifBlank { m.name },
                            score = maxOf(existing.score, m.score),
                            limit = if (keepExisting) existing.limit else m.limit,
                            dailyCompleted = maxOf(existing.dailyCompleted, done),
                            // token 与通道必须同进同出，不能只换其中一个
                            sourceToken = if (keepExisting) existing.sourceToken else tok,
                            isAppChannel = if (keepExisting) existing.isAppChannel else isApp,
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
            dailyIsApp = dailyIsApp,
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
                    // 任务完成态只由服务端数据推导，不做本地持久化：
                    // 启动、切号、下拉、运行结束后都走这里，跨天自然恢复。
                    taskCompleted = isAllTasksCompleted(merged.missions, merged.weekMask),
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
        // 没有可执行任务（只有签到或全是 0 分任务）不能算「今日任务已完成」：
        // all{} 对空集合恒真，会把根本没跑过的状态判成完成。
        if (regular.isEmpty()) return false
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
            // 拉取最新任务与完成状态（reload 内部会按服务端数据刷新 taskCompleted），
            // 全部完成时不再运行
            lastMissionsLoadTime = currentTimeMillis()
            if (reloadMissionsAndReturn() != null && getState().taskCompleted) {
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
                    /*
                     * issue #5：切换/登录第二个账号后立即运行任务时，acc-score 可能被服务端
                     * 频控或风控拒绝（A 账号刚高频查询过）。此前直接中止整轮任务，用户什么都跑不了。
                     * 现在降级：查询失败仍继续执行任务，只是本轮不做到账核验（before = null），
                     * 具体的服务端 code/msg 已由 PointsQueryException 带入运行日志。
                     */
                    val initialPoints =
                        try {
                            pointsFor(account)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            addTaskLog("积分查询失败（${e.message}），继续执行任务，本轮不做到账核验")
                            null
                        }
                    if (!isCurrentAccount()) throw CancellationException("账号已切换")
                    val earnings = Earnings(account, initialPoints?.total)
                    runEarnings = earnings
                    if (initialPoints != null) {
                        setState { it.copy(points = initialPoints) }
                    }
                    var gained = 0

                    gained = runDailySignin(merged, uid, earnings)

                    val accountTokens = setOf(account.token, account.appToken).filter { it.isNotEmpty() }.toSet()
                    val validMissions =
                        missionList.filter {
                            !it.isDailySignin && it.limit > 0 && it.score > 0 &&
                                it.sourceToken.isNotEmpty() &&
                                // 任务必须源自当前账号持有的会话：切换账号后若内存里
                                // 残留了上一账号的任务（isCurrentAccount 比对时序问题等），
                                // 其 sourceToken 不在当前 token 集合内，这里直接剔除，
                                // 避免用错误 token 签名执行（issue #5 内存状态类根因兜底）。
                                it.sourceToken in accountTokens &&
                                // 这类任务涉及金融营销，直接跳过（与下游一致的安全防护）
                                listOf("免费权益", "借贷", "贷款").none { name -> name in it.name }
                        }
                    addTaskLog("共 ${validMissions.size} 个可执行任务")
                    runMissions(validMissions, uid, gained, earnings)
                    gained = verifyPoints(earnings)

                    if (isActive && isCurrentAccount()) {
                        // 重新拉取任务列表：完成态由 reloadMissionsAndReturn 统一按服务端数据推导
                        reloadMissionsAndReturn()
                        val resultText =
                            if (earnings.queryFailed) {
                                "任务请求结束，到账核验失败，已核验 $gained 分"
                            } else {
                                "任务请求结束，已核验本轮期间增加 $gained 分"
                            }
                        addTaskLog(resultText)
                        onToast(resultText)
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
                // 网络结果不明确时停止本轮，先核对到账，避免重复提交
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
                    setState { it.copy(taskCompleted = isAllTasksCompleted(missions, getState().weekMask)) }
                } else {
                    lastMissionsLoadTime = currentTimeMillis()
                    // reload 内部按服务端数据刷新 taskCompleted
                    reloadMissionsAndReturn() ?: return@launch
                    setState { it.copy(taskLogs = emptyList()) }
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

    private companion object {
        // 积分汇总查询：-98 频控时的重试次数与退避间隔（issue #5）
        const val POINTS_QUERY_ATTEMPTS = 2
        const val POINTS_QUERY_RETRY_DELAY_MS = 5_000L
    }
}
