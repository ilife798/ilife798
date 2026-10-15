package com.github.ilife798.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 备注集合的持久化结构。用 Map 保证设备 ID 与备注一一对应。 */
@Serializable
private data class DeviceNotesDto(
    val notes: Map<String, String> = emptyMap(),
)

private val deviceNotesJson = Json { ignoreUnknownKeys = true }

/**
 * 解析持久化字符串为备注集合。
 *
 * 任何异常（空串、格式错误、字段类型不符）都退化为空集合，避免损坏的数据
 * 让启动流程崩溃。解析成功的条目会逐条重新归一化，防止历史脏数据（超长、
 * 纯空白）重新进入内存。
 */
fun decodeDeviceNotes(raw: String?): DeviceNotes {
    if (raw.isNullOrBlank()) return DeviceNotes.EMPTY
    val dto =
        try {
            deviceNotesJson.decodeFromString<DeviceNotesDto>(raw)
        } catch (_: Exception) {
            return DeviceNotes.EMPTY
        }
    return dto.notes.entries.fold(DeviceNotes.EMPTY) { acc, (deviceId, text) ->
        acc.withNote(deviceId, text)
    }
}

/** 序列化备注集合为持久化字符串。 */
fun encodeDeviceNotes(notes: DeviceNotes): String = deviceNotesJson.encodeToString(DeviceNotesDto(notes.asMap()))
