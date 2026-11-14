package com.github.ilife798.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 收藏设备的最后一次快照。
 *
 * 存在的理由只有一个：iOS 的 App Intents 要在没有界面的上下文里枚举设备。
 * 设备列表本身是服务端数据、不落盘，而 Siri 唤起时网络还没回来，
 * 因此这里缓存一份「上次看到的收藏」。界面永远不读它——界面有自己的实时列表，
 * 读到旧快照反而会让用户以为设备少了。
 */
@Serializable
private data class DeviceSnapshotDto(
    val devices: List<Device> = emptyList(),
)

private val deviceSnapshotJson = Json { ignoreUnknownKeys = true }

fun encodeDeviceSnapshot(devices: List<Device>): String = deviceSnapshotJson.encodeToString(DeviceSnapshotDto(devices))

/** 解析快照。任何异常都退化为空列表，损坏数据不该让快捷指令崩掉。 */
fun decodeDeviceSnapshot(raw: String?): List<Device> {
    if (raw.isNullOrBlank()) return emptyList()
    val dto =
        try {
            deviceSnapshotJson.decodeFromString<DeviceSnapshotDto>(raw)
        } catch (_: Exception) {
            return emptyList()
        }
    return dto.devices.filter { it.id.isNotEmpty() }
}
