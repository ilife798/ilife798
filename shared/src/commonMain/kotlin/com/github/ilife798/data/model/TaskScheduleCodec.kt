package com.github.ilife798.data.model

import kotlinx.serialization.json.Json

private val taskScheduleJson = Json { ignoreUnknownKeys = true }

/**
 * 解析定时任务配置。数据损坏或缺失时退回「未启用」的默认值，避免启动崩溃。
 */
fun decodeTaskSchedule(raw: String?): TaskSchedule {
    if (raw.isNullOrBlank()) return TaskSchedule()
    return try {
        taskScheduleJson.decodeFromString<TaskSchedule>(raw).normalized
    } catch (_: Exception) {
        TaskSchedule()
    }
}

fun encodeTaskSchedule(schedule: TaskSchedule): String = taskScheduleJson.encodeToString(schedule.normalized)
