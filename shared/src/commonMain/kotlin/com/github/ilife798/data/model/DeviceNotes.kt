package com.github.ilife798.data.model

/** 单条设备备注。以设备 ID 为唯一键，同一设备至多一条备注。 */
data class DeviceNote(
    val deviceId: String,
    val text: String,
)

/** 备注最大长度，按 Unicode 码点计，避免截断代理对（emoji）。 */
const val DEVICE_NOTE_MAX_LENGTH: Int = 50

/**
 * 归一化备注文本：去除首尾空白，空白视为无备注，超长按码点截断。
 * 截断用码点而非 UTF-16 单元，保证 emoji 等增补平面字符不被拦腰截断。
 */
fun normalizeDeviceNote(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val codePoints = trimmed.codePoints().toArray()
    if (codePoints.size <= DEVICE_NOTE_MAX_LENGTH) return trimmed
    return String(codePoints, 0, DEVICE_NOTE_MAX_LENGTH)
}

/**
 * 设备备注集合。不可变，键为设备 ID。
 *
 * 备注仅保存在本机（PersistentStorage），不上传服务端；不随账号切换、收藏状态变化，
 * 只由设备 ID 决定归属。设备被移除时其备注仍保留，避免重加设备后备注丢失。
 */
data class DeviceNotes(
    private val byDeviceId: Map<String, String> = emptyMap(),
) {
    /** 取指定设备的备注；无备注时返回空串。 */
    fun textOf(deviceId: String): String = byDeviceId[deviceId].orEmpty()

    /** 是否已有备注。 */
    fun hasNote(deviceId: String): Boolean = !byDeviceId[deviceId].isNullOrEmpty()

    /**
     * 写入备注并返回新集合。文本经归一化后为空则等同于删除该条，
     * 保证集合内不残留空备注。
     */
    fun withNote(
        deviceId: String,
        rawText: String,
    ): DeviceNotes {
        if (deviceId.isEmpty()) return this
        val text = normalizeDeviceNote(rawText)
        if (text.isEmpty()) return withoutNote(deviceId)
        if (byDeviceId[deviceId] == text) return this
        return DeviceNotes(byDeviceId + (deviceId to text))
    }

    /** 删除指定设备的备注。 */
    fun withoutNote(deviceId: String): DeviceNotes {
        if (!byDeviceId.containsKey(deviceId)) return this
        return DeviceNotes(byDeviceId - deviceId)
    }

    /**
     * 仅保留给定设备 ID 的备注。用于按账号设备列表清理孤儿备注。
     * 传入空集合时返回空集合。
     */
    fun retainedOnly(deviceIds: Collection<String>): DeviceNotes {
        val keep = deviceIds.toSet()
        val filtered = byDeviceId.filterKeys { it in keep }
        if (filtered.size == byDeviceId.size) return this
        return DeviceNotes(filtered)
    }

    /** 转为普通映射，供序列化使用。 */
    fun asMap(): Map<String, String> = byDeviceId

    /** 展开为列表，按设备 ID 排序以保证序列化结果稳定。 */
    fun entries(): List<DeviceNote> =
        byDeviceId.entries
            .sortedBy { it.key }
            .map { DeviceNote(it.key, it.value) }

    /** 是否有任何备注。 */
    fun isEmpty(): Boolean = byDeviceId.isEmpty()

    companion object {
        val EMPTY = DeviceNotes()
    }
}
