package com.github.ilife798.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 别名集合的持久化结构。 */
@Serializable
private data class DeviceAliasesDto(
    val aliases: Map<String, String> = emptyMap(),
)

private val deviceAliasesJson = Json { ignoreUnknownKeys = true }

/**
 * 解析持久化字符串为别名集合。任何异常都退化为空集合，避免损坏数据让启动崩溃。
 * 解析成功的条目会逐条重新归一化，防止历史脏数据重新进入内存。
 */
fun decodeDeviceAliases(raw: String?): DeviceAliases {
    if (raw.isNullOrBlank()) return DeviceAliases.EMPTY
    val dto =
        try {
            deviceAliasesJson.decodeFromString<DeviceAliasesDto>(raw)
        } catch (_: Exception) {
            return DeviceAliases.EMPTY
        }
    return dto.aliases.entries.fold(DeviceAliases.EMPTY) { acc, (deviceId, text) ->
        acc.withAlias(deviceId, text)
    }
}

/** 序列化别名集合为持久化字符串。 */
fun encodeDeviceAliases(aliases: DeviceAliases): String = deviceAliasesJson.encodeToString(DeviceAliasesDto(aliases.asMap()))
