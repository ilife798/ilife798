package com.github.ilife798

import com.github.ilife798.data.model.TaskProgress

// 运行状态通知：设备运行、积分任务运行（含实时进度与停止入口）
expect object RunNotifications {
    fun updateDevice(
        deviceId: String,
        deviceName: String,
        running: Boolean,
    )

    // 当前运行账号的显示名：多账号批量运行时显示在积分行前，null 表示单账号运行
    fun updateTaskAccount(label: String?)

    fun updateTask(progress: TaskProgress)

    fun removeTask()
}
