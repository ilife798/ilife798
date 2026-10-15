package com.github.ilife798.data.model

/** 单条设备别名。以设备 ID 为唯一键，同一设备至多一条别名。 */
data class DeviceAlias(
    val deviceId: String,
    val text: String,
)

/** 别名最大长度，按 Unicode 码点计，避免截断代理对（emoji）。 */
const val DEVICE_ALIAS_MAX_LENGTH: Int = 20

/** 归一化别名：去除首尾空白，空白视为无别名，超长按码点截断。 */
fun normalizeDeviceAlias(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val codePoints = trimmed.codePoints().toArray()
    if (codePoints.size <= DEVICE_ALIAS_MAX_LENGTH) return trimmed
    return String(codePoints, 0, DEVICE_ALIAS_MAX_LENGTH)
}

/**
 * 设备别名集合。不可变，键为设备 ID。
 *
 * 别名仅保存在本机（PersistentStorage），不上传服务端；不随账号切换、收藏状态变化，
 * 只由设备 ID 决定归属。别名只影响本机显示，不改变服务端设备名。
 */
data class DeviceAliases(
    private val byDeviceId: Map<String, String> = emptyMap(),
) {
    /** 取指定设备的别名；无别名时返回空串。 */
    fun textOf(deviceId: String): String = byDeviceId[deviceId].orEmpty()

    /** 是否已有别名。 */
    fun hasAlias(deviceId: String): Boolean = !byDeviceId[deviceId].isNullOrEmpty()

    /** 写入别名并返回新集合。归一化后为空则等同于删除该条。 */
    fun withAlias(
        deviceId: String,
        rawText: String,
    ): DeviceAliases {
        if (deviceId.isEmpty()) return this
        val text = normalizeDeviceAlias(rawText)
        if (text.isEmpty()) return withoutAlias(deviceId)
        if (byDeviceId[deviceId] == text) return this
        return DeviceAliases(byDeviceId + (deviceId to text))
    }

    /** 删除指定设备的别名。 */
    fun withoutAlias(deviceId: String): DeviceAliases {
        if (!byDeviceId.containsKey(deviceId)) return this
        return DeviceAliases(byDeviceId - deviceId)
    }

    /** 仅保留给定设备 ID 的别名。 */
    fun retainedOnly(deviceIds: Collection<String>): DeviceAliases {
        val keep = deviceIds.toSet()
        val filtered = byDeviceId.filterKeys { it in keep }
        if (filtered.size == byDeviceId.size) return this
        return DeviceAliases(filtered)
    }

    /** 转为普通映射，供序列化使用。 */
    fun asMap(): Map<String, String> = byDeviceId

    /** 展开为列表，按设备 ID 排序以保证序列化结果稳定。 */
    fun entries(): List<DeviceAlias> =
        byDeviceId.entries
            .sortedBy { it.key }
            .map { DeviceAlias(it.key, it.value) }

    /** 是否有任何别名。 */
    fun isEmpty(): Boolean = byDeviceId.isEmpty()

    companion object {
        val EMPTY = DeviceAliases()
    }
}
