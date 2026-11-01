package com.github.ilife798

import android.content.Context
import android.net.Uri
import org.json.JSONObject

internal data class StoredDeviceWidget(
    val entry: DeviceWidgetEntry,
    val syncedAt: Long,
)

internal class DeviceWidgetStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("device_widgets", Context.MODE_PRIVATE)

    fun key(target: DeviceWidgetTarget): String = "${Uri.encode(target.accountKey)}/${Uri.encode(target.deviceId)}"

    private fun snapshots(): JSONObject = runCatching { JSONObject(prefs.getString("entries", "{}") ?: "{}") }.getOrElse { JSONObject() }

    fun sync(
        accountKeys: Set<String>,
        entries: List<DeviceWidgetEntry>,
    ): Boolean {
        val snapshots = snapshots()
        val before = snapshots.toString()
        snapshots.keys().asSequence().toList().forEach { key ->
            if (snapshots.optJSONObject(key)?.optString("accountKey") !in accountKeys) snapshots.remove(key)
        }
        entries.filter { it.target.accountKey in accountKeys }.forEach { entry -> put(snapshots, entry) }
        if (before == snapshots.toString() && prefs.getStringSet("accounts", emptySet()) == accountKeys) return false
        prefs
            .edit()
            .putString("entries", snapshots.toString())
            .putStringSet("accounts", accountKeys.toSet())
            .apply()
        return true
    }

    fun save(entry: DeviceWidgetEntry) {
        val snapshots = snapshots()
        put(snapshots, entry)
        val accounts = prefs.getStringSet("accounts", emptySet()).orEmpty().toSet() + entry.target.accountKey
        prefs
            .edit()
            .putString("entries", snapshots.toString())
            .putStringSet("accounts", accounts)
            .apply()
    }

    private fun put(
        snapshots: JSONObject,
        entry: DeviceWidgetEntry,
    ) {
        val key = key(entry.target)
        val old = snapshots.optJSONObject(key)
        val data =
            JSONObject()
                .put("accountKey", entry.target.accountKey)
                .put("deviceId", entry.target.deviceId)
                .put("accountLabel", entry.accountLabel)
                .put("name", entry.name)
                .put("status", entry.status)
        val unchanged = data.keys().asSequence().all { field -> old?.optString(field) == data.optString(field) }
        data.put("syncedAt", if (unchanged) old?.optLong("syncedAt", 0L) ?: 0L else System.currentTimeMillis())
        snapshots.put(key, data)
    }

    fun entries(): List<StoredDeviceWidget> =
        snapshots().let { data ->
            data
                .keys()
                .asSequence()
                .mapNotNull { read(data.optJSONObject(it)) }
                .toList()
        }

    fun get(key: String): StoredDeviceWidget? = read(snapshots().optJSONObject(key))

    private fun read(data: JSONObject?): StoredDeviceWidget? {
        if (data == null) return null
        val target = DeviceWidgetTarget(data.optString("accountKey"), data.optString("deviceId"))
        if (target.accountKey.isBlank() || target.deviceId.isBlank()) return null
        return StoredDeviceWidget(
            DeviceWidgetEntry(target, data.optString("accountLabel"), data.optString("name"), data.optString("status")),
            data.optLong("syncedAt", 0L),
        )
    }

    fun binding(widgetId: Int): String? = prefs.getString("binding_$widgetId", null)

    fun bind(
        widgetId: Int,
        key: String,
    ) {
        prefs.edit().putString("binding_$widgetId", key).apply()
    }

    fun remove(widgetIds: IntArray) {
        prefs.edit().apply {
            widgetIds.forEach { remove("binding_$it") }
            apply()
        }
    }
}
