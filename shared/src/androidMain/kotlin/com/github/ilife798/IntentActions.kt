package com.github.ilife798

// Intent action 与 extra 常量，供 MainActivity、通知、快捷方式与图块共用。
object IntentActions {
    const val ACTION_SCAN = "com.github.ilife798.action.SCAN"
    const val ACTION_START_DEVICE = "com.github.ilife798.action.START_DEVICE"
    const val ACTION_OPEN_WIDGET_DEVICE = "com.github.ilife798.action.OPEN_WIDGET_DEVICE"
    const val EXTRA_WIDGET_ACCOUNT = "com.github.ilife798.extra.WIDGET_ACCOUNT"
    const val ACTION_RUN_TASKS = "com.github.ilife798.action.RUN_TASKS"
    const val ACTION_SHOW_UPDATE = "com.github.ilife798.action.SHOW_UPDATE"
    const val ACTION_STOP_DEVICE = "com.github.ilife798.action.STOP_DEVICE"
    const val ACTION_OPEN_HOME = "com.github.ilife798.action.OPEN_HOME"
    const val ACTION_OPEN_TASKS = "com.github.ilife798.action.OPEN_TASKS"
    const val ACTION_STOP_TASKS = "com.github.ilife798.action.STOP_TASKS"
    const val EXTRA_DEVICE_ID = "com.github.ilife798.extra.DEVICE_ID"
}
