package com.github.ilife798.data.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.ilife798.AppStorage
import com.github.ilife798.RunNotifications
import com.github.ilife798.StorageKeys
import com.github.ilife798.data.api.APP_TYPE_DEVICE
import com.github.ilife798.data.api.APP_TYPE_POINTS
import com.github.ilife798.data.api.DevStatusResult
import com.github.ilife798.data.api.IlifeApi
import com.github.ilife798.data.model.AppState
import com.github.ilife798.data.model.Device
import com.github.ilife798.data.model.DeviceAliases
import com.github.ilife798.data.model.DeviceDetailInfo
import com.github.ilife798.data.model.DeviceNotes
import com.github.ilife798.data.model.HomeDeviceType
import com.github.ilife798.data.model.decodeDeviceAliases
import com.github.ilife798.data.model.decodeDeviceNotes
import com.github.ilife798.data.model.encodeDeviceAliases
import com.github.ilife798.data.model.encodeDeviceNotes
import com.github.ilife798.logDebug
import com.github.ilife798.update.requestNotificationPermission
import com.github.ilife798.util.DeviceLinkParser
import com.github.ilife798.util.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val PAY_TYPE_WALLET = 91
private const val PAY_TYPE_ALIPAY = 21

// 运行中设备的状态轮询间隔（秒），用于感知机器端手动停止
private const val DEVICE_MONITOR_INTERVAL_SECONDS = 5L

// 设备列表、启停与扫码解析。
class DeviceController(
    private val scope: CoroutineScope,
    private val apiProvider: () -> IlifeApi,
    private val getState: () -> AppState,
    private val setState: ((AppState) -> AppState) -> Unit,
    private val onToast: (String) -> Unit,
    private val onLogError: (String, Exception) -> Unit,
    private val onSessionExpired: () -> Unit,
) {
    private val api: IlifeApi get() = apiProvider()

    // 选择的设备类型，默认取已收藏设备的类型（按 洗烘/吹风/饮水/淋浴 顺序取最上），
    // 用户手动选择后持久化，下次进入或重启沿用
    var homeDeviceType by mutableStateOf(HomeDeviceType.Shower)
        private set
    private var homeDeviceTypeExplicit = false

    // 开启自动抵扣（acc/upt useScore）
    var autoDeduct by mutableStateOf(true)
        private set

    // 扫码解析出的设备编号，供导航跳转到对应设备详情页
    var scannedDeviceId by mutableStateOf<String?>(null)
        private set

    // 当前打开的设备详情（模式/通道/商家等，ui/app/dev/home/1?apply=6 数据）
    var deviceDetail by mutableStateOf<DeviceDetailInfo?>(null)
        private set

    // 仅供控制器内部记录详情归属，无 UI 观察者，普通字段即可
    private var detailDeviceId: String? = null

    var pollingDeviceId by mutableStateOf<String?>(null)
        private set

    // 启动请求进行中，详情页主按钮显示“启动中”，状态确认后恢复
    var startingDeviceId by mutableStateOf<String?>(null)
        private set

    private val runningDevices = mutableMapOf<String, String>()
    private var deviceMonitorJob: Job? = null
    private var lastDeviceLoadTime = 0L

    private fun currentToken(): String = getState().account.preferredToken

    // 设备控制鉴权：优先 appToken（设备通道），缺失时回退积分 token
    private data class DeviceAuth(
        val token: String,
        val appType: String,
    )

    private fun deviceAuth(): DeviceAuth {
        val useApp = getState().account.appToken.isNotEmpty()
        return DeviceAuth(
            token = if (useApp) getState().account.appToken else getState().account.token,
            appType = if (useApp) APP_TYPE_DEVICE else APP_TYPE_POINTS,
        )
    }

    // 设备自定义备注，以设备 ID 为唯一键，仅存本机、不上传
    var deviceNotes by mutableStateOf(DeviceNotes.EMPTY)
        private set

    // 设备别名，以设备 ID 为唯一键，仅存本机、不上传；只影响本机显示
    var deviceAliases by mutableStateOf(DeviceAliases.EMPTY)
        private set

    private fun isCurrentToken(token: String): Boolean {
        val account = getState().account
        return token.isNotEmpty() && (account.token == token || account.appToken == token)
    }

    fun loadHomeDeviceTypePref() {
        try {
            val saved = AppStorage.instance.getString(StorageKeys.HOME_DEVICE_TYPE)
            val type = HomeDeviceType.entries.firstOrNull { it.name == saved } ?: return
            homeDeviceType = type
            homeDeviceTypeExplicit = true
        } catch (e: Exception) {
            onLogError("loadHomeDeviceTypePref", e)
        }
    }

    fun selectHomeDeviceType(type: HomeDeviceType) {
        if (homeDeviceType == type && homeDeviceTypeExplicit) return
        homeDeviceType = type
        homeDeviceTypeExplicit = true
        try {
            AppStorage.instance.saveString(StorageKeys.HOME_DEVICE_TYPE, type.name)
        } catch (e: Exception) {
            onLogError("selectHomeDeviceType", e)
        }
    }

    fun loadDeviceNotesPref() {
        try {
            val saved = AppStorage.instance.getString(StorageKeys.DEVICE_NOTES)
            deviceNotes = decodeDeviceNotes(saved)
        } catch (e: Exception) {
            onLogError("loadDeviceNotesPref", e)
        }
    }

    /** 取指定设备的备注，无备注时返回空串。 */
    fun deviceNote(deviceId: String): String = deviceNotes.textOf(deviceId)

    /**
     * 写入指定设备的备注。文本归一化后为空即删除该条备注。
     * 返回 true 表示状态已变更（调用方可用于提示），false 表示无变化或设备 ID 为空。
     */
    fun updateDeviceNote(
        deviceId: String,
        text: String,
    ): Boolean {
        if (deviceId.isEmpty()) return false
        val updated = deviceNotes.withNote(deviceId, text)
        if (updated === deviceNotes) return false
        deviceNotes = updated
        persistDeviceNotes()
        return true
    }

    /** 批量删除备注，用于设备被移除或注销时清理。 */
    fun dropDeviceNotes(deviceIds: Collection<String>) {
        val updated = deviceIds.fold(deviceNotes) { acc, id -> acc.withoutNote(id) }
        if (updated === deviceNotes) return
        deviceNotes = updated
        persistDeviceNotes()
    }

    private fun persistDeviceNotes() {
        try {
            AppStorage.instance.saveString(StorageKeys.DEVICE_NOTES, encodeDeviceNotes(deviceNotes))
        } catch (e: Exception) {
            onLogError("persistDeviceNotes", e)
        }
    }

    fun loadDeviceAliasesPref() {
        try {
            val saved = AppStorage.instance.getString(StorageKeys.DEVICE_ALIASES)
            deviceAliases = decodeDeviceAliases(saved)
        } catch (e: Exception) {
            onLogError("loadDeviceAliasesPref", e)
        }
    }

    /** 取指定设备的别名，无别名时返回空串。 */
    fun deviceAlias(deviceId: String): String = deviceAliases.textOf(deviceId)

    /**
     * 写入指定设备的别名。文本归一化后为空即删除该条别名。
     * 返回 true 表示状态已变更，false 表示无变化或设备 ID 为空。
     */
    fun updateDeviceAlias(
        deviceId: String,
        text: String,
    ): Boolean {
        if (deviceId.isEmpty()) return false
        val updated = deviceAliases.withAlias(deviceId, text)
        if (updated === deviceAliases) return false
        deviceAliases = updated
        persistDeviceAliases()
        return true
    }

    /** 清空全部备注，返回清掉的条数。 */
    fun clearAllDeviceNotes(): Int {
        val count = deviceNotes.entries().size
        if (count == 0) return 0
        deviceNotes = DeviceNotes.EMPTY
        persistDeviceNotes()
        return count
    }

    /** 清空全部别名，返回清掉的条数。 */
    fun clearAllDeviceAliases(): Int {
        val count = deviceAliases.entries().size
        if (count == 0) return 0
        deviceAliases = DeviceAliases.EMPTY
        persistDeviceAliases()
        return count
    }

    /**
     * 按设备 ID 精确清除选中的备注与别名，返回实际删除的总条数。
     *
     * 只删传入 ID 上对应类型的数据，不触碰其他设备或其他类型；两个集合都无变化时
     * 不落盘，避免无意义的写入。用于「清空设备自定义信息」中按条目勾选删除。
     */
    fun clearDeviceCustomInfo(
        noteDeviceIds: Collection<String>,
        aliasDeviceIds: Collection<String>,
    ): Int {
        val notesDropped = noteDeviceIds.filter { deviceNotes.hasNote(it) }
        val aliasesDropped = aliasDeviceIds.filter { deviceAliases.hasAlias(it) }
        if (notesDropped.isEmpty() && aliasesDropped.isEmpty()) return 0

        if (notesDropped.isNotEmpty()) {
            deviceNotes = notesDropped.fold(deviceNotes) { acc, id -> acc.withoutNote(id) }
            persistDeviceNotes()
        }
        if (aliasesDropped.isNotEmpty()) {
            deviceAliases = aliasesDropped.fold(deviceAliases) { acc, id -> acc.withoutAlias(id) }
            persistDeviceAliases()
        }
        return notesDropped.size + aliasesDropped.size
    }

    /**
     * 把导入结果里的设备别名与备注批量写入本机，返回实际变更的条数。
     *
     * 只覆盖传入 map 里出现的设备，本机其它设备的别名/备注不受影响——导入是合并而非替换。
     * 两类数据分别落盘一次，避免逐条写入。
     */
    fun applyImportedDeviceInfo(
        notes: Map<String, String>,
        aliases: Map<String, String>,
    ): Int {
        var changed = 0
        if (notes.isNotEmpty()) {
            val updated = notes.entries.fold(deviceNotes) { acc, (id, text) -> acc.withNote(id, text) }
            if (updated !== deviceNotes) {
                changed += notes.count { deviceNote(it.key) != it.value }
                deviceNotes = updated
                persistDeviceNotes()
            }
        }
        if (aliases.isNotEmpty()) {
            val updated = aliases.entries.fold(deviceAliases) { acc, (id, text) -> acc.withAlias(id, text) }
            if (updated !== deviceAliases) {
                changed += aliases.count { deviceAlias(it.key) != it.value }
                deviceAliases = updated
                persistDeviceAliases()
            }
        }
        return changed
    }

    private fun persistDeviceAliases() {
        try {
            AppStorage.instance.saveString(StorageKeys.DEVICE_ALIASES, encodeDeviceAliases(deviceAliases))
        } catch (e: Exception) {
            onLogError("persistDeviceAliases", e)
        }
    }

    fun updateAutoDeduct(enabled: Boolean) {
        val auth = deviceAuth()
        if (auth.token.isEmpty()) return
        scope.launch {
            try {
                val result = api.setUseScore(auth.token, value = if (enabled) 1 else 0, appType = auth.appType)
                if (result.success) {
                    autoDeduct = enabled
                } else {
                    onToast("设置失败，请稍后再试")
                }
            } catch (e: Exception) {
                onLogError("updateAutoDeduct", e)
                onToast("设置失败，请稍后再试")
            }
        }
    }

    private fun inferHomeDeviceType(devices: List<Device>) {
        if (homeDeviceTypeExplicit) return
        val dtypes = devices.map { it.dtype }.filter { it != 0 }.toSet()
        if (dtypes.isEmpty()) return
        // 优先精确匹配四类场景，全都不是时归入“其它”
        homeDeviceType =
            HomeDeviceType.entries
                .firstOrNull { it != HomeDeviceType.Other && it.deviceType in dtypes }
                ?: HomeDeviceType.Other
    }

    fun reset() {
        deviceMonitorJob?.cancel()
        deviceMonitorJob = null
        runningDevices.clear()
        pollingDeviceId = null
        deviceDetail = null
        detailDeviceId = null
        lastDeviceLoadTime = 0L
    }

    fun loadDeviceInfo(force: Boolean = false): Job? {
        val auth = deviceAuth()
        if (auth.token.isEmpty()) return null
        val now = currentTimeMillis()
        if (!force && now - lastDeviceLoadTime < LOAD_COOLDOWN_MS) return null
        lastDeviceLoadTime = now
        return scope.launch {
            try {
                val master = api.getMasterDevices(auth.token)
                // 账号已切换/退出登录，丢弃过期结果
                if (!isCurrentToken(auth.token)) return@launch
                // 登录态失效：主接口不再返回账户数据
                if (master.accountId.isEmpty() && master.devices.isEmpty()) {
                    onSessionExpired()
                    return@launch
                }
                val devices = master.devices
                val updatedDevices =
                    devices.map { dto ->
                        val realStatus =
                            try {
                                api.getDevStatus(auth.token, dto.id, auth.appType)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // 逐台设备的状态探测失败属预期（离线/超时），仅记消息避免刷屏
                                logDebug("ILife798", "loadDeviceInfo.getDevStatus: ${e.message}")
                                null
                            }
                        Device(
                            id = dto.id,
                            name = dto.name,
                            geneStatus = realStatus?.geneStatus ?: dto.geneStatus,
                            deviceStatus = realStatus?.deviceStatus ?: 1,
                            dtype = dto.dtype,
                        )
                    }
                val account = getState().account
                setState {
                    it.copy(
                        devices = updatedDevices,
                        account = account.copy(uid = master.accountId.ifEmpty { account.uid }),
                    )
                }
                inferHomeDeviceType(updatedDevices)
            } catch (e: Exception) {
                onLogError("loadDeviceInfo", e)
            }
        }
    }

    fun addDevice(id: String) {
        val token = currentToken()
        if (token.isEmpty()) return
        scope.launch {
            try {
                val result = api.devFavo(token, id, remove = false)
                if (result.success) {
                    onToast("设备已添加")
                    // 乐观加入收藏列表，使详情页立即显示“已收藏”
                    setState { state ->
                        if (state.devices.any { it.id == id }) {
                            state
                        } else {
                            state.copy(
                                devices =
                                    state.devices +
                                        Device(
                                            id = id,
                                            name = "",
                                            geneStatus = 99,
                                            deviceStatus = 1,
                                            dtype = 0,
                                        ),
                            )
                        }
                    }
                    // 立即按服务端顺序刷新
                    loadDeviceInfo(force = true)
                }
            } catch (e: Exception) {
                onLogError("addDevice", e)
                onToast("添加失败：${e.message}")
            }
        }
    }

    // 离开设备详情页：刷新首页设备列表；仅在类型为自动推断时跟随该设备切换，不覆盖用户手选
    fun onDeviceDetailClosed(deviceId: String) {
        scope.launch {
            try {
                loadDeviceInfo(force = true)?.join()
                if (homeDeviceTypeExplicit) return@launch
                getState()
                    .devices
                    .firstOrNull { it.id == deviceId }
                    ?.let { HomeDeviceType.fromDeviceType(it.dtype) }
                    ?.let { homeDeviceType = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onLogError("onDeviceDetailClosed", e)
            }
        }
    }

    // 二维码/NFC 内容在 scope 中解析，避免随扫码页/添加页离开 composition 被取消
    fun submitScannedRaw(raw: String) {
        scope.launch {
            val deviceId = resolveScanDeviceId(raw) ?: return@launch
            scannedDeviceId = deviceId
        }
    }

    fun consumeScannedDeviceId() {
        scannedDeviceId = null
    }

    // 解析扫码内容：路径形式直接就是设备编号，?id= 形式经 /qr/use 换取设备编号
    private suspend fun resolveScanDeviceId(raw: String): String? {
        val token = currentToken()
        if (token.isEmpty()) {
            onToast("请先登录")
            return null
        }
        val parsed = DeviceLinkParser.parse(raw)
        parsed.deviceId?.takeIf { it.isNotEmpty() }?.let { return it }
        val qrId = parsed.qrId
        if (qrId.isNullOrEmpty()) {
            onToast("未知二维码内容")
            return null
        }
        return try {
            when (val outcome = resolveQrUseResult(api.qrUse(token, qrId))) {
                is QrResolveOutcome.Device -> {
                    outcome.deviceId
                }

                QrResolveOutcome.UnsupportedType -> {
                    onToast("暂不支持该类型二维码")
                    null
                }

                QrResolveOutcome.MissingDevice -> {
                    onToast("二维码中不包含设备信息")
                    null
                }

                QrResolveOutcome.Failed -> {
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onLogError("resolveScanDeviceId", e)
            onToast("扫码解析失败：${e.message}")
            null
        }
    }

    // 收藏/取消收藏；currentlyFollowed 为当前是否已收藏（决定 remove 方向）
    fun toggleDeviceFavorite(
        deviceId: String,
        currentlyFollowed: Boolean,
    ) {
        val token = currentToken()
        if (token.isEmpty()) return
        scope.launch {
            try {
                val result = api.devFavo(token, deviceId, remove = currentlyFollowed)
                if (result.success) {
                    onToast(if (currentlyFollowed) "取消收藏" else "收藏成功")
                    loadDeviceInfo(force = true)
                }
            } catch (e: Exception) {
                onLogError("toggleDeviceFavorite", e)
                onToast("操作失败：${e.message}")
            }
        }
    }

    // 设备详情页
    fun loadDeviceDetail(deviceId: String): Job? {
        val auth = deviceAuth()
        if (auth.token.isEmpty()) return null
        if (detailDeviceId != deviceId) {
            detailDeviceId = deviceId
            deviceDetail = null
        }
        return scope.launch {
            try {
                val detail = api.getDeviceDetail(auth.token, deviceId, auth.appType) ?: return@launch
                if (!isCurrentToken(auth.token)) return@launch
                // 已切换到其它设备详情，丢弃过期响应
                if (detailDeviceId != deviceId) return@launch
                deviceDetail = detail
                setState { state ->
                    state.copy(
                        devices =
                            state.devices.map {
                                if (it.id == deviceId) {
                                    it.copy(
                                        name = it.name.ifEmpty { detail.name },
                                        geneStatus = detail.geneStatus,
                                        deviceStatus = detail.deviceStatus,
                                        dtype = if (it.dtype == 0) detail.dtype else it.dtype,
                                    )
                                } else {
                                    it
                                }
                            },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onLogError("loadDeviceDetail", e)
            }
        }
    }

    fun toggleDeviceRunning(
        deviceId: String,
        args: String = "",
        forceStop: Boolean = false,
    ) {
        val auth = deviceAuth()
        if (auth.token.isEmpty()) return
        val deviceName =
            getState()
                .devices
                .firstOrNull { it.id == deviceId }
                ?.name
                ?.ifEmpty { deviceId } ?: deviceId
        pollingDeviceId = deviceId
        scope.launch {
            try {
                if (!isCurrentToken(auth.token)) return@launch
                val currentStatus = if (forceStop) null else api.getDevStatus(auth.token, deviceId, auth.appType)
                val starting = !forceStop && currentStatus?.geneStatus == 99
                if (starting) startingDeviceId = deviceId
                val result =
                    if (starting) {
                        requestNotificationPermission()
                        // 启动前把积分抵扣开关同步到服务端，保证开关与实际扣款方式一致
                        api.setUseScore(auth.token, value = if (autoDeduct) 1 else 0, appType = auth.appType)
                        // 优先钱袋支付（91），失败时自动切换支付宝免密支付（21）
                        val wallet =
                            api.devStart(
                                auth.token,
                                deviceId,
                                auth.appType,
                                ptype = PAY_TYPE_WALLET,
                                args = args,
                                reportError = false,
                            )
                        if (wallet.success) {
                            wallet
                        } else {
                            api.devStart(auth.token, deviceId, auth.appType, ptype = PAY_TYPE_ALIPAY, args = args)
                        }
                    } else {
                        api.devEnd(auth.token, deviceId, auth.appType)
                    }
                if (!result.success) return@launch
                syncRunning(deviceId, deviceName, starting)
                // 轮询至 geneStatus 变化：第一轮 5×2.5s 快查，未变再第二轮 25×5s 慢查
                var newStatus: DevStatusResult? = null
                var remaining = 5
                var stepMs = 2500L
                while (remaining > 0) {
                    remaining--
                    delay(stepMs.milliseconds)
                    if (!isCurrentToken(auth.token)) return@launch
                    newStatus = api.getDevStatus(auth.token, deviceId, auth.appType)
                    if (newStatus?.geneStatus != currentStatus?.geneStatus) break
                    if (remaining == 0 && stepMs == 2500L) {
                        stepMs = 5000L
                        remaining = 25
                    }
                }
                if (!isCurrentToken(auth.token)) return@launch
                setState { state ->
                    state.copy(
                        devices =
                            state.devices.map {
                                if (it.id == deviceId) {
                                    it.copy(
                                        geneStatus = newStatus?.geneStatus ?: it.geneStatus,
                                        deviceStatus = newStatus?.deviceStatus ?: it.deviceStatus,
                                    )
                                } else {
                                    it
                                }
                            },
                    )
                }
                // 详情页同步最新状态与会话结束时间（gene.time 用于“剩余X分钟”）
                if (newStatus != null) {
                    val status = newStatus
                    val keepEndTime = deviceDetail?.geneEndTime ?: 0L
                    deviceDetail =
                        deviceDetail
                            ?.takeIf { it.id == deviceId }
                            ?.copy(
                                geneStatus = status.geneStatus,
                                deviceStatus = status.deviceStatus,
                                geneEndTime = status.geneEndTime.takeIf { it > 0 } ?: keepEndTime,
                            )
                }
                // 启动后以轮询结果校正（停止在请求成功时已上报）
                if (starting) {
                    // starting=true 蕴含 currentStatus 非空，elvis 结果必非空
                    val isRunning = (newStatus ?: currentStatus).geneStatus != 99
                    syncRunning(deviceId, deviceName, isRunning)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onToast("操作失败：${e.message}")
            } finally {
                if (pollingDeviceId == deviceId) pollingDeviceId = null
                if (startingDeviceId == deviceId) startingDeviceId = null
            }
        }
    }

    // 运行通知与后台监控开关同步（启动请求成功后乐观上报 + 状态轮询校正共用）
    private fun syncRunning(
        deviceId: String,
        deviceName: String,
        running: Boolean,
    ) {
        RunNotifications.updateDevice(deviceId, deviceName, running)
        if (running) {
            runningDevices[deviceId] = deviceName
            ensureDeviceMonitor()
        } else {
            runningDevices.remove(deviceId)
        }
    }

    // 周期性检查应用启动后仍在运行的设备；机器端手动停止后收起其运行通知
    private fun ensureDeviceMonitor() {
        if (deviceMonitorJob?.isActive == true) return
        if (runningDevices.isEmpty()) return
        deviceMonitorJob =
            scope.launch {
                while (isActive && runningDevices.isNotEmpty()) {
                    delay(DEVICE_MONITOR_INTERVAL_SECONDS.seconds)
                    val auth = deviceAuth()
                    if (auth.token.isEmpty()) break
                    runningDevices.toMap().forEach { (id, name) ->
                        val status =
                            try {
                                api.getDevStatus(auth.token, id, auth.appType)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                onLogError("deviceMonitor", e)
                                null
                            }
                        if (status?.geneStatus == 99) {
                            runningDevices.remove(id)
                            RunNotifications.updateDevice(id, name, false)
                            setState { state ->
                                state.copy(
                                    devices =
                                        state.devices.map {
                                            if (it.id == id) it.copy(geneStatus = 99) else it
                                        },
                                )
                            }
                        }
                    }
                }
            }
    }

    // 通知“停止”按钮：强制停止对应设备（不依赖当前状态判断）
    fun stopDevice(deviceId: String) {
        toggleDeviceRunning(deviceId, forceStop = true)
    }
}
