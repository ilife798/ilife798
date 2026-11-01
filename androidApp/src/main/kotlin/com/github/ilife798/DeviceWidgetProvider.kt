package com.github.ilife798

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.RemoteViews
import java.util.UUID

private const val ACTION_WIDGET_PINNED = "com.github.ilife798.action.WIDGET_PINNED"
private const val EXTRA_WIDGET_KEY = "com.github.ilife798.extra.WIDGET_KEY"

class AndroidDeviceWidgetController(
    private val context: Context,
) : DeviceWidgetController {
    override fun sync(
        accountKeys: Set<String>,
        entries: List<DeviceWidgetEntry>,
    ) {
        if (DeviceWidgetStore(context).sync(accountKeys, entries)) DeviceWidgetProvider.updateAll(context)
    }

    override fun pin(entry: DeviceWidgetEntry): DeviceWidgetResult =
        try {
            val manager = AppWidgetManager.getInstance(context)
            val store = DeviceWidgetStore(context)
            // 不支持应用内添加的桌面仍可从系统小组件列表选择此设备。
            store.save(entry)
            if (!manager.isRequestPinAppWidgetSupported) {
                DeviceWidgetResult.UNSUPPORTED
            } else {
                val callback =
                    Intent(context, DeviceWidgetProvider::class.java)
                        .setAction(ACTION_WIDGET_PINNED)
                        .setData(Uri.parse("ilife798-widget://pin/${UUID.randomUUID()}"))
                        .putExtra(EXTRA_WIDGET_KEY, store.key(entry.target))
                // 系统需要写入成功添加后的 appWidgetId；必须使用显式、一次性的可变回调。
                val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val success = PendingIntent.getBroadcast(context, 0, callback, PendingIntent.FLAG_ONE_SHOT or mutableFlag)
                val preview =
                    Bundle().apply {
                        putParcelable(
                            AppWidgetManager.EXTRA_APPWIDGET_PREVIEW,
                            DeviceWidgetProvider.views(context, AppWidgetManager.INVALID_APPWIDGET_ID, store.key(entry.target)),
                        )
                    }
                if (manager.requestPinAppWidget(ComponentName(context, DeviceWidgetProvider::class.java), preview, success)) {
                    DeviceWidgetResult.REQUESTED
                } else {
                    success.cancel()
                    DeviceWidgetResult.UNSUPPORTED
                }
            }
        } catch (_: Exception) {
            DeviceWidgetResult.FAILED
        }
}

class DeviceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id -> update(context, id) }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_WIDGET_PINNED) return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val key = intent.getStringExtra(EXTRA_WIDGET_KEY) ?: return
        val manager = AppWidgetManager.getInstance(context)
        if (manager.getAppWidgetInfo(id)?.provider != ComponentName(context, DeviceWidgetProvider::class.java)) return
        val store = DeviceWidgetStore(context)
        // 用户确认系统弹窗之前可能已经退出对应账号。
        if (store.get(key) == null) return
        store.bind(id, key)
        update(context, id)
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        DeviceWidgetStore(context).remove(appWidgetIds)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, DeviceWidgetProvider::class.java)).forEach { update(context, it) }
        }

        fun update(
            context: Context,
            widgetId: Int,
        ) {
            AppWidgetManager.getInstance(context).updateAppWidget(widgetId, views(context, widgetId))
        }

        internal fun views(
            context: Context,
            widgetId: Int,
            previewKey: String? = null,
        ): RemoteViews {
            val store = DeviceWidgetStore(context)
            val key = previewKey ?: store.binding(widgetId)
            val snapshot = key?.let { store.get(it) }
            val entry = snapshot?.entry
            return RemoteViews(context.packageName, R.layout.widget_device).apply {
                setTextViewText(R.id.widget_account, entry?.accountLabel ?: context.getString(R.string.device_widget_label))
                setTextViewText(R.id.widget_name, entry?.name ?: context.getString(R.string.device_widget_choose))
                setTextViewText(
                    R.id.widget_status,
                    entry?.status ?: context.getString(
                        if (key == null) R.string.device_widget_not_configured else R.string.device_widget_logged_out,
                    ),
                )
                setTextViewText(
                    R.id.widget_updated,
                    snapshot?.let { context.getString(R.string.device_widget_synced, DateFormat.format("MM-dd HH:mm", it.syncedAt)) }
                        ?: context.getString(R.string.device_widget_configure_hint),
                )
                setTextViewText(
                    R.id.widget_open,
                    context.getString(if (entry == null) R.string.device_widget_choose_action else R.string.device_widget_open),
                )
                val click =
                    if (entry != null) {
                        Intent(context, MainActivity::class.java)
                            .setAction(IntentActions.ACTION_OPEN_WIDGET_DEVICE)
                            .putExtra(IntentActions.EXTRA_DEVICE_ID, entry.target.deviceId)
                            .putExtra(IntentActions.EXTRA_WIDGET_ACCOUNT, entry.target.accountKey)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    } else {
                        Intent(context, DeviceWidgetConfigureActivity::class.java)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    }
                click.data = Uri.parse("ilife798-widget://open/$widgetId")
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(context, widgetId, click, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
            }
        }
    }
}
