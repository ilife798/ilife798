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

        /** 已登录且持有 token 的账号；后台任务（桌面组件、定时任务）都按这份列表执行。 */
        fun accounts(context: Context): List<Account> = entries(context).map { it.account }.filter { it.hasAnyToken }

        /**
         * 同上，但连同缓存资料一起返回。
         *
         * 后台通知要显示账号昵称，而昵称只在 [AccountEntry.info] 里（[Account] 本身没有昵称字段），
         * 所以定时任务这条链路必须拿整条条目。
         */
        fun accountEntries(context: Context): List<AccountEntry> = entries(context).filter { it.account.hasAnyToken }

        fun account(
            context: Context,
            key: String,
        ): Account? = entries(context).firstOrNull { DeviceWidgets.accountKey(it.account) == key }?.account?.takeIf { it.hasAnyToken }

        private fun entries(context: Context): List<AccountEntry> {
            val text = PersistentStorage(context).getString(StorageKeys.ACCOUNT_LIST).orEmpty()
            return runCatching { json.decodeFromString<List<AccountEntry>>(text) }.getOrDefault(emptyList())
        }
    }
}
