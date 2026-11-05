package com.github.ilife798

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** 设备卡片使用稳定的远程适配器按需更新，保留桌面中的滑动位置。 */
class DeviceWidgetCollectionService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        DeviceCards(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))

    private class DeviceCards(
        private val context: Context,
        private val widgetId: Int,
    ) : RemoteViewsFactory {
        private var entries: List<StoredDeviceWidget> = emptyList()
        private val store = DeviceWidgetStore(context)

        override fun onCreate() {
            onDataSetChanged()
        }

        override fun onDataSetChanged() {
            entries = store.collection(widgetId)
        }

        override fun onDestroy() {
            entries = emptyList()
        }

        override fun getCount(): Int = entries.size

        override fun getViewAt(position: Int): RemoteViews? =
            entries.getOrNull(position)?.let {
                DeviceWidgetProvider.cardViews(context, widgetId, store.key(it.entry.target), position, entries.size)
            }

        override fun getLoadingView(): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_card).apply {
                setTextViewText(R.id.widget_name, "正在读取设备…")
            }

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long =
            entries.getOrNull(position)?.let { DeviceWidgetProvider.itemId(store.key(it.entry.target)) } ?: -1L

        override fun hasStableIds(): Boolean = true
    }
}
