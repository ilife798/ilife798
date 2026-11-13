package com.github.ilife798

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal const val CHANNEL_DEVICE = "device_running"
internal const val CHANNEL_TASK = "task_running"

/**
 * 定时任务「后台运行」的保活通道。
 *
 * 与 [CHANNEL_TASK] 分开的理由：进度通知是执行期间才有的、跑完就该消失，用户可能想单独关掉它；
 * 保活通知是「这个功能开着就一直挂在那」的状态提示，两者的打扰预期不同，混在一个通道里
 * 用户没法只静音其中一个。默认低打扰，不出声、不弹横幅。
 */
internal const val CHANNEL_RESIDENT = "scheduled_resident"

internal fun ensureRunChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_DEVICE) == null) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DEVICE,
                "设备运行状态",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "设备运行期间显示运行状态与停止操作"
                setShowBadge(false)
            },
        )
    }
    if (manager.getNotificationChannel(CHANNEL_TASK) == null) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TASK,
                "积分任务运行状态",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "积分任务运行期间显示已获得积分与停止操作"
                setShowBadge(false)
            },
        )
    }
    if (manager.getNotificationChannel(CHANNEL_RESIDENT) == null) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESIDENT,
                "定时任务后台常驻",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "开启定时任务的后台运行后常驻显示，关闭后自动消失"
                setShowBadge(false)
                enableVibration(false)
            },
        )
    }
}

internal fun canPostNotification(
    context: Context,
    channelId: String,
): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    if (!manager.areNotificationsEnabled()) return false
    val channel = manager.getNotificationChannel(channelId) ?: return false
    return channel.importance != NotificationManager.IMPORTANCE_NONE
}

internal fun cancelNotification(id: Int) {
    runCatching {
        ApplicationContext.instance.getSystemService(NotificationManager::class.java)?.cancel(id)
    }
}
