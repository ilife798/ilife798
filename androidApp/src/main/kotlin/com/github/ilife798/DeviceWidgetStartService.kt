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
import com.github.ilife798.data.model.deviceDisplayStatus
import com.github.ilife798.data.viewmodel.buildDeviceDetailStartArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** 桌面点击直接执行；不创建 Activity，也不依赖当前打开的账号或 ViewModel。 */
class DeviceWidgetStartService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = mutableMapOf<String, MutableSet<Int>>()

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
        val key = store.binding(id).orEmpty()
        val entry = store.get(key)?.entry
        if (entry == null) {
            if (active.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        store.setAction(id, "启动中…", true)
        DeviceWidgetProvider.update(this, id)
        // 按物理设备去重：同一设备的多个组件、多个账号也不会重复发送启动请求。
        active[entry.target.deviceId]?.let {
            it.add(id)
            return START_NOT_STICKY
        }
        active[entry.target.deviceId] = mutableSetOf(id)
        scope.launch {
            var message = "操作中断，请确认设备状态"
            try {
                message = withTimeout(110_000) { withContext(Dispatchers.IO) { start(id, key, entry) } }
            } catch (_: TimeoutCancellationException) {
                message = "请求超时，请确认设备状态后再操作"
            } catch (e: WidgetStartFailure) {
                message = e.message ?: "启动失败"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 网络中断时启动可能已经生效；绝不自动重试这类请求。
                message = "网络异常，请确认设备状态后再操作"
            } finally {
                active.remove(entry.target.deviceId).orEmpty().forEach { widgetId ->
                    val bound = store.binding(widgetId)?.let { store.get(it)?.entry }
                    if (bound?.target?.deviceId == entry.target.deviceId) {
                        store.setAction(widgetId, message, false)
                        DeviceWidgetProvider.update(this@DeviceWidgetStartService, widgetId)
                    }
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
    ): String {
        if (ApiConfig.baseUrl.isBlank()) ApiConfig.init(BuildConfig.API_GATEWAY, BuildConfig.SIGN_SALT, BuildConfig.API_CID)
        val store = DeviceWidgetStore(this)
        val auth = account(entry.target.accountKey)

        fun checkBinding() {
            if (store.binding(id) != key || AppWidgetManager.getInstance(this).getAppWidgetInfo(id) == null) fail("小组件配置已变化，请重新操作")
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
            val configured = store.startArgs(id)
            if (configured == null && (detail.parts.isNotEmpty() || detail.subs.isNotEmpty() || detail.goods.isNotEmpty())) {
                fail("请在设备详情选择模式或通道后重新添加组件")
            }
            val args =
                configured
                    ?: buildDeviceDetailStartArgs(
                        detail.goods,
                        null,
                        -1,
                        detail.dtype in setOf(20, 120),
                        detail.dtype == 10 && detail.gtype != 6,
                    )
            val options = if (args.isBlank()) JSONObject() else JSONObject(args)
            if (options.has("pos") && detail.subs.getOrNull(options.optInt("pos", -1))?.available != true) fail("所选通道不可用，请重新选择")
            if (options.has("mode") && !options.isNull("mode") && options.optInt("mode", -1) >= 0 &&
                detail.parts.none { it.mode == options.optInt("mode") }
            ) {
                fail("设备模式已变化，请重新选择")
            }
            val status =
                api.getDevStatus(auth.preferredToken, entry.target.deviceId, appType)
                    ?: fail(if (expired) "登录已失效，请重新登录" else "无法确认设备状态，未发送启动请求")
            saveStatus(key, deviceDisplayStatus(status.deviceStatus, status.geneStatus, detail.dtype).label)
            if (status.deviceStatus == 0) fail("设备离线，暂时无法启动")
            if (status.geneStatus == 98) fail("设备已禁用，无法启动")
            if (status.geneStatus != 99) return "设备正在使用，未重复启动"
            if (store.recentlyStarted(entry.target.deviceId)) return "刚提交过启动，请确认设备状态后再操作"
            checkBinding()
            val score = api.setUseScore(auth.preferredToken, if (store.autoDeduct(id)) 1 else 0, appType)
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
            repeat(5) {
                delay(1_000)
                val fresh = runCatching { api.getDevStatus(auth.preferredToken, entry.target.deviceId, appType) }.getOrNull()
                if (fresh != null && fresh.deviceStatus != 0 &&
                    fresh.geneStatus !in setOf(98, 99)
                ) {
                    saveStatus(key, deviceDisplayStatus(fresh.deviceStatus, fresh.geneStatus, detail.dtype).label)
                    return "启动成功 · ${deviceDisplayStatus(fresh.deviceStatus, fresh.geneStatus, detail.dtype).label}"
                }
            }
            return "启动请求已提交，请确认设备状态"
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
