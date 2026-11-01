package com.github.ilife798

import com.github.ilife798.data.model.Account
import com.github.ilife798.data.model.AccountInfo
import com.github.ilife798.data.model.Device
import com.github.ilife798.data.model.deviceDisplayStatus

data class DeviceWidgetTarget(
    val accountKey: String,
    val deviceId: String,
)

// 小组件仅保存展示快照与账号标识，不复制登录凭据。
data class DeviceWidgetEntry(
    val target: DeviceWidgetTarget,
    val accountLabel: String,
    val name: String,
    val status: String,
)

enum class DeviceWidgetResult {
    REQUESTED,
    UNSUPPORTED,
    FAILED,
}

interface DeviceWidgetController {
    fun sync(
        accountKeys: Set<String>,
        entries: List<DeviceWidgetEntry>,
    )

    fun pin(entry: DeviceWidgetEntry): DeviceWidgetResult
}

object DeviceWidgets {
    var controller: DeviceWidgetController? = null

    val available: Boolean get() = controller != null

    // 手机号在补齐 uid、切换登录渠道时保持稳定；没有手机号的账号才使用 uid。
    fun accountKey(account: Account): String =
        when {
            account.phone.isNotBlank() -> "phone:${account.phone}"
            account.uid.isNotBlank() -> "uid:${account.uid}"
            else -> ""
        }

    fun entry(
        account: Account,
        info: AccountInfo,
        device: Device,
        alias: String = "",
    ): DeviceWidgetEntry =
        DeviceWidgetEntry(
            target = DeviceWidgetTarget(accountKey(account), device.id),
            accountLabel = info.name.ifBlank { account.phone.takeLast(4).let { if (it.isBlank()) "设备账号" else "尾号 $it" } },
            name = alias.ifBlank { device.name.ifBlank { device.id } },
            status = deviceDisplayStatus(device.deviceStatus, device.geneStatus, device.dtype).label,
        )
}
