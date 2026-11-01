package com.github.ilife798

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import com.github.ilife798.data.api.APP_TYPE_DEVICE
import com.github.ilife798.data.api.APP_TYPE_POINTS
import com.github.ilife798.data.api.ApiConfig
import com.github.ilife798.data.api.DeviceWidgetSession
import com.github.ilife798.data.model.Account
import com.github.ilife798.data.model.DeviceDisplayStatus
import com.github.ilife798.data.model.deviceDisplayStatus
import com.github.ilife798.data.viewmodel.buildDeviceDetailStartArgs
import com.github.ilife798.data.viewmodel.isDeviceChannelRequired
import com.github.ilife798.util.formatMoney
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val EXTRA_WIDGET_CHOICE = "com.github.ilife798.extra.WIDGET_CHOICE"
internal const val EXTRA_WIDGET_SELECTION = "com.github.ilife798.extra.WIDGET_SELECTION"
internal const val EXTRA_WIDGET_MORE = "com.github.ilife798.extra.WIDGET_MORE"
internal const val EXTRA_WIDGET_CANCEL = "com.github.ilife798.extra.WIDGET_CANCEL"

/** 桌面点击直接执行；不创建 Activity，也不依赖当前打开的账号或 ViewModel。 */
class DeviceWidgetStartService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = mutableMapOf<String, Pair<Int, String>>()
    private val accountLocks = mutableMapOf<String, Mutex>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "桌面设备操作", NotificationManager.IMPORTANCE_LOW))
        startForeground(
            NOTIFICATION_ID,
            Notification
                .Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile_device)
                .setContentTitle("正在启动桌面设备")
                .setContentText("结果将显示在桌面小组件上")
                .setOngoing(true)
                .build(),
        )
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        val store = DeviceWidgetStore(this)
        val key = intent?.getStringExtra(EXTRA_WIDGET_TARGET) ?: store.binding(id).orEmpty()
        val entry = store.get(key)?.entry
        if (entry == null || store.collection(id).none { store.key(it.entry.target) == key }) {
            if (active.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        val selectionId = intent?.getStringExtra(EXTRA_WIDGET_SELECTION)
        if (intent?.getBooleanExtra(EXTRA_WIDGET_CANCEL, false) == true) {
            val pending = store.selection(id, key)
            if (pending == null || pending.optString("id") == selectionId) {
                store.setSelection(id, key, null)
                store.setAction(id, "已取消选择，未启动设备", false, key)
                DeviceWidgetProvider.update(this, id)
            }
            if (active.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_WIDGET_MORE, false) == true) {
            store.selection(id, key)?.takeIf { it.optString("id") == selectionId }?.let {
                val pages = (it.getJSONArray("choices").length() + 2) / 3
                it.put("page", (it.optInt("page") + 1) % pages.coerceAtLeast(1))
                store.setSelection(id, key, it)
            }
            DeviceWidgetProvider.update(this, id)
            if (active.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        val choice = if (intent?.hasExtra(EXTRA_WIDGET_CHOICE) == true) intent.getIntExtra(EXTRA_WIDGET_CHOICE, -1) else null
        // 按物理设备去重：同一设备的多个组件、多个账号也不会重复发送启动请求。
        active[entry.target.deviceId]?.let {
            if (it != (id to key)) {
                store.setAction(id, "该设备正在处理，请稍后查看", false, key)
                DeviceWidgetProvider.update(this, id)
            }
            return START_NOT_STICKY
        }
        active[entry.target.deviceId] = id to key
        store.setAction(id, "启动中…", true, key)
        DeviceWidgetProvider.update(this, id)
        val accountLock = accountLocks.getOrPut(entry.target.accountKey) { Mutex() }
        scope.launch {
            var message = "操作中断，请确认设备状态"
            try {
                message =
                    withTimeout(
                        110_000,
                    ) { accountLock.withLock { withContext(Dispatchers.IO) { start(id, key, entry, selectionId, choice) } } }
            } catch (_: TimeoutCancellationException) {
                message = "请求超时，请确认设备状态后再操作"
            } catch (e: WidgetStartFailure) {
                message = e.message ?: "启动失败"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 只记录异常类型与代码位置，避免把请求参数和账户凭据写入日志。
                android.util.Log.w("DeviceWidget", "Start failed: ${e.javaClass.simpleName} at ${e.stackTrace.firstOrNull()}")
                // 网络中断时启动可能已经生效；绝不自动重试这类请求。
                message = "网络异常，请确认设备状态后再操作"
            } finally {
                active.remove(entry.target.deviceId)
                if (store.collection(id).any { store.key(it.entry.target) == key }) {
                    store.setAction(id, message, false, key)
                    DeviceWidgetProvider.update(this@DeviceWidgetStartService, id)
                }
                Toast.makeText(this@DeviceWidgetStartService, message, Toast.LENGTH_LONG).show()
                if (active.isEmpty()) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun account(key: String): Account = DeviceWidgetSession.account(this, key) ?: fail("账号已退出，请先登录后使用")

    private suspend fun start(
        id: Int,
        key: String,
        entry: DeviceWidgetEntry,
        selectionId: String?,
        choice: Int?,
    ): String {
        if (ApiConfig.baseUrl.isBlank()) {
            ApiConfig.init(
                BuildConfig.API_GATEWAY,
                BuildConfig.SIGN_SALT,
                BuildConfig.API_CID,
                BuildConfig.APP_SIGN_SALT,
                BuildConfig.MINI_SIGN_SALT,
            )
        }
        val store = DeviceWidgetStore(this)
        val auth = account(entry.target.accountKey)

        fun checkBinding() {
            if (store.collection(id).none { store.key(it.entry.target) == key } ||
                AppWidgetManager.getInstance(this).getAppWidgetInfo(id) == null
            ) {
                fail("小组件配置已变化，请重新操作")
            }
            if (account(entry.target.accountKey).preferredToken != auth.preferredToken) fail("登录状态已变化，请重新操作")
        }
        val appType = if (auth.appToken.isNotBlank()) APP_TYPE_DEVICE else APP_TYPE_POINTS
        val session = DeviceWidgetSession()
        try {
            val api = session.api
            var expired = false
            api.onSessionExpired = { expired = true }
            val detail =
                api.getDeviceDetail(auth.preferredToken, entry.target.deviceId, appType)
                    ?: fail(if (expired) "登录已失效，请重新登录" else "无法读取设备信息，请稍后再试")
            val status =
                api.getDevStatus(auth.preferredToken, entry.target.deviceId, appType)
                    ?: fail(if (expired) "登录已失效，请重新登录" else "无法确认设备状态，未发送启动请求")
            saveStatus(key, deviceDisplayStatus(status.deviceStatus, status.geneStatus, detail.dtype).label)
            if (status.deviceStatus == 0) fail("设备离线，暂时无法启动")
            if (status.geneStatus == 98) fail("设备已禁用，无法启动")
            if (status.geneStatus != 99) return "设备正在使用，未重复启动"
            if (store.recentlyStarted(entry.target.deviceId)) return "刚提交过启动，请确认设备状态后再操作"
            checkBinding()
            val pending = store.selection(id, key)
            val configured = store.startArgs(id, key).orEmpty()
            val options =
                if (choice != null) {
                    if (pending == null || pending.optString("id") != selectionId) fail("选项已过期，请重新点击启动")
                    val values = pending.getJSONArray("choices")
                    if ((0 until values.length()).none { values.getJSONObject(it).getInt("value") == choice }) fail("选项已变化，请重新点击启动")
                    JSONObject(pending.getJSONObject("args").toString()).put(pending.getString("kind"), choice)
                } else if (configured.isBlank()) {
                    JSONObject()
                } else {
                    JSONObject(configured)
                }
            store.setSelection(id, key, null)

            fun select(
                kind: String,
                title: String,
                values: List<Pair<Int, String>>,
            ): String {
                if (values.isEmpty()) fail("没有可用${if (kind == "mode") "模式" else "通道"}，未启动设备")
                val choices = JSONArray()
                values.forEach { (value, label) -> choices.put(JSONObject().put("value", value).put("label", label)) }
                store.setSelection(
                    id,
                    key,
                    JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("at", System.currentTimeMillis())
                        .put("kind", kind)
                        .put("title", title)
                        .put("choices", choices)
                        .put("args", options)
                        .put("page", 0),
                )
                return title
            }
            val passage = detail.dtype in setOf(20, 120)
            val modeRequired = if (passage) 13 in detail.sensors && detail.parts.isNotEmpty() else detail.parts.any { it.rate > 0.0 }
            val channelRequired = isDeviceChannelRequired(detail.dtype, detail.subs.size, detail.sensors, detail.fmv)
            if (detail.parts.isNotEmpty() && detail.parts.none { it.rate > 0.0 }) fail("未设置费率信息，无法启动")
            var mode = if (options.has("mode") && !options.isNull("mode")) options.getInt("mode").takeIf { it >= 0 } else null
            val staleMode = mode != null && detail.parts.none { it.mode == mode && it.rate > 0.0 }
            if (staleMode) {
                mode = null
                options.remove("mode")
            }
            if (modeRequired && mode == null) {
                val modes = detail.parts.filter { it.mode >= 0 && it.rate > 0.0 }.distinctBy { it.mode }
                mode = modes.singleOrNull()?.takeUnless { staleMode }?.mode
                if (mode == null) {
                    return select(
                        "mode",
                        "${entry.name} · 请选择模式",
                        modes.map {
                            it.mode to
                                "${it.name.ifBlank { "模式 ${it.mode}" }} · ¥${formatMoney(it.rate)}"
                        },
                    )
                }
                options.put("mode", mode)
            }
            var pos = options.optInt("pos", -1)
            val staleChannel = pos >= 0 && detail.subs.getOrNull(pos)?.available != true
            if (staleChannel) {
                pos = -1
                options.remove("pos")
            }
            if (channelRequired && pos < 0) {
                val selected = detail.subs.withIndex().filter { it.value.isSelect && it.value.available }
                pos =
                    if (!staleChannel && selected.size == 1) {
                        selected.single().index
                    } else if (!staleChannel && detail.subs.size == 1 && detail.subs[0].available) {
                        0
                    } else {
                        -1
                    }
                if (pos < 0) {
                    return select(
                        "pos",
                        "${entry.name} · 请选择通道",
                        detail.subs.withIndex().filter { it.value.available }.map {
                            it.index to
                                "通道 ${it.index + 1}"
                        },
                    )
                }
            }
            val args = buildDeviceDetailStartArgs(detail.goods, mode, pos, passage, detail.dtype == 10 && detail.gtype != 6)
            store.saveStartArgs(id, key, args)
            val score = api.setUseScore(auth.preferredToken, if (store.autoDeduct(id, key)) 1 else 0, appType)
            if (!score.success) fail(if (expired) "登录已失效，请重新登录" else score.message.ifBlank { "积分抵扣设置失败，未启动设备" })
            checkBinding()
            store.recordStart(entry.target.deviceId)
            var result = api.devStart(auth.preferredToken, entry.target.deviceId, appType, ptype = 91, args = args, reportError = false)
            if (!result.success && !expired && result.code != Int.MIN_VALUE) {
                val beforeFallback =
                    api.getDevStatus(auth.preferredToken, entry.target.deviceId, appType)
                        ?: fail("无法确认设备状态，未尝试其他支付方式")
                if (beforeFallback.deviceStatus == 0) fail("设备离线，未尝试其他支付方式")
                if (beforeFallback.geneStatus != 99) return "设备状态已变化，请确认是否正在使用"
                checkBinding()
                result = api.devStart(auth.preferredToken, entry.target.deviceId, appType, ptype = 21, args = args, reportError = false)
            }
            if (!result.success) fail(if (expired) "登录已失效，请重新登录" else result.message.ifBlank { "设备启动失败" })
            // 请求成功与机器实际运行分开显示，状态查询失败也不重新发启动请求。
            var starting = false
            repeat(5) {
                delay(1_000)
                val fresh = runCatching { api.getDevStatus(auth.preferredToken, entry.target.deviceId, appType) }.getOrNull()
                if (fresh != null && fresh.deviceStatus != 0 &&
                    fresh.geneStatus !in setOf(98, 99)
                ) {
                    val display = deviceDisplayStatus(fresh.deviceStatus, fresh.geneStatus, detail.dtype)
                    saveStatus(key, display.label)
                    if (display == DeviceDisplayStatus.Running) return "启动成功 · ${display.label}"
                    starting = display == DeviceDisplayStatus.Starting
                }
            }
            return if (starting) "启动请求已提交 · 启动中" else "启动请求已提交，请确认设备状态"
        } finally {
            session.close()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun saveStatus(
        key: String,
        status: String,
    ) = withContext(Dispatchers.Main.immediate) {
        val store = DeviceWidgetStore(this@DeviceWidgetStartService)
        store.get(key)?.entry?.let { store.save(it.copy(status = status)) }
    }

    private fun fail(message: String): Nothing = throw WidgetStartFailure(message)

    private class WidgetStartFailure(
        message: String,
    ) : Exception(message)

    companion object {
        private const val CHANNEL = "device_widget_actions"
        private const val NOTIFICATION_ID = 7984
    }
}
