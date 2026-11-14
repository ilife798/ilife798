package com.github.ilife798

import com.github.ilife798.data.model.AccountEntry
import com.github.ilife798.data.model.decodeDeviceAliases
import com.github.ilife798.data.model.decodeDeviceSnapshot
import kotlinx.serialization.json.Json

/**
 * App Intent 下拉里的一项。
 *
 * [key] 是喂给链接的东西（设备编号 / 账号序号），[title] 给人看，[subtitle] 补一句区别信息。
 * 不直接用 Device / AccountEntry 是为了别把整条模型暴露到 Swift：
 * 那边只关心「选哪个」，多给的字段全是负担。
 *
 * 字段名不叫 `id`：Objective-C 里 id 是类型关键字，导出到 Swift 时容易撞名。
 */
data class ShortcutChoice(
    val key: String,
    val title: String,
    val subtitle: String,
)

/**
 * iOS App Intents 的 Kotlin 侧入口。
 *
 * 设计上刻意做得很薄：所有动作最终都拼成一条 `ilife798://…` 交给 [ShortcutRouter]，
 * 与 URL scheme、Android 的 handleIntent 走同一条已测路径。这样 Siri 能拿到原生参数选择，
 * 而动作语义只有一份实现，不会出现「快捷指令能做、URL 做不到」的分叉。
 */
object IosShortcuts {
    private val accountListJson = Json { ignoreUnknownKeys = true }

    /** 交给 Kotlin 侧解析并派发；返回是否识别成功。 */
    fun handle(url: String): Boolean = ShortcutRouter.handleUrl(url)

    /**
     * 可供选择的设备：优先别名，其次设备名，最后退回编号。
     *
     * 数据来自上次快照而非实时接口——Intent 运行时不保证网络已就绪，
     * 拿旧列表让人选一个再让动作自己校验，比下拉空白要好。
     */
    fun devices(): List<ShortcutChoice> {
        val storage = storageOrNull() ?: return emptyList()
        val aliases =
            try {
                decodeDeviceAliases(storage.getString(StorageKeys.DEVICE_ALIASES))
            } catch (_: Exception) {
                null
            }
        return try {
            decodeDeviceSnapshot(storage.getString(StorageKeys.DEVICE_SNAPSHOT)).map { device ->
                val title =
                    aliases
                        ?.textOf(device.id)
                        .orEmpty()
                        .ifEmpty { device.name }
                        .ifEmpty { device.id }
                val subtitle = if (title == device.id) "" else device.id
                ShortcutChoice(key = device.id, title = title, subtitle = subtitle)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 可供切换的账号。id 用列表下标：切换账号的链接本来就接受序号，
     * 而 uid 可能为空（只登录了设备控制通道时），下标总是有效。
     */
    fun accounts(): List<ShortcutChoice> {
        val raw = storageOrNull()?.getString(StorageKeys.ACCOUNT_LIST) ?: return emptyList()
        val list =
            try {
                accountListJson.decodeFromString<List<AccountEntry>>(raw)
            } catch (_: Exception) {
                return emptyList()
            }
        return list.mapIndexedNotNull { index, entry ->
            if (!entry.account.hasAnyToken) return@mapIndexedNotNull null
            val title =
                entry.info.name
                    .ifEmpty { entry.account.phone }
                    .ifEmpty { entry.account.uid }
            if (title.isEmpty()) return@mapIndexedNotNull null
            val subtitle =
                entry.account.phone
                    .takeIf { it.isNotEmpty() && it != title }
                    .orEmpty()
            ShortcutChoice(key = index.toString(), title = title, subtitle = subtitle)
        }
    }

    /**
     * 取持久化实例。
     *
     * Siri 可能在本应用从未启动过的情况下直接执行 Intent，此时
     * [AppStorage.instance] 还没被赋值。iOS 的 PersistentStorage 只包了一层
     * NSUserDefaults、构造无副作用，这里补一次初始化是安全的；
     * 但仍然用 try 兜住，绝不让一次快捷指令调用把进程带崩。
     */
    private fun storageOrNull(): PersistentStorage? =
        try {
            AppStorage.instance
        } catch (_: Exception) {
            try {
                PersistentStorage().also { AppStorage.instance = it }
            } catch (_: Exception) {
                null
            }
        }
}
