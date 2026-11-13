package com.github.ilife798.data.model

import kotlinx.serialization.Serializable

/**
 * 积分任务的定时执行计划。
 *
 * 只描述「什么时候跑」，不描述「跑哪些账号」：到点后统一执行全部已登录账号，
 * 与任务页的批量执行保持一致。
 */
@Serializable
data class TaskSchedule(
    val enabled: Boolean = false,
    /** 触发小时，0-23。 */
    val hour: Int = DEFAULT_HOUR,
    /** 触发分钟，0-59。 */
    val minute: Int = 0,
    /** 生效的星期，1=周一..7=周日；为空表示每天。 */
    val weekdays: List<Int> = emptyList(),
) {
    /** 归一化后的计划：越界的时间与星期一律收敛到合法范围，避免脏数据排不出闹钟。 */
    val normalized: TaskSchedule
        get() =
            copy(
                hour = hour.coerceIn(0, 23),
                minute = minute.coerceIn(0, 59),
                weekdays = weekdays.filter { it in 1..7 }.distinct().sorted(),
            )

    val timeText: String
        get() = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

    val weekdayText: String
        get() = if (weekdays.isEmpty()) "每天" else weekdays.joinToString("、") { WEEKDAY_NAMES[it - 1] }

    fun isActiveOn(dayOfWeek: Int): Boolean = weekdays.isEmpty() || dayOfWeek in weekdays

    companion object {
        const val DEFAULT_HOUR = 6

        val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}
