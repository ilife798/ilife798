package com.github.ilife798

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView

class DeviceWidgetConfigureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(this)
        if (manager.getAppWidgetInfo(widgetId)?.provider != ComponentName(this, DeviceWidgetProvider::class.java)) {
            finish()
            return
        }
        val store = DeviceWidgetStore(this)
        val entries = store.entries().sortedWith(compareBy({ it.entry.accountLabel }, { it.entry.name }))
        val padding = (20 * resources.displayMetrics.density).toInt()
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }
        layout.addView(
            TextView(this).apply {
                setText(R.string.device_widget_choose)
                textSize = 24f
                setPadding(0, padding, 0, padding)
            },
        )
        layout.addView(
            TextView(this).apply {
                setText(if (entries.isEmpty()) R.string.device_widget_empty else R.string.device_widget_choose_hint)
                textSize = 14f
                setPadding(0, 0, 0, padding)
            },
        )
        val list =
            ListView(this).apply {
                adapter =
                    SimpleAdapter(
                        this@DeviceWidgetConfigureActivity,
                        entries.map {
                            mapOf("name" to it.entry.name, "account" to "${it.entry.accountLabel} · ${it.entry.target.deviceId}")
                        },
                        android.R.layout.simple_list_item_2,
                        arrayOf("name", "account"),
                        intArrayOf(android.R.id.text1, android.R.id.text2),
                    )
                setOnItemClickListener { _, _, position, _ ->
                    val key = store.key(entries[position].entry.target)
                    if (store.get(key) != null) {
                        store.bind(widgetId, key)
                        DeviceWidgetProvider.update(this@DeviceWidgetConfigureActivity, widgetId, resetSelection = true)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                        finish()
                    }
                }
            }
        layout.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        layout.addView(
            Button(this).apply {
                setText(R.string.device_widget_open_app)
                setOnClickListener {
                    startActivity(
                        Intent(this@DeviceWidgetConfigureActivity, MainActivity::class.java).setAction(IntentActions.ACTION_OPEN_HOME),
                    )
                    finish()
                }
            },
        )
        setContentView(layout)
        @Suppress("DEPRECATION")
        layout.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars =
                    insets.getInsets(
                        android.view.WindowInsets.Type
                            .systemBars(),
                    )
                view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            } else {
                view.setPadding(
                    padding + insets.systemWindowInsetLeft,
                    padding + insets.systemWindowInsetTop,
                    padding + insets.systemWindowInsetRight,
                    padding + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }
}
