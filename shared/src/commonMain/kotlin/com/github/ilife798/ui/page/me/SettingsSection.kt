package com.github.ilife798.ui.page.me

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.github.ilife798.data.model.PaletteStyle
import com.github.ilife798.data.model.ThemeMode
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.getAppVersion
import com.github.ilife798.getAppVersionCode
import com.github.ilife798.isIOS
import com.github.ilife798.showToast
import com.github.ilife798.supportsDynamicColor
import com.github.ilife798.supportsPredictiveBack
import com.github.ilife798.ui.component.ConfirmDialog
import com.github.ilife798.ui.component.DisclaimerDialog
import com.github.ilife798.ui.component.SwitchPreference
import com.github.ilife798.ui.theme.ColorSwatchPreview
import com.github.ilife798.ui.theme.PresetColors
import com.github.ilife798.util.currentTimeMillis
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported

@Composable
internal fun SettingsSection(
    viewModel: AppViewModel,
    onLicenseClick: () -> Unit,
    onBackupClick: () -> Unit,
    wideScreen: Boolean,
) {
    val state = viewModel.state
    val uriHandler = LocalUriHandler.current
    val themeOptions = listOf("跟随系统", "浅色", "深色")
    var themeSelectedIndex by remember {
        mutableIntStateOf(
            when (state.themeMode) {
                ThemeMode.System -> 0
                ThemeMode.Light -> 1
                ThemeMode.Dark -> 2
            },
        )
    }
    var showDisclaimerDialog by remember { mutableStateOf(false) }
    var showGithubProxyDialog by remember { mutableStateOf(false) }
    var showClearDeviceInfo by remember { mutableStateOf(false) }
    var showSponsorDialog by remember { mutableStateOf(false) }
    // 选择弹窗与二次确认之间暂存待删除项，null 表示当前没有待确认的删除
    var pendingClear by remember { mutableStateOf<PendingClearSelection?>(null) }
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastVersionTapAt by remember { mutableLongStateOf(0L) }

    SmallTitle(text = "应用设置", insideMargin = PaddingValues(16.dp, 8.dp, 16.dp, 0.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            SmallTitle(text = "外观", insideMargin = PaddingValues(16.dp, 8.dp, 16.dp, 0.dp))
            OverlayDropdownPreference(
                title = "主题模式",
                items = themeOptions,
                selectedIndex = themeSelectedIndex,
                onSelectedIndexChange = { index ->
                    themeSelectedIndex = index
                    val mode =
                        when (index) {
                            0 -> ThemeMode.System
                            1 -> ThemeMode.Light
                            else -> ThemeMode.Dark
                        }
                    viewModel.setThemeMode(mode)
                },
            )
            if (!isIOS && isRuntimeShaderSupported()) {
                SwitchPreference(
                    title = "模糊效果",
                    summary = "为顶栏、底栏、对话框添加模糊效果",
                    checked = state.appBlur,
                    onCheckedChange = { viewModel.setAppBlur(it) },
                )
            }
            if (supportsPredictiveBack) {
                SwitchPreference(
                    title = "预测性返回动画",
                    summary = "返回滑动前提前预览即将跳转至的界面",
                    checked = state.predictiveBackEnabled,
                    onCheckedChange = { viewModel.setPredictiveBackEnabled(it) },
                )
            }
            SwitchPreference(
                title = "自定义颜色",
                summary = "自定义应用主题配色方案",
                checked = state.customColor,
                onCheckedChange = { viewModel.setCustomColor(it) },
            )
            if (supportsDynamicColor) {
                AnimatedVisibility(
                    visible = state.customColor,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    SwitchPreference(
                        title = "动态取色",
                        summary = "基于系统壁纸颜色生成配色方案",
                        checked = state.dynamicColor,
                        onCheckedChange = { viewModel.setDynamicColor(it) },
                    )
                }
            }
            AnimatedVisibility(
                visible = state.customColor,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                OverlayDropdownPreference(
                    title = "调色板风格",
                    items = PaletteStyle.entries.map { it.displayName },
                    selectedIndex = PaletteStyle.entries.indexOf(state.paletteStyle).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        viewModel.setPaletteStyle(PaletteStyle.entries[index])
                    },
                )
            }
            AnimatedVisibility(
                visible = state.customColor && !state.dynamicColor,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                PresetColorGrid(
                    paletteStyle = state.paletteStyle,
                    selectedSeed = state.seedColor,
                    onSelect = { viewModel.setSeedColor(it) },
                )
            }
            if (!wideScreen) {
                SwitchPreference(
                    title = "悬浮底栏",
                    summary = "切换悬浮式底部导航栏",
                    checked = state.floatingNav,
                    onCheckedChange = { viewModel.setFloatingNav(it) },
                )
            }

            SmallTitle(text = "更新设置", insideMargin = PaddingValues(16.dp, 8.dp, 16.dp, 0.dp))
            ArrowPreference(
                title = "检查更新",
                summary = "从 GitHub 检查最新版本",
                onClick = { viewModel.checkForUpdate() },
            )
            SwitchPreference(
                title = "启动时检查更新",
                summary = "打开应用时自动检查新版本",
                checked = viewModel.checkUpdateOnStart,
                onCheckedChange = { viewModel.setCheckUpdateOnStartEnabled(it) },
            )
            ArrowPreference(
                title = "加速地址",
                summary = viewModel.githubProxyUrl.ifBlank { "设置 GitHub 加速地址" },
                onClick = { showGithubProxyDialog = true },
            )
        }
    }
    SmallTitle(text = "备份与重置", insideMargin = PaddingValues(16.dp, 8.dp, 16.dp, 0.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ArrowPreference(
                title = "备份数据",
                summary = "导出或导入账号与设备自定义信息",
                onClick = onBackupClick,
            )
            ArrowPreference(
                title = "清空设备自定义信息",
                summary = "选择要清空的设备别名与备注，仅影响本机",
                onClick = { showClearDeviceInfo = true },
            )
        }
    }
    SmallTitle(text = "关于", insideMargin = PaddingValues(16.dp, 8.dp, 16.dp, 0.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            BasicComponent(
                title = "版本",
                summary = "${getAppVersion()}(${getAppVersionCode()})",
                modifier =
                    Modifier.combinedClickable(
                        onClick = {
                            val now = currentTimeMillis()
                            versionTapCount = if (now - lastVersionTapAt > 2000L) 1 else versionTapCount + 1
                            lastVersionTapAt = now
                            if (versionTapCount >= 5) {
                                versionTapCount = 0
                                if (viewModel.developerMode) {
                                    viewModel.disableDeveloperMode()
                                    showToast("已关闭开发者模式")
                                } else {
                                    viewModel.enableDeveloperMode()
                                    showToast("已启用开发者模式")
                                }
                            }
                        },
                    ),
            )
            ArrowPreference(
                title = "查看源代码",
                summary = "在 GitHub 上查看源代码",
                onClick = { uriHandler.openUri("https://github.com/lanyi233/ilife798") },
            )
            ArrowPreference(
                title = "开放源代码许可",
                summary = "查看应用所使用的第三方开源库及其许可证信息",
                onClick = onLicenseClick,
            )
            ArrowPreference(
                title = "免责声明",
                summary = "查看使用本应用的免责声明",
                onClick = { showDisclaimerDialog = true },
            )
            ArrowPreference(
                title = "赞助支持",
                summary = "赞助维护者一杯水",
                onClick = { showSponsorDialog = true },
            )
        }
    }

    if (showSponsorDialog) {
        SponsorSupportDialog(
            appBlur = state.appBlur,
            onDismiss = { showSponsorDialog = false },
            onSelect = { target ->
                // 选完先收起弹窗，再交给平台层打开外部浏览器
                showSponsorDialog = false
                viewModel.openSponsor(target.url)
            },
        )
    }

    if (showClearDeviceInfo) {
        ClearDeviceInfoDialog(
            aliases = viewModel.deviceAliases.entries(),
            notes = viewModel.deviceNotes.entries(),
            appBlur = state.appBlur,
            onDismiss = { showClearDeviceInfo = false },
            onClearSelected = { noteIds, aliasIds ->
                // 先收起选择弹窗，把待删除项暂存，再走第二次确认，避免误触直接删
                pendingClear = PendingClearSelection(noteIds, aliasIds)
                showClearDeviceInfo = false
            },
        )
    }

    pendingClear?.let { pending ->
        val total = pending.noteDeviceIds.size + pending.aliasDeviceIds.size
        ConfirmDialog(
            title = "清空设备自定义信息",
            message =
                "将删除选中的 $total 项（备注 ${pending.noteDeviceIds.size} 条、" +
                    "别名 ${pending.aliasDeviceIds.size} 条），此操作不可撤销。",
            appBlur = state.appBlur,
            destructive = true,
            onConfirm = {
                val count =
                    viewModel.clearDeviceCustomInfo(
                        noteDeviceIds = pending.noteDeviceIds,
                        aliasDeviceIds = pending.aliasDeviceIds,
                    )
                pendingClear = null
                showToast(if (count > 0) "已清空 $count 项" else "没有可清空的内容")
            },
            onDismiss = { pendingClear = null },
        )
    }

    if (showDisclaimerDialog) {
        DisclaimerDialog(
            appBlur = state.appBlur,
            onDismiss = { showDisclaimerDialog = false },
        )
    }

    UpdateDialogs(
        viewModel = viewModel,
        showGithubProxyDialog = showGithubProxyDialog,
        onDismissGithubProxyDialog = { showGithubProxyDialog = false },
    )
}

@Composable
private fun PresetColorGrid(
    paletteStyle: PaletteStyle,
    selectedSeed: Int,
    onSelect: (Int) -> Unit,
) {
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        val columns = (maxWidth / 80.dp).toInt().coerceAtLeast(1)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PresetColors.chunked(columns).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    rowItems.forEach { preset ->
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            ColorSwatchPreview(
                                preset = preset,
                                paletteStyle = paletteStyle,
                                selected = selectedSeed == preset.color.toArgb(),
                                onClick = { onSelect(preset.color.toArgb()) },
                            )
                        }
                    }
                    repeat(columns - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * 「清空设备自定义信息」在两次弹窗之间暂存的待删除项。
 *
 * 选择弹窗只负责收集勾选，确认删除前还要再问一次，因此需要把勾选结果从第一个
 * 弹窗带到第二个弹窗。两个集合分别对应备注与别名命中的设备 ID。
 */
private data class PendingClearSelection(
    val noteDeviceIds: List<String>,
    val aliasDeviceIds: List<String>,
)
