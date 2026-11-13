package com.github.ilife798

import com.github.ilife798.data.model.TaskProgress
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

// iOS 运行通知：UNUserNotificationCenter 单通知槽。
// 设备状态 → id "ilife798.device"，积分任务 → id "ilife798.task"；add(request) 同 id 覆盖刷新。
// 权限在首次实际需要展示前请求（[.alert, .sound]），拒绝/失败时静默跳过投递，
// 任务执行不受影响（spec ios-notifications「未授权不影响功能」）。
actual object RunNotifications {
    private const val DEVICE_NOTIFICATION_ID = "ilife798.device"
    private const val TASK_NOTIFICATION_ID = "ilife798.task"

    actual fun updateDevice(
        deviceId: String,
        deviceName: String,
        running: Boolean,
    ) {
        if (deviceId.isBlank()) return
        if (running) {
            post(DEVICE_NOTIFICATION_ID, "设备 $deviceName 运行中")
        } else {
            remove(DEVICE_NOTIFICATION_ID)
        }
    }

    // 当前运行账号的显示名：非空时拼在积分行前（对齐 Android 多账号运行）
    private var taskAccountLabel: String? = null

    actual fun updateTaskAccount(label: String?) {
        taskAccountLabel = label?.takeIf { it.isNotBlank() }
    }

    actual fun updateTask(progress: TaskProgress) {
        val lines = progress.bodyLines(taskAccountLabel)
        post(TASK_NOTIFICATION_ID, lines.joinToString("\n"))
    }

    actual fun removeTask() {
        taskAccountLabel = null
        remove(TASK_NOTIFICATION_ID)
    }

    // 已首次响铃过的通知 id：后续同 id 刷新静默（对齐 Android setOnlyAlertOnce）。
    private val alertedIds = mutableSetOf<String>()

    // 授权就绪才投递：未决定时系统弹窗、已授权时立即回调；清除类操作（remove）无需权限。
    private fun post(
        id: String,
        body: String,
    ) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
        ) { granted, _ ->
            if (granted) {
                val firstAlert = alertedIds.add(id) // 竞态代价仅为偶发一次重复响铃，可接受
                val content =
                    UNMutableNotificationContent().apply {
                        setBody(body)
                        if (firstAlert) {
                            setSound(UNNotificationSound.defaultSound())
                        }
                    }
                val request =
                    UNNotificationRequest.requestWithIdentifier(
                        identifier = id,
                        content = content,
                        trigger = null,
                    )
                center.addNotificationRequest(request) { _ ->
                    // 同 id 覆盖刷新；投递错误静默
                }
            }
        }
    }

    private fun remove(id: String) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(id))
    }
}
