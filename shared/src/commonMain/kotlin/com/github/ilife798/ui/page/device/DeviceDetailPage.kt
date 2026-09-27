package com.github.ilife798.ui.page.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.ilife798.DeviceTile
import com.github.ilife798.DeviceTileResult
import com.github.ilife798.copyToClipboard
import com.github.ilife798.data.model.DeviceOption
import com.github.ilife798.data.model.DeviceSubState
import com.github.ilife798.data.model.deviceDisplayStatus
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.data.viewmodel.buildDeviceDetailStartArgs
import com.github.ilife798.showToast
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.ConfirmDialog
import com.github.ilife798.ui.component.DeviceIcon
import com.github.ilife798.ui.component.HeaderRow
import com.github.ilife798.ui.component.InfoRow
import com.github.ilife798.ui.component.LoadingCard
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.component.SectionHeader
import com.github.ilife798.ui.component.StatusPill
import com.github.ilife798.ui.component.SwitchPreference
import com.github.ilife798.ui.theme.SettleAmber
import com.github.ilife798.ui.theme.primaryButtonColors
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import com.github.ilife798.util.currentTimeMillis
import com.github.ilife798.util.formatMoney
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val DTYPE_CHARGING = 120
private const val DTYPE_HAIR_DRIER = 20
private const val SENSOR_MULTIPLE = 12
private const val SENSOR_TIME_CTL = 13
private const val HAIR_DRIER_FMV_BUILD = 311
private const val GATEWAY_HAIER = 6

// 固定程序类（洗/烘/鞋）：运行中仅展示状态，不允许手动结算，程序结束由服务端结算
private val PROGRAM_DTYPES = setOf(10, 80, 90)

// 仅充电桩（有 subs）与吹风机（一拖多传感器、有 subs 且固件末段版本 > 311）启动前要求选通道
internal fun isChannelRequired(
    dtype: Int,
    subsCount: Int,
    sensors: List<Int>,
    fmv: String = "",
): Boolean =
    when (dtype) {
        DTYPE_CHARGING -> subsCount > 0
        DTYPE_HAIR_DRIER -> SENSOR_MULTIPLE in sensors && subsCount > 0 && fmvBuildOver(fmv)
        else -> false
    }

// fmv 按 "." 分段取末段转数值，须大于 311；不可解析按 false
private fun fmvBuildOver(fmv: String): Boolean =
    fmv
        .split(".")
        .lastOrNull()
        ?.toIntOrNull()
        ?.let { it > HAIR_DRIER_FMV_BUILD } ?: false

// 支持的设备类型描述，其余走“设备”兜底
private fun dtypeLabel(dtype: Int): String =
    when (dtype) {
        5 -> "水控/直饮机"
        6 -> "水表/淋浴器"
        8 -> "管线/饮水机"
        9 -> "净/热水主机"
        10 -> "洗衣机"
        20 -> "吹风机"
        21 -> "洗发/沐浴露主机"
        30 -> "自动售货机"
        35 -> "售卡/充值机"
        45 -> "电话机"
        50 -> "门禁"
        60 -> "消费机"
        70 -> "开卡器"
        75 -> "充电宝"
        80 -> "烘干机"
        90 -> "洗鞋机"
        95 -> "计量控制器"
        100 -> "取件柜"
        110 -> "分拣台"
        115 -> "查询屏"
        120 -> "充电桩"
        130 -> "定时控制器"
        140 -> "打卡机"
        else -> "设备"
    }

// 启动确认文案：洗/烘/鞋按网关区分——海尔网关（gtype=6）带模式名，其余固定文案
internal fun startConfirmMessage(
    dtype: Int,
    modeName: String,
    gtype: Int = 0,
): String {
    val machine =
        when (dtype) {
            10 -> "洗衣机"
            80 -> "烘干机"
            90 -> "洗鞋机"
            else -> null
        }
    if (machine != null) {
        val haier = gtype == GATEWAY_HAIER
        val item = if (dtype == 90 && haier) "鞋子" else "衣物"
        return if (haier) {
            "请确认已将${item}放入$machine，确认选择${modeName}模式"
        } else {
            "请确认是否将${item}放入$machine，点击确定之后，${machine}将开始运行"
        }
    }
    return when (dtype) {
        DTYPE_CHARGING -> "确认开始充电？"
        else -> "请确认选择的模式，点击确定之后，设备将开始运行"
    }
}

private data class UseButtonState(
    val text: String,
    val enabled: Boolean,
    val settle: Boolean,
    val isStart: Boolean,
) {
    companion object {
        // 禁用态（未登录/启动中/离线/禁用/无费率）：启动按钮不可点
        fun disabled(text: String) = UseButtonState(text, enabled = false, settle = false, isStart = true)

        // 待机启动态
        fun start(text: String) = UseButtonState(text, enabled = true, settle = false, isStart = true)

        // 结算态；enabled=false 为固定程序的“运行中”禁用态
        fun settle(
            text: String,
            enabled: Boolean = true,
        ) = UseButtonState(text, enabled = enabled, settle = true, isStart = false)
    }
}

@Composable
fun DeviceDetailPage(
    viewModel: AppViewModel,
    deviceId: String,
    onBack: () -> Unit,
) {
    val state = viewModel.state
    val isLoggedIn = state.account.hasAnyToken
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(state.appBlur)

    val liveDevice = state.devices.firstOrNull { it.id == deviceId }
    val detail = viewModel.deviceDetail?.takeIf { it.id == deviceId }

    LaunchedEffect(deviceId) {
        if (state.account.hasAnyToken) viewModel.loadDeviceDetail(deviceId)
    }

    val resolvedName =
        liveDevice?.name?.takeIf { it.isNotEmpty() }
            ?: detail?.name?.takeIf { it.isNotEmpty() }
    var cachedName by remember(deviceId) { mutableStateOf("") }
    LaunchedEffect(resolvedName) {
        if (!resolvedName.isNullOrEmpty()) cachedName = resolvedName
    }
    val name = cachedName.ifEmpty { deviceId }
    val dtype = liveDevice?.dtype?.takeIf { it != 0 } ?: detail?.dtype ?: 0
    val geneStatus = liveDevice?.geneStatus ?: detail?.geneStatus ?: 0
    val deviceStatus = liveDevice?.deviceStatus ?: detail?.deviceStatus ?: 1

    val parts = detail?.parts ?: emptyList()
    val subs = detail?.subs ?: emptyList()
    val sensors = detail?.sensors ?: emptyList()
    val goods = detail?.goods ?: emptyList()
    val fmv = detail?.fmv ?: ""
    val gtype = detail?.gtype ?: 0

    var selectedMode by remember(deviceId) { mutableStateOf<Int?>(null) }
    var selectedChannel by remember(deviceId) { mutableStateOf(-1) }
    var showStartConfirm by remember(deviceId) { mutableStateOf(false) }
    var showSettleConfirm by remember(deviceId) { mutableStateOf(false) }
    var showTileConfirm by remember(deviceId) { mutableStateOf(false) }

    val followed = state.devices.any { it.id == deviceId }

    // 服务端默认选中的通道（isSelect）预选，仅通道设备生效；仅接受可用通道
    LaunchedEffect(detail) {
        if (detail != null && selectedChannel < 0 && isChannelRequired(dtype, detail.subs.size, sensors, fmv)) {
            val index = detail.subs.indexOfFirst { it.isSelect && it.available }
            if (index >= 0) selectedChannel = index
        }
    }

    val passageDevice = dtype == DTYPE_HAIR_DRIER || dtype == DTYPE_CHARGING
    val timeCtl = SENSOR_TIME_CTL in sensors
    val hasRateParts = parts.any { it.rate > 0.0 }
    val partsNoRate = parts.isNotEmpty() && !hasRateParts
    val modeRequired =
        when {
            passageDevice -> timeCtl && parts.isNotEmpty()
            else -> hasRateParts
        }
    val channelRequired = isChannelRequired(dtype, subs.size, sensors, fmv)
    val needsConfirm =
        when (dtype) {
            DTYPE_CHARGING -> true
            DTYPE_HAIR_DRIER -> false
            else -> modeRequired
        }

    val isPolling = viewModel.pollingDeviceId == deviceId
    val isOffline = deviceStatus == 0
    val now = currentTimeMillis()
    val endTime = detail?.geneEndTime ?: 0L

    // 运行剩余分钟（一拖多且未到预计结束时间；固定程序须 TIME_CTL 触发）
    fun remainMinutes(timeCtlGate: Boolean): Long =
        if ((!timeCtlGate || timeCtl) && SENSOR_MULTIPLE in sensors && endTime > now) {
            ((endTime - now) / 60000).coerceAtLeast(1)
        } else {
            0L
        }
    val buttonState =
        when {
            !isLoggedIn -> {
                UseButtonState.disabled("请先登录")
            }

            // 启动请求进行中
            viewModel.startingDeviceId == deviceId -> {
                UseButtonState.disabled("启动中")
            }

            isOffline -> {
                UseButtonState.disabled("设备离线")
            }

            // 无费率先于禁用判定
            partsNoRate -> {
                UseButtonState.disabled("未设置费率信息")
            }

            geneStatus == 98 -> {
                UseButtonState.disabled("禁用")
            }

            geneStatus == 99 -> {
                UseButtonState.start(if (dtype == DTYPE_CHARGING) "开始充电" else "立即使用")
            }

            // 固定程序运行中不提供手动结算（运行中/剩余分钟，琥珀禁用）
            dtype in PROGRAM_DTYPES -> {
                val remain = remainMinutes(timeCtlGate = true)
                UseButtonState.settle(if (remain > 0) "剩余${remain}分钟" else "运行中", enabled = false)
            }

            else -> {
                val remain = remainMinutes(timeCtlGate = false)
                UseButtonState.settle(if (remain > 0) "剩余${remain}分钟" else "立即结算")
            }
        }
    // 详情未加载完成时禁用主按钮，避免回落的默认状态触发错误的启动/结算
    val buttonEnabled = buttonState.enabled && !isPolling && detail != null

    fun doStart() {
        val args =
            buildDeviceDetailStartArgs(
                goods = goods,
                partMode = selectedMode,
                channelIndex = selectedChannel,
                passageDevice = passageDevice,
                // 洗衣机且非海尔网关未选模式时仍带 {"mode":-1,...}
                washingFallbackMode = dtype == 10 && gtype != GATEWAY_HAIER,
            )
        // 始终优先钱包（91）支付，不足时 controller 内自动回退支付宝免密（21）
        viewModel.toggleDeviceRunning(deviceId, args)
    }

    fun onStartClick() {
        if (channelRequired && selectedChannel < 0) {
            showToast("请选择通道")
            return
        }
        if (modeRequired && selectedMode == null) {
            showToast("请先选择模式")
            return
        }
        if (needsConfirm) {
            showStartConfirm = true
        } else {
            doStart()
        }
    }

    Scaffold(
        topBar = {
            BlurredTopAppBar(
                title = "设备详情",
                blurBackdrop = blurBackdrop,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
        bottomBar = {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surface)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { showTileConfirm = true },
                    ) {
                        Icon(
                            imageVector = MiuixIcons.GridView,
                            contentDescription = "创建快捷设置图块",
                            modifier = Modifier.size(22.dp),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = "图块", style = MiuixTheme.textStyles.body2)
                    }
                    // 未登录时后端收藏接口不可用，禁用按钮
                    val favColor =
                        when {
                            !isLoggedIn -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                            followed -> MiuixTheme.colorScheme.primary
                            else -> MiuixTheme.colorScheme.onSurface
                        }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                            Modifier.clickable(enabled = isLoggedIn) {
                                viewModel.toggleDeviceFavorite(deviceId, followed)
                            },
                    ) {
                        Icon(
                            imageVector = if (followed) MiuixIcons.FavoritesFill else MiuixIcons.Favorites,
                            contentDescription = if (followed) "取消收藏" else "收藏",
                            modifier = Modifier.size(22.dp),
                            tint = favColor,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (followed) "取消" else "收藏",
                            style = MiuixTheme.textStyles.body2,
                            color = favColor,
                        )
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when {
                                buttonState.isStart -> onStartClick()

                                // 结束付费会话前需二次确认，与启动确认保持一致
                                else -> showSettleConfirm = true
                            }
                        },
                        enabled = buttonEnabled,
                        colors =
                            if (buttonState.settle) {
                                // 琥珀底白字不明显，文字用黑色
                                ButtonDefaults.buttonColors(color = SettleAmber, contentColor = Color.Black)
                            } else {
                                primaryButtonColors(state.dynamicColor)
                            },
                    ) {
                        Text(text = buttonState.text, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
    ) { paddingValues ->
        PageScrollColumn(blurBackdrop, scrollBehavior, paddingValues) {
            DeviceInfoCard(
                deviceId = deviceId,
                name = name,
                dtype = dtype,
                geneStatus = geneStatus,
                deviceStatus = deviceStatus,
                enterpriseName = detail?.enterpriseName ?: "",
                enterpriseAbbr = detail?.enterpriseAbbr ?: "",
                contactPhone = detail?.contactPhone ?: "",
            )

            if (isLoggedIn) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "开启积分自动抵扣",
                        checked = viewModel.autoDeduct,
                        onCheckedChange = { viewModel.updateAutoDeduct(it) },
                    )
                }
            }

            if (detail == null) {
                if (isLoggedIn || liveDevice != null) {
                    LoadingCard(title = "设备信息", message = "正在加载设备信息…")
                }
            } else {
                if (parts.isNotEmpty()) {
                    ModeCard(
                        dtype = dtype,
                        parts = parts,
                        selectedMode = selectedMode,
                        onSelect = { selectedMode = it },
                    )
                }

                if (channelRequired) {
                    ChannelCard(
                        subs = subs,
                        selectedChannel = selectedChannel,
                        onSelect = { selectedChannel = it },
                    )
                }
            }
        }
    }

    if (showStartConfirm) {
        val selectedPart = parts.firstOrNull { it.mode == selectedMode }
        val modeName = selectedPart?.name?.ifEmpty { "模式 $selectedMode" } ?: "模式 $selectedMode"
        ConfirmDialog(
            title = "启动设备",
            message = startConfirmMessage(dtype, modeName, gtype),
            appBlur = state.appBlur,
            confirmText = "启动",
            onConfirm = {
                showStartConfirm = false
                doStart()
            },
            onDismiss = { showStartConfirm = false },
        )
    }

    if (showSettleConfirm) {
        ConfirmDialog(
            title = "结束使用",
            message = "确认结束本次使用并结算吗？",
            appBlur = state.appBlur,
            confirmText = "结算",
            onConfirm = {
                showSettleConfirm = false
                viewModel.toggleDeviceRunning(deviceId)
            },
            onDismiss = { showSettleConfirm = false },
        )
    }

    if (showTileConfirm) {
        ConfirmDialog(
            title = "创建设备快捷设置图块",
            message = "确定要创建设备快捷设置图块吗？",
            appBlur = state.appBlur,
            onConfirm = {
                DeviceTile.bind(deviceId, name) { result ->
                    showToast(
                        when (result) {
                            DeviceTileResult.ADDED -> "已添加设备快捷设置图块"
                            DeviceTileResult.ALREADY_ADDED -> "图块已存在，已更新为当前设备"
                            DeviceTileResult.UNSUPPORTED -> "请在快捷设置面板中手动添加图块"
                            DeviceTileResult.CANCELLED -> "已取消添加图块"
                            DeviceTileResult.FAILED -> "创建失败"
                        },
                    )
                }
                showTileConfirm = false
            },
            onDismiss = { showTileConfirm = false },
        )
    }
}

@Composable
private fun DeviceInfoCard(
    deviceId: String,
    name: String,
    dtype: Int,
    geneStatus: Int,
    deviceStatus: Int,
    enterpriseName: String,
    enterpriseAbbr: String,
    contactPhone: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            HeaderRow(
                icon = { DeviceIcon(dtype = dtype, size = 40.dp) },
                title = name,
                trailing = {
                    StatusPill(deviceDisplayStatus(deviceStatus, geneStatus, dtype))
                },
                id = deviceId,
                onIdClick = { copyToClipboard(deviceId, "已复制设备编号") },
            )
            if (enterpriseName.isNotEmpty()) {
                InfoRow("商家", enterpriseName)
            }
            if (enterpriseAbbr.isNotEmpty() && enterpriseAbbr != enterpriseName) {
                InfoRow("服务商", enterpriseAbbr)
            }
            if (contactPhone.isNotEmpty()) {
                InfoRow("联系电话", contactPhone, onValueClick = { copyToClipboard(contactPhone, "已复制联系电话") })
            }
        }
    }
}

@Composable
private fun ModeCard(
    dtype: Int,
    parts: List<DeviceOption>,
    selectedMode: Int?,
    onSelect: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader("选择需要的${dtypeLabel(dtype)}模式")
            parts.forEach { part ->
                val summary =
                    buildList {
                        if (part.rate > 0.0) add("¥${formatMoney(part.rate)}")
                        if (part.maxT > 0) add("最长 ${part.maxT}")
                    }.joinToString(" · ")
                RadioButtonPreference(
                    title = part.name.ifEmpty { "模式 ${part.mode}" },
                    summary = summary.ifEmpty { null },
                    selected = selectedMode == part.mode,
                    onClick = { onSelect(part.mode) },
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(
    subs: List<DeviceSubState>,
    selectedChannel: Int,
    onSelect: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader("选择通道")
            subs.forEachIndexed { index, sub ->
                val available = sub.available
                RadioButtonPreference(
                    title = "通道 ${index + 1}",
                    summary =
                        when {
                            available -> "空闲"
                            sub.status == 99 -> "故障"
                            sub.status == 98 -> "禁用"
                            else -> "使用中"
                        },
                    selected = selectedChannel == index,
                    onClick = { onSelect(index) },
                    radioButtonLocation = RadioButtonLocation.End,
                    enabled = available,
                )
            }
        }
    }
}
