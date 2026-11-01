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
        val args = entry.startArgs ?: old?.takeIf { it.has("startArgs") }?.optString("startArgs")
        if (args !=
            null
        ) {
            data.put("startArgs", args).put(
                "autoDeduct",
                if (entry.startArgs !=
                    null
                ) {
                    entry.autoDeduct
                } else {
                    old?.optBoolean("autoDeduct", true) ?: true
                },
            )
        }
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
            DeviceWidgetEntry(
                target,
                data.optString("accountLabel"),
                data.optString("name"),
                data.optString("status"),
                data
                    .takeIf {
                        it.has("startArgs")
                    }?.optString("startArgs"),
                data.optBoolean("autoDeduct", true),
            ),
            data.optLong("syncedAt", 0L),
        )
    }

    fun binding(widgetId: Int): String? = prefs.getString("binding_$widgetId", null)

    fun bind(
        widgetId: Int,
        key: String,
        args: String? = get(key)?.entry?.startArgs,
        autoDeduct: Boolean = get(key)?.entry?.autoDeduct ?: true,
    ) {
        prefs
            .edit()
            .putString(
                "binding_$widgetId",
                key,
            ).putString("args_$widgetId", args)
            .putBoolean("deduct_$widgetId", autoDeduct)
            .remove("action_$widgetId")
            .apply()
    }

    fun startArgs(widgetId: Int): String? = prefs.getString("args_$widgetId", null)

    fun autoDeduct(widgetId: Int): Boolean = prefs.getBoolean("deduct_$widgetId", true)

    fun recentlyStarted(deviceId: String): Boolean = System.currentTimeMillis() - prefs.getLong("attempt_$deviceId", 0L) < 60_000

    fun recordStart(deviceId: String) {
        prefs.edit().putLong("attempt_$deviceId", System.currentTimeMillis()).commit()
    }

    fun action(widgetId: Int): JSONObject? =
        runCatching {
            JSONObject(prefs.getString("action_$widgetId", "") ?: "").takeIf { System.currentTimeMillis() - it.optLong("at") < 120_000 }
        }.getOrNull()

    fun setAction(
        widgetId: Int,
        message: String,
        busy: Boolean,
    ) {
        prefs
            .edit()
            .putString(
                "action_$widgetId",
                JSONObject()
                    .put("message", message)
                    .put("busy", busy)
                    .put("at", System.currentTimeMillis())
                    .toString(),
            ).apply()
    }

    fun remove(widgetIds: IntArray) {
        prefs.edit().apply {
            widgetIds.forEach {
                remove("binding_$it")
                remove("args_$it")
                remove("deduct_$it")
                remove("action_$it")
            }
            apply()
        }
    }
}
