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
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.util.UUID

private const val ACTION_WIDGET_PINNED = "com.github.ilife798.action.WIDGET_PINNED"
private const val EXTRA_WIDGET_KEY = "com.github.ilife798.extra.WIDGET_KEY"
private const val EXTRA_START_ARGS = "com.github.ilife798.extra.WIDGET_START_ARGS"
private const val EXTRA_AUTO_DEDUCT = "com.github.ilife798.extra.WIDGET_AUTO_DEDUCT"
internal const val EXTRA_WIDGET_TARGET = "com.github.ilife798.extra.WIDGET_TARGET"

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
                        .putExtra(EXTRA_START_ARGS, entry.startArgs)
                        .putExtra(EXTRA_AUTO_DEDUCT, entry.autoDeduct)
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
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        update(context, appWidgetId)
    }

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
        store.bind(id, key, intent.getStringExtra(EXTRA_START_ARGS), intent.getBooleanExtra(EXTRA_AUTO_DEDUCT, true))
        update(context, id, resetSelection = true)
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

        @Suppress("DEPRECATION")
        fun update(
            context: Context,
            widgetId: Int,
            resetSelection: Boolean = false,
        ) {
            // 新版系统会异步绑定集合服务，使用应用上下文避免随短期启动服务销毁而解绑。
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val views = views(appContext, widgetId)
            if (resetSelection) views.setDisplayedChild(R.id.widget_stack, 0)
            manager.updateAppWidget(widgetId, views)
            run {
                manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_stack)
            }
        }

        @Suppress("DEPRECATION")
        internal fun views(
            context: Context,
            widgetId: Int,
            previewKey: String? = null,
        ): RemoteViews {
            val store = DeviceWidgetStore(context)
            if (previewKey != null) return cardViews(context, widgetId, previewKey, 0, 1)
            val (width, height) = widgetSize(context, widgetId)
            val density = context.resources.displayMetrics.density
            val entries = store.collection(widgetId)
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val start =
                Intent(context, DeviceWidgetStartService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .setData(Uri.parse("ilife798-widget://deck-start/$widgetId"))
            val configure =
                Intent(context, DeviceWidgetConfigureActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .setData(Uri.parse("ilife798-widget://configure/$widgetId"))
            val settings =
                PendingIntent.getActivity(
                    context,
                    widgetId,
                    configure,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val selection = store.activeSelection(widgetId)
            if (selection != null) return selectionViews(context, widgetId, selection.first, selection.second, settings)
            val focus = store.consumeFocus(widgetId)
            return RemoteViews(context.packageName, R.layout.widget_device_deck).apply {
                // 抵消 StackView 固定预留的 10% 透视空间，让当前卡片铺满裁剪窗口。
                // 内部高亮边框每侧为 4dp；右侧再多留一侧，避免后卡从边缘露出。
                setViewPadding(
                    R.id.widget_stack,
                    0,
                    -(((height - 44) * 0.1f + 4) * density).toInt(),
                    -(((width - 20) * 0.1f + 8) * density).toInt(),
                    0,
                )
                val adapter =
                    Intent(context, DeviceWidgetCollectionService::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        .setData(Uri.parse("ilife798-widget://deck-data/$widgetId"))
                setRemoteAdapter(R.id.widget_stack, adapter)
                setEmptyView(R.id.widget_stack, R.id.widget_empty)
                setPendingIntentTemplate(
                    R.id.widget_stack,
                    PendingIntent.getForegroundService(
                        context,
                        widgetId,
                        start,
                        PendingIntent.FLAG_UPDATE_CURRENT or mutable,
                    ),
                )
                setOnClickPendingIntent(R.id.widget_settings, settings)
                setOnClickPendingIntent(R.id.widget_empty, settings)
                if (focus != null) {
                    val index = entries.indexOfFirst { store.key(it.entry.target) == focus }
                    if (index >= 0) setDisplayedChild(R.id.widget_stack, index)
                }
                setTextViewText(
                    R.id.widget_empty,
                    context.getString(
                        if (store.binding(widgetId) ==
                            null
                        ) {
                            R.string.device_widget_choose
                        } else {
                            R.string.device_widget_logged_out
                        },
                    ),
                )
            }
        }

        // StackView 将整张卡片作为一次点击分发；多选项放在集合之外，每项使用独立且不可变的 PendingIntent。
        private fun selectionViews(
            context: Context,
            widgetId: Int,
            key: String,
            selection: JSONObject,
            settings: PendingIntent,
        ): RemoteViews {
            fun click(
                action: String,
                value: Int? = null,
            ): PendingIntent {
                val intent =
                    Intent(context, DeviceWidgetStartService::class.java)
                        .setData(Uri.parse("ilife798-widget://select/$widgetId/${Uri.encode(key)}/${selection.getString("id")}/$action"))
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        .putExtra(EXTRA_WIDGET_TARGET, key)
                        .putExtra(EXTRA_WIDGET_SELECTION, selection.getString("id"))
                if (value != null) intent.putExtra(EXTRA_WIDGET_CHOICE, value)
                if (action == "more") intent.putExtra(EXTRA_WIDGET_MORE, true)
                if (action == "cancel") intent.putExtra(EXTRA_WIDGET_CANCEL, true)
                return PendingIntent.getForegroundService(
                    context,
                    widgetId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            return RemoteViews(context.packageName, R.layout.widget_device_selection).apply {
                setOnClickPendingIntent(R.id.widget_settings, settings)
                setViewVisibility(R.id.widget_summary, View.GONE)
                setViewVisibility(R.id.widget_choices_panel, View.VISIBLE)
                setTextViewText(R.id.widget_choices_title, selection.getString("title"))
                setTextViewText(R.id.widget_updated, "完成选择后启动 · 记住本次选择")
                setTextViewText(R.id.widget_position, "")
                removeAllViews(R.id.widget_choices)
                val choices = selection.getJSONArray("choices")
                val offset = selection.optInt("page") * 3
                for (index in offset until minOf(offset + 3, choices.length())) {
                    val option = choices.getJSONObject(index)
                    val value = option.getInt("value")
                    addView(
                        R.id.widget_choices,
                        RemoteViews(context.packageName, R.layout.widget_choice).apply {
                            setTextViewText(R.id.widget_choice, option.getString("label"))
                            setOnClickPendingIntent(R.id.widget_choice, click("value-$value", value))
                        },
                    )
                }
                setViewVisibility(R.id.widget_choices_next, if (choices.length() > 3) View.VISIBLE else View.GONE)
                setOnClickPendingIntent(R.id.widget_choices_next, click("more"))
                setOnClickPendingIntent(R.id.widget_choices_cancel, click("cancel"))
            }
        }

        internal fun itemId(key: String): Long = key.fold(1125899906842597L) { hash, char -> hash * 31 + char.code }

        private fun widgetSize(
            context: Context,
            widgetId: Int,
        ): Pair<Int, Int> {
            val options =
                if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Bundle()
                } else {
                    AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
                }
            val portrait = context.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val width =
                options.getInt(
                    if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
                    300,
                )
            val height =
                options.getInt(
                    if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                    180,
                )
            return width to height
        }

        internal fun cardViews(
            context: Context,
            widgetId: Int,
            key: String,
            position: Int,
            count: Int,
        ): RemoteViews {
            val store = DeviceWidgetStore(context)
            val snapshot = store.get(key)
            val entry = snapshot?.entry
            val action = if (entry != null) store.action(widgetId, key) else null
            val busy = action?.optBoolean("busy") == true
            val (width, height) = widgetSize(context, widgetId)
            val cardHeight = (height - 48).coerceAtLeast(72)
            val compact = cardHeight < 130
            val status = action?.optString("message") ?: entry?.status ?: context.getString(R.string.device_widget_logged_out)
            val warning = listOf("失败", "异常", "超时", "离线", "禁用", "失效", "不可用", "退出", "无法", "请").any { it in status }
            val color = context.getColor(if (warning) R.color.widget_warning else R.color.widget_accent)

            fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
            return RemoteViews(context.packageName, R.layout.widget_card).apply {
                setTextViewText(R.id.widget_account, entry?.accountLabel ?: context.getString(R.string.device_widget_label))
                setTextViewText(R.id.widget_name, entry?.name ?: context.getString(R.string.device_widget_choose))
                setTextViewText(R.id.widget_position, "${position + 1} / $count")
                setTextViewText(R.id.widget_status, status)
                setContentDescription(R.id.widget_status, status)
                setTextColor(R.id.widget_status, color)
                setTextColor(R.id.widget_status_dot, color)
                setTextViewText(
                    R.id.widget_updated,
                    action?.let { context.getString(R.string.device_widget_operated, DateFormat.format("HH:mm", it.optLong("at"))) }
                        ?: snapshot?.let { context.getString(R.string.device_widget_synced, DateFormat.format("HH:mm", it.syncedAt)) }
                        ?: context.getString(R.string.device_widget_configure_hint),
                )
                setTextViewText(
                    R.id.widget_open,
                    if (busy) {
                        "处理中"
                    } else if (compact) {
                        "启动"
                    } else {
                        context.getString(R.string.device_widget_open)
                    },
                )
                setTextViewTextSize(R.id.widget_name, android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 16f else 20f)
                setTextViewTextSize(R.id.widget_account, android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 9f else 10f)
                setInt(R.id.widget_name, "setMaxLines", if (compact) 1 else 2)
                setInt(R.id.widget_status, "setMaxLines", if (compact) 1 else 2)
                setViewVisibility(R.id.widget_updated, View.VISIBLE)
                setViewVisibility(R.id.widget_progress, if (busy) View.VISIBLE else View.GONE)
                val padding = dp(if (compact) 6 else 14)
                setViewPadding(R.id.widget_card_root, padding, padding, padding, padding)
                // 子卡片由 AT_MOST 限制到窗口大小；较大的最小尺寸确保短文本也铺满，
                // 防止框架扩大后卡间距，也保证当前不透明背景遮住后卡的文字与按钮。
                setInt(R.id.widget_card_root, "setMinimumWidth", dp(width))
                setInt(R.id.widget_card_root, "setMinimumHeight", dp(height))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val size = if (compact) 44f else 64f
                    setViewLayoutWidth(R.id.widget_open, size, android.util.TypedValue.COMPLEX_UNIT_DIP)
                    setViewLayoutHeight(R.id.widget_open, size, android.util.TypedValue.COMPLEX_UNIT_DIP)
                }
                setViewPadding(R.id.widget_open, dp(3), dp(if (compact) 2 else 10), dp(3), dp(if (compact) 2 else 8))
                setContentDescription(
                    R.id.widget_open,
                    if (busy) "正在启动 ${entry?.name.orEmpty()}" else "使用${entry?.accountLabel.orEmpty()}启动${entry?.name.orEmpty()}",
                )
                if (entry != null) setOnClickFillInIntent(R.id.widget_open, Intent().putExtra(EXTRA_WIDGET_TARGET, key))
            }
        }
    }
}
