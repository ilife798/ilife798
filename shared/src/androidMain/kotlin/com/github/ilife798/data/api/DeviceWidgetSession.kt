package com.github.ilife798.data.api

import android.content.Context
import com.github.ilife798.DeviceWidgets
import com.github.ilife798.PersistentStorage
import com.github.ilife798.StorageKeys
import com.github.ilife798.data.model.Account
import com.github.ilife798.data.model.AccountEntry
import kotlinx.serialization.json.Json

/** 独立拥有网络客户端；启动请求关闭连接层自动重试。 */
class DeviceWidgetSession {
    // 与 App 中的长连接隔离，避免桌面操作拿到已经被服务端关闭的空闲连接。
    private val pool = okhttp3.ConnectionPool()
    private val client = createAndroidHttpClient(allowConnectionRetry = false, pool = pool)
    val api = IlifeApi(client)

    fun close() {
        client.close()
        pool.evictAll()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun account(
            context: Context,
            key: String,
        ): Account? {
            val text = PersistentStorage(context).getString(StorageKeys.ACCOUNT_LIST).orEmpty()
            val accounts = runCatching { json.decodeFromString<List<AccountEntry>>(text) }.getOrDefault(emptyList())
            return accounts.firstOrNull { DeviceWidgets.accountKey(it.account) == key }?.account?.takeIf { it.hasAnyToken }
        }
    }
}
