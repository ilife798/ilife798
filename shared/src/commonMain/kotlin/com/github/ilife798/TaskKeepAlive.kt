package com.github.ilife798

import com.github.ilife798.util.setTaskKeepAlive

/**
 * 任务执行期间的进程保活。
 *
 * 为什么要有这一层：iOS 上积分任务要连续跑几分钟到十几分钟，全部是网络请求，
 * 没有正在播放的媒体、没有定位、没有 VoIP——退到后台后系统随时会挂起进程，
 * 请求停在半路，用户看到的是「任务卡住」。静音音频走 playback 通道能让进程活着，
 * 但它是**按需**的：任务跑完必须立刻停，否则就是无谓地占着音频会话与电量。
 *
 * 两条执行路径（单账号 [com.github.ilife798.data.viewmodel.TaskController] 与
 * 批量 [com.github.ilife798.data.viewmodel.TaskBatchController]）会各自申请与释放，
 * 批量内部每跑一个账号都要重新申请一次，所以这里用计数器而不是布尔：
 * 只有最后一个持有者释放时才真正关掉平台通道，账号之间的间隙不会把保活打断。
 */
object TaskKeepAlive {
    private var activeCount = 0

    /** 申请保活；首个持有者才真正开启平台通道。 */
    fun begin() {
        activeCount++
        if (activeCount == 1) setTaskKeepAlive(enabled = true)
    }

    /** 释放保活；最后一个持有者才真正关闭。未持有时的调用被忽略，不会把计数减成负数。 */
    fun end() {
        if (activeCount == 0) return
        activeCount--
        if (activeCount == 0) setTaskKeepAlive(enabled = false)
    }
}
