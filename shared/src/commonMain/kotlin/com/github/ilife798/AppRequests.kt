package com.github.ilife798

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// 一次性 UI 请求：request 写入，UI 观察 [value] 变化，consume 复位。
class UiRequest<T>(
    private val initial: T,
) {
    var value: T by mutableStateOf(initial)
        private set

    fun request(newValue: T) {
        value = newValue
    }

    fun consume() {
        value = initial
    }
}

// 自增型请求：每次 request 递增，保证 LaunchedEffect 能观察到新请求。
class UiCommand {
    var id by mutableIntStateOf(0)
        private set

    fun request() {
        id++
    }

    fun consume() {
        id = 0
    }
}

// 平台 Intent 与桌面快捷方式触发的导航/操作请求。
object AppRequests {
    // 桌面快捷方式
    val scan = UiCommand()
    val startDevice = UiRequest<String?>(null)
    val openWidgetDevice = UiRequest<DeviceWidgetTarget?>(null)
    val runTasks = UiCommand()

    // NFC 标签携带的设备链接（与二维码同源，复用扫码添加流程）
    val tagContent = UiRequest<String?>(null)

    // 运行状态通知
    val stopDevice = UiRequest<String?>(null)
    val openHome = UiCommand()
    val openTasks = UiCommand()
    val stopTasks = UiCommand()

    // 更新通知
    val showUpdate = UiCommand()
}
