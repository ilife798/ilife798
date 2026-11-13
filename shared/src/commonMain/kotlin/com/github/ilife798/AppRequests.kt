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

    // 值为是否后台执行（bg）：false=跳转任务页并运行，true=后台执行不跳转
    val runTasks = UiRequest<Boolean?>(null)

    // NFC 标签携带的设备链接（与二维码同源，复用扫码添加流程）
    val tagContent = UiRequest<String?>(null)

    // 运行状态通知；DeviceActionRequest.bg 为 true 时后台执行不跳转
    val stopDevice = UiRequest<DeviceActionRequest?>(null)

    // scheme devices/{id}/run 启动设备
    val runDevice = UiRequest<DeviceActionRequest?>(null)
    val openHome = UiCommand()
    val openTasks = UiCommand()
    val stopTasks = UiRequest<Boolean?>(null)

    // 定时任务闹钟：等价于按下任务页的批量运行按钮，全部已登录账号依次执行
    val runScheduledTasks = UiCommand()

    // 更新通知
    val showUpdate = UiCommand()

    // 快捷指令 / 外部链接（ilife798://…）触发的页面跳转与操作
    val openScore = UiCommand()
    val openBill = UiCommand()
    val openCards = UiCommand()
    val cardScan = UiCommand()

    // 「添加设备」；value 非空时预填设备编号
    val deviceAdd = UiRequest<String?>(null)
    val openAccount = UiCommand()

    // 账号相关的三条新增入口：只打开页面 / 预填手机号 / 预填凭据
    val accountAdd = UiCommand()
    val accountAddPhone = UiRequest<String?>(null)
    val accountAddToken = UiRequest<AccountTokenPrefill?>(null)

    // 「切换账号」；值为序号或 uid，由界面层解析成下标
    val switchAccount = UiRequest<String?>(null)
    val openMe = UiCommand()
    val openBackup = UiCommand()
    val openSchedule = UiCommand()
    val refresh = UiCommand()

    // 快捷指令「依次运行账号」。用一个非空包装类型而不是 List<String>?，
    // 否则「全部账号」与「没有请求」在 UiRequest 上都表现为 null，无法区分。
    val runBatch = UiRequest<BatchRunRequest?>(null)

    // 「依次运行全部已登录账号」，与任务页的批量按钮同一条路径；值为是否后台执行
    val runAllTasks = UiRequest<Boolean?>(null)
}

// 批量运行请求；[uids] 为 null 表示全部已登录账号；[bg] 为 true 时后台执行不跳转。
class BatchRunRequest(
    val uids: List<String>?,
    val bg: Boolean = false,
)

// 账户凭据预填；两个槽位都可能为空，由页面自行校验。[bg] 为 true 时静默导入不跳转。
class AccountTokenPrefill(
    val appToken: String,
    val token: String,
    val bg: Boolean = false,
)

// 设备启停请求；[bg] 为 true 时后台执行不跳转。
class DeviceActionRequest(
    val id: String,
    val bg: Boolean = false,
)
