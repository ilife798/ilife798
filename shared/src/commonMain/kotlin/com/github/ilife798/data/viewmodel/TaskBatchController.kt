package com.github.ilife798.data.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.ilife798.RunNotifications
import com.github.ilife798.data.model.AccountEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 多个账号依次执行积分任务。
 *
 * 任务页的「运行选中账号」与定时任务共用这一份实现，因此到点触发与手动按下按钮
 * 走的是同一条路径：按 uid 锚定账号身份、逐个切号、等本轮结束再切下一个、跑完切回原账号。
 * 通知里的账号前缀也由这里维护——单账号运行不带前缀，批量运行带上当前账号名。
 */
class TaskBatchController(
    private val scope: CoroutineScope,
    private val accounts: () -> List<AccountEntry>,
    private val currentUid: () -> String,
    private val currentIndex: () -> Int,
    private val switchAccount: (Int) -> Unit,
    private val runTasks: () -> Boolean,
    private val isLoading: () -> Boolean,
    private val refreshAccountAssets: (Int) -> Unit,
) {
    var batching by mutableStateOf(false)
        private set

    private var batchJob: Job? = null

    // 账号下标在批量运行期间可能因增删账号而偏移，改用 uid 按账号顺序锚定身份，
    // 每轮执行前再按 uid 反查当前下标，避免切错账号。
    fun indexOfUid(uid: String): Int =
        if (uid.isEmpty()) {
            -1
        } else {
            accounts().indexOfFirst { it.account.uid == uid }
        }

    fun displayName(entry: AccountEntry): String = entry.info.name.ifBlank { entry.account.phone.ifBlank { entry.account.uid } }

    /** 传入要执行的账号 uid；为空表示执行全部已登录账号（定时任务用）。 */
    fun start(uids: List<String>? = null) {
        if (batching) return
        val targets =
            if (uids.isNullOrEmpty()) {
                accounts().filter { it.account.hasAnyToken }.map { it.account.uid }.filter { it.isNotEmpty() }
            } else {
                uids
            }
        if (targets.isEmpty()) return
        batching = true
        batchJob =
            scope.launch {
                val originUid = currentUid()
                val originIndex = currentIndex()
                // 无论正常结束、异常还是被取消，都要把 batching 复位并切回原账号，
                // 否则按钮会永久卡在「运行中，点击停止」再也点不动
                try {
                    for (uid in targets) {
                        val index = indexOfUid(uid)
                        val entry = accounts().getOrNull(index) ?: continue
                        if (!entry.account.hasAnyToken) continue
                        if (index != currentIndex()) {
                            switchAccount(index)
                            // 等切号后的数据重载完成再请求，避免用旧 token 发起任务
                            delay(SWITCH_DELAY_MS.milliseconds)
                        }
                        // 批量运行时通知正文带账号前缀，用户能看出当前跑到哪个账号
                        RunNotifications.updateTaskAccount(displayName(entry))
                        // runTasks 返回 false 表示这一轮根本没启动（缺少登录态等），
                        // 此时不能再等 isLoading：它不会自己变 true，白等满 MAX_WAIT_ROUNDS
                        if (!runTasks()) {
                            val refreshIndex = indexOfUid(uid)
                            if (refreshIndex >= 0) refreshAccountAssets(refreshIndex)
                            continue
                        }
                        // 等本轮结束：isLoading 由执行器驱动，最多等 10 分钟
                        var waited = 0
                        while (isLoading() && waited < MAX_WAIT_ROUNDS) {
                            delay(500.milliseconds)
                            waited++
                        }
                        // 跑完一个账号就单独刷新它的积分，账号行立刻反映到账结果。
                        // 只刷这一个账号，避免一次对全站发起大量 mission-lst 撞 -98。
                        // 稍等再拉：让服务端的积分流水先落库。
                        delay(POST_RUN_DELAY_MS.milliseconds)
                        val refreshIndex = indexOfUid(uid)
                        if (refreshIndex >= 0) refreshAccountAssets(refreshIndex)
                    }
                } finally {
                    // 同样按 uid 找回原账号，它若被删除则退回原下标，仍不存在就保持不动
                    val back = indexOfUid(originUid).takeIf { it >= 0 } ?: originIndex
                    if (back in accounts().indices) switchAccount(back)
                    // 批量结束后回到单账号语义：通知不再显示账号前缀
                    RunNotifications.updateTaskAccount(null)
                    batching = false
                    batchJob = null
                }
            }
    }

    /** 整批停止；当前轮次的 [TaskController] 停止由调用方负责。 */
    fun stop() {
        batchJob?.cancel()
        batchJob = null
        batching = false
        RunNotifications.updateTaskAccount(null)
    }

    companion object {
        // 切号后等待账号数据重载的时长
        private const val SWITCH_DELAY_MS = 1_500L

        // 跑完一个账号后，等积分流水落库再刷新该账号资产
        private const val POST_RUN_DELAY_MS = 1_000L

        // 单账号最长等待 10 分钟（500ms × 1200）
        private const val MAX_WAIT_ROUNDS = 1_200
    }
}
