package com.github.ilife798

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import com.github.ilife798.data.model.TaskProgress

internal const val RUN_NOTIFICATION_GROUP = "com.github.ilife798.run"
internal const val DEVICE_ID_BASE = 2201
internal const val DEVICE_ID_RANGE = 900

// 前台服务通知的固定 id（镜像当前主运行通知），避开设备 id 段与更新通知 id。
internal const val RUN_NOTIFICATION_ID = 2001

private const val REQUEST_DEVICE_CONTENT = 4101
private const val REQUEST_TASK_CONTENT = 4103
private const val REQUEST_STOP_TASKS = 4104
private const val REQUEST_STOP_DEVICE_BASE = 4200
private const val REQUEST_STOP_DEVICE_RANGE = 500

private const val CRITICAL_TEXT_RUNNING = "运行中"
private const val PROGRESS_COLOR = -12877066

internal fun deviceNotificationId(deviceId: String): Int = DEVICE_ID_BASE + ((deviceId.hashCode() and 0x7FFFFFFF) % DEVICE_ID_RANGE)

internal fun buildDeviceNotification(
    context: Context,
    deviceId: String,
    deviceName: String,
): Notification {
    val label = deviceName.takeIf { it.isNotBlank() } ?: "设备"
    val builder =
        Notification
            .Builder(context, CHANNEL_DEVICE)
            .setSmallIcon(appIconRes(context))
            .setContentTitle("设备运行中")
            .setContentText("$label · 编号 $deviceId")
            .setContentIntent(
                activityPendingIntent(
                    context,
                    REQUEST_DEVICE_CONTENT,
                    IntentActions.ACTION_OPEN_HOME,
                    deviceId = null,
                ),
            ).setCategory(Notification.CATEGORY_PROGRESS)
            .setGroup(RUN_NOTIFICATION_GROUP)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
    builder.addAction(stopDeviceAction(context, deviceId))
    applyRunProgress(builder)
    return builder.build()
}

/**
 * 积分任务运行通知。正文两行：
 * - 单账号：`积分:12` / `任务名称 (1/3)`
 * - 多账号：`账号名 | 积分:12` / `任务名称 (1/3)`
 */
internal fun buildTaskNotification(
    context: Context,
    progress: TaskProgress,
    accountLabel: String?,
): Notification {
    val lines = progress.bodyLines(accountLabel)
    val builder =
        Notification
            .Builder(context, CHANNEL_TASK)
            .setSmallIcon(appIconRes(context))
            .setContentTitle("积分任务运行中")
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(
                activityPendingIntent(
                    context,
                    REQUEST_TASK_CONTENT,
                    IntentActions.ACTION_OPEN_TASKS,
                    deviceId = null,
                ),
            ).setCategory(Notification.CATEGORY_PROGRESS)
            .setGroup(RUN_NOTIFICATION_GROUP)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
    // 停止按钮与任务页的停止按钮等价：都走 ACTION_STOP_TASKS → AppRequests.stopTasks
    builder.addAction(
        stopAction(
            context,
            activityPendingIntent(
                context,
                REQUEST_STOP_TASKS,
                IntentActions.ACTION_STOP_TASKS,
                deviceId = null,
            ),
        ),
    )
    // 这里不用 ProgressStyle：它会整体替换 notification.style，把上面的 BigTextStyle 顶掉，
    // 第二行「任务名 (轮次/总数)」就不再展开。改回普通进度条，两行正文与进度可以并存。
    builder.setProgress(0, 0, true)
    return builder.build()
}

/**
 * 后台定时任务的进度通知：视觉与 [buildTaskNotification] 完全一致，
 * 只是正文由调用方直接给出（后台执行只有账号级粗进度，没有「任务名 (X/X)」）。
 */
fun buildTaskNotification(
    context: Context,
    text: String,
): Notification {
    val builder =
        Notification
            .Builder(context, CHANNEL_TASK)
            .setSmallIcon(appIconRes(context))
            .setContentTitle("积分任务运行中")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(
                activityPendingIntent(
                    context,
                    REQUEST_TASK_CONTENT,
                    IntentActions.ACTION_OPEN_TASKS,
                    deviceId = null,
                ),
            ).setCategory(Notification.CATEGORY_PROGRESS)
            .setGroup(RUN_NOTIFICATION_GROUP)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
    builder.setProgress(0, 0, true)
    return builder.build()
}

/** 后台定时任务的结果汇总：一账号一行，可划掉，点击进任务页。 */
fun buildTaskResultNotification(
    context: Context,
    title: String,
    text: String,
): Notification =
    Notification
        .Builder(context, CHANNEL_TASK)
        .setSmallIcon(appIconRes(context))
        .setContentTitle(title)
        .setContentText(text.lineSequence().firstOrNull().orEmpty())
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(
            activityPendingIntent(
                context,
                REQUEST_TASK_CONTENT,
                IntentActions.ACTION_OPEN_TASKS,
                deviceId = null,
            ),
        ).setAutoCancel(true)
        .build()

/**
 * 保活通知：定时任务开着「后台运行」时一直挂着的常驻通知。
 *
 * 它不显示进度，作用是让前台服务活着——进程有前台服务时系统不会把它冻结或快速回收，
 * 闹钟到点才能立刻执行。走单独的 [CHANNEL_RESIDENT] 通道（低打扰、无振动、无角标），
 * 用户可以只静音它而不影响执行期间的进度通知。
 */
internal fun buildResidentNotification(
    context: Context,
    stopIntent: PendingIntent,
    nextRun: String?,
): Notification {
    val text =
        if (nextRun.isNullOrBlank()) {
            "后台运行已开启，到点会自动执行积分任务"
        } else {
            "后台运行已开启，下次执行：$nextRun"
        }
    return Notification
        .Builder(context, CHANNEL_RESIDENT)
        .setSmallIcon(appIconRes(context))
        .setContentTitle("定时任务后台运行中")
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(
            activityPendingIntent(
                context,
                REQUEST_TASK_CONTENT,
                IntentActions.ACTION_OPEN_TASKS,
                deviceId = null,
            ),
        ).setCategory(Notification.CATEGORY_SERVICE)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setOngoing(true)
        .addAction(stopAction(context, stopIntent))
        .build()
}

private fun stopDeviceAction(
    context: Context,
    deviceId: String,
): Notification.Action {
    val requestCode =
        REQUEST_STOP_DEVICE_BASE +
            ((deviceId.hashCode() and 0x7FFFFFFF) % REQUEST_STOP_DEVICE_RANGE)
    val pendingIntent =
        activityPendingIntent(
            context,
            requestCode,
            IntentActions.ACTION_STOP_DEVICE,
            deviceId,
        )
    return stopAction(context, pendingIntent)
}

private fun stopAction(
    context: Context,
    pendingIntent: PendingIntent,
): Notification.Action =
    Notification.Action
        .Builder(
            Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
            "停止",
            pendingIntent,
        ).build()

private fun applyRunProgress(builder: Notification.Builder) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        applyProgressStyle(builder)
    } else {
        builder.setProgress(0, 0, true)
    }
}

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
private fun applyProgressStyle(builder: Notification.Builder) {
    val style =
        Notification
            .ProgressStyle()
            .setStyledByProgress(false)
            .setProgressIndeterminate(true)
            .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(PROGRESS_COLOR)))
    builder.style = style
    builder.setShortCriticalText(CRITICAL_TEXT_RUNNING)
    if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
        builder.setRequestPromotedOngoing(true)
    }
}

// 通知小图标统一使用应用图标：状态栏只取 alpha 通道，颜色由系统着色。
// 优先取启动图标的矢量前景（androidApp 的 ic_launcher_foreground），
// 拿不到时退回 applicationInfo.icon，保证不会因为资源缺失而崩溃。
private fun appIconRes(context: Context): Int {
    val foreground =
        context.resources.getIdentifier("ic_launcher_foreground", "drawable", context.packageName)
    if (foreground != 0) return foreground
    val icon = context.applicationInfo.icon
    return if (icon != 0) icon else android.R.drawable.stat_notify_sync
}

private fun activityPendingIntent(
    context: Context,
    requestCode: Int,
    action: String,
    deviceId: String?,
): PendingIntent {
    val launchIntent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
    launchIntent.action = action
    launchIntent.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP,
    )
    if (deviceId != null) {
        launchIntent.putExtra(IntentActions.EXTRA_DEVICE_ID, deviceId)
    }
    return PendingIntent.getActivity(
        context,
        requestCode,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
