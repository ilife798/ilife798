package com.github.ilife798.data.model

// 积分任务运行通知的进度快照：第一行积分，第二行当前任务与轮次。
// 多账号批量运行时第一行前面再拼账号名（由调用方传入，见 [bodyLines]）。
data class TaskProgress(
    val gained: Int = 0,
    val missionName: String = "",
    val round: Int = 0,
    val total: Int = 0,
) {
    val pointsLine: String get() = "积分:$gained"

    // 未进入具体任务（刚启动、正在加载列表）时没有第二行
    val missionLine: String
        get() = if (missionName.isBlank() || total <= 0) "" else "$missionName ($round/$total)"

    // 通知正文：账号名非空时拼成「账号 | 积分:12」，否则单账号只显示「积分:12」
    fun bodyLines(accountLabel: String?): List<String> {
        val label = accountLabel?.takeIf { it.isNotBlank() }
        val first = if (label == null) pointsLine else "$label | $pointsLine"
        return listOfNotNull(first, missionLine.takeIf { it.isNotBlank() })
    }
}
