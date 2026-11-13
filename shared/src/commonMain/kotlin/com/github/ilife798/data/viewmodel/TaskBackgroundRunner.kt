package com.github.ilife798.data.viewmodel

import com.github.ilife798.data.api.CODE_RATE_LIMITED
import com.github.ilife798.data.api.IlifeApi
import com.github.ilife798.data.api.PointsQueryException
import com.github.ilife798.data.model.Account
import com.github.ilife798.data.model.MissionInfo
import com.github.ilife798.data.model.PointsInfo
import com.github.ilife798.util.currentTimeMillis
import com.github.ilife798.util.getDayOfWeek
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** 单个账号在后台跑完一轮的结果。 */
data class BackgroundAccountResult(
    val label: String,
    val gained: Int,
    /** false 表示这一轮没能核验到账（例如缺少积分基准）。 */
    val verified: Boolean,
    val message: String,
)

/**
 * 后台执行积分任务：不依赖界面、不切号、不读写 AppState。
 *
 * 供「定时任务 - 后台运行」使用。与界面路径（[TaskController] + [TaskBatchController]）的差别是
 * 这里对每个账号直接用它自己的 token 发请求，因此不需要切号，也不会打断用户正在看的界面。
 *
 * 代价是逻辑与 [TaskController] 有一份并行实现：界面路径绑定 AppState 与 isLoading，
 * 后台路径必须能在应用进程被闹钟唤起、UI 尚未建立时独立工作。取舍是保留两条路径，
 * 而不是给既有的 608 行 UI 关键路径加分支。
 */
class TaskBackgroundRunner(
    private val api: IlifeApi,
    private val onLog: (String) -> Unit = {},
) {
    private val rateLimiter = TokenRateLimiter()

    /** 供进度展示：当前第几个账号、共几个、账号名。 */
    var onAccountStart: ((index: Int, total: Int, label: String) -> Unit)? = null

    suspend fun runAll(accounts: List<Account>): List<BackgroundAccountResult> {
        val targets = accounts.filter { it.hasAnyToken && it.uid.isNotEmpty() }
        val results = mutableListOf<BackgroundAccountResult>()
        targets.forEachIndexed { index, account ->
            val label = labelOf(account)
            onAccountStart?.invoke(index + 1, targets.size, label)
            onLog("开始执行 $label")
            results +=
                try {
                    runAccount(account, label)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onLog("$label 执行失败：${e.message}")
                    BackgroundAccountResult(label, 0, false, "执行失败")
                }
        }
        return results
    }

    private suspend fun runAccount(
        account: Account,
        label: String,
    ): BackgroundAccountResult {
        rateLimiter.clear()
        val missionList = fetchMergedMissions(account)
        val before = pointsOrNull(account)
        if (before?.total == null) {
            onLog("$label 本轮开始时没有积分基准，无法核验增量")
        }
        var gained = 0
        var queryFailed = before?.total == null

        runDailySignin(account, missionList, label)?.let { gained += it }

        val tokens = setOf(account.token, account.appToken).filter { it.isNotEmpty() }.toSet()
        val validMissions =
            missionList.missions.filter { mission ->
                !mission.isDailySignin &&
                    mission.limit > 0 &&
                    mission.score > 0 &&
                    mission.sourceToken.isNotEmpty() &&
                    mission.sourceToken in tokens &&
                    FINANCE_KEYWORDS.none { it in mission.name }
            }
        if (validMissions.isEmpty()) {
            onLog("$label 没有可执行任务")
        } else {
            onLog("$label 共 ${validMissions.size} 个可执行任务")
        }

        for (mission in validMissions) {
            gained += runMission(account, mission, label)
        }

        val delta = verifyDelta(account, before, gained)
        if (delta == null) queryFailed = true
        val verifiedPoints = delta ?: gained
        val message =
            when {
                validMissions.isEmpty() && verifiedPoints == 0 -> "没有可执行任务"
                queryFailed -> "到账核验失败，已核验 $verifiedPoints 分"
                verifiedPoints > 0 -> "获得 $verifiedPoints 分"
                else -> "任务已执行，本轮无新增积分"
            }
        return BackgroundAccountResult(label, verifiedPoints, !queryFailed, message)
    }

    private suspend fun fetchMergedMissions(account: Account): MergedMissions {
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
        var dailyScore = 0
        var dailyToken = ""
        var validScore: Int? = null
        var totalScore: Int? = null
        var loadedChannels = 0
        for (token in tokens) {
            val isApp = token == appToken
            val result =
                runCatching { api.getMissionList(token, isApp) }
                    .onFailure { onLog("读取任务进度失败：${it.message}") }
                    .getOrNull() ?: continue
            loadedChannels++
            weekMask = weekMask or result.weekMask
            if (result.dailyAdId.isNotEmpty() && dailyToken.isEmpty()) {
                dailyAdId = result.dailyAdId
                dailyScore = result.dailyScore
                dailyToken = token
            }
            validScore = maxOf(validScore ?: 0, result.validScore ?: 0).takeIf { validScore != null || result.validScore != null }
            totalScore = maxOf(totalScore ?: 0, result.totalScore ?: 0).takeIf { totalScore != null || result.totalScore != null }
            result.missions.forEach { mission ->
                val existing = merged[mission.adId]
                merged[mission.adId] =
                    if (existing == null) {
                        MissionInfo(
                            adId = mission.adId,
                            name = mission.name,
                            score = mission.score,
                            limit = mission.limit,
                            dailyCompleted = mission.dailyCompleted,
                            isDailySignin = mission.isDailySignin,
                            sourceToken = token,
                            isAppChannel = isApp,
                        )
                    } else {
                        existing.copy(
                            limit = maxOf(existing.limit, mission.limit),
                            score = maxOf(existing.score, mission.score),
                            dailyCompleted = maxOf(existing.dailyCompleted, mission.dailyCompleted),
                        )
                    }
            }
        }
        check(loadedChannels > 0) { "未能读取任何渠道的任务进度" }
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

    private suspend fun runDailySignin(
        account: Account,
        merged: MergedMissions,
        label: String,
    ): Int? {
        if (merged.dailyAdId.isBlank() || merged.dailyToken.isEmpty()) return null
        val weekDay = getDayOfWeek()
        if ((merged.weekMask and (1 shl (weekDay - 1))) != 0) {
            onLog("$label 每日签到：今日已签到")
            return null
        }
        val signToken = merged.dailyToken
        val isApp = signToken == account.appToken
        repeat(RATE_LIMIT_RETRIES) { attempt ->
            val wait = rateLimiter.reserveDelay(signToken, currentTimeMillis())
            if (wait > 0) delay(wait.milliseconds)
            val result =
                runCatching {
                    api.signIn(signToken, account.uid, weekDay, merged.dailyAdId, merged.dailyScore, isApp)
                }.getOrElse {
                    onLog("$label 签到异常：${it.message}")
                    return null
                }
            if (result.success) {
                onLog("$label 签到成功")
                return 0
            }
            if (result.code != CODE_RATE_LIMITED || attempt == RATE_LIMIT_RETRIES - 1) {
                onLog("$label 签到失败：${result.message}")
                return null
            }
            delay(RATE_LIMIT_BACKOFF_MS.milliseconds)
        }
        return null
    }

    private suspend fun runMission(
        account: Account,
        mission: MissionInfo,
        label: String,
    ): Int {
        val maxCount = mission.limit
        if (mission.dailyCompleted >= maxCount) {
            onLog("$label ${mission.name} 已完成，跳过")
            return 0
        }
        val execToken = mission.sourceToken
        val isApp = execToken == account.appToken
        var round = mission.dailyCompleted
        while (round < maxCount) {
            round++
            onLog("$label ${mission.name} ($round/$maxCount) 执行中…")
            // 与界面路径一致：每个任务之间留出间隔，避免连续请求触发频控
            delay(MISSION_INTERVAL_MS.milliseconds)
            val wait = rateLimiter.reserveDelay(execToken, currentTimeMillis())
            if (wait > 0) delay(wait.milliseconds)
            var result =
                runCatching { api.executeMission(execToken, account.uid, mission.adId, mission.score, isApp) }
                    .getOrElse {
                        onLog("$label ${mission.name} 请求结果不明确，停止本轮")
                        return 0
                    }
            if (!result.success && result.code == CODE_RATE_LIMITED) {
                delay(RATE_LIMIT_BACKOFF_MS.milliseconds)
                result =
                    runCatching { api.executeMission(execToken, account.uid, mission.adId, mission.score, isApp) }
                        .getOrElse { return 0 }
            }
            if (!result.success) {
                if (result.code == CODE_RATE_LIMITED) {
                    onLog("$label 请求频繁，跳过剩余任务")
                    return 0
                }
                onLog("$label ${mission.name} 失败：${result.message}")
                return 0
            }
        }
        return 0
    }

    /** 用累计积分增量核验到账；拿不到基准或差值异常时返回 null。 */
    private suspend fun verifyDelta(
        account: Account,
        before: PointsInfo?,
        fallback: Int,
    ): Int? {
        val base = before?.total ?: return null
        val after = pointsOrNull(account) ?: return null
        val total = after.total ?: return null
        val delta = total - base
        return if (delta < 0) null else delta.coerceAtLeast(fallback.coerceAtMost(delta))
    }

    private suspend fun pointsOrNull(account: Account): PointsInfo? {
        val appToken = account.appToken
        val pointsToken = account.token
        val tokens =
            buildList {
                if (pointsToken.isNotEmpty()) add(pointsToken to false)
                if (appToken.isNotEmpty() && appToken != pointsToken) add(appToken to true)
            }
        if (tokens.isEmpty()) return null
        var failure: Exception? = null
        for ((token, isApp) in tokens) {
            repeat(POINTS_QUERY_ATTEMPTS) { attempt ->
                try {
                    return api.getPointsSummary(token, isApp)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PointsQueryException) {
                    failure = e
                    if (e.code != CODE_RATE_LIMITED) return null
                    if (attempt < POINTS_QUERY_ATTEMPTS - 1) delay(POINTS_QUERY_RETRY_DELAY_MS.milliseconds)
                } catch (e: Exception) {
                    failure = e
                    return null
                }
            }
        }
        failure?.let { onLog("积分查询失败：${it.message}") }
        return null
    }

    private fun labelOf(account: Account): String = account.phone.ifBlank { account.uid }.ifBlank { "账号" }

    private data class MergedMissions(
        val missions: List<MissionInfo>,
        val weekMask: Int,
        val dailyAdId: String,
        val dailyScore: Int,
        val dailyToken: String,
        val validScore: Int?,
        val totalScore: Int?,
    )

    companion object {
        private val FINANCE_KEYWORDS = listOf("免费权益", "借贷", "贷款")

        // 与界面路径保持同样的节奏，否则后台更容易撞上 -98 频控
        private const val MISSION_INTERVAL_MS = 30_000L
        private const val RATE_LIMIT_BACKOFF_MS = 60_000L
        private const val RATE_LIMIT_RETRIES = 2
        private const val POINTS_QUERY_ATTEMPTS = 2
        private const val POINTS_QUERY_RETRY_DELAY_MS = 5_000L
    }
}
