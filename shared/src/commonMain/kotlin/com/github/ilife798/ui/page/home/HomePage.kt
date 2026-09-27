package com.github.ilife798.ui.page.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.ilife798.data.model.HomeDeviceType
import com.github.ilife798.data.model.deviceDisplayStatus
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.ui.component.AppPullToRefresh
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.DeviceIcon
import com.github.ilife798.ui.component.EmptyStateText
import com.github.ilife798.ui.component.HeaderRow
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.component.StatusPill
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import com.github.ilife798.util.formatMoney
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomePage(
    viewModel: AppViewModel,
    onDeviceAddClick: () -> Unit = {},
    onDeviceClick: (String) -> Unit = {},
    bottomPadding: Dp,
) {
    val state = viewModel.state
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(state.appBlur)

    Scaffold(
        topBar = { BlurredTopAppBar("首页", blurBackdrop, scrollBehavior) },
    ) { paddingValues ->
        AppPullToRefresh(viewModel.homeRefreshing, { viewModel.refreshHome() }, scrollBehavior, paddingValues) {
            PageScrollColumn(
                blurBackdrop = blurBackdrop,
                scrollBehavior = scrollBehavior,
                contentPadding = paddingValues,
                bottomPadding = bottomPadding,
            ) {
                val stats = viewModel.spendingStats
                val isLoggedIn = state.account.hasAnyToken
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SpendingCard(
                        label = "昨日花费",
                        value = stats.yesterday,
                        isLoggedIn = isLoggedIn,
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                    )
                    SpendingCard(
                        label = "今日花费",
                        value = stats.today,
                        isLoggedIn = isLoggedIn,
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                    )
                    SpendingCard(
                        label = "本月平均花费",
                        value = stats.monthAverage,
                        isLoggedIn = isLoggedIn,
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                    )
                }
                DeviceCard(viewModel, onDeviceAddClick, onDeviceClick)
            }
        }
    }
}

@Composable
private fun SpendingCard(
    label: String,
    value: Double,
    isLoggedIn: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 12.dp, bottom = 16.dp)) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = if (isLoggedIn) "¥${formatMoney(value)}" else "--",
                style = MiuixTheme.textStyles.title2,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DeviceCard(
    viewModel: AppViewModel,
    onDeviceAddClick: () -> Unit,
    onDeviceClick: (String) -> Unit,
) {
    val allDevices = viewModel.state.devices
    val homeType = viewModel.homeDeviceType
    val devices =
        remember(allDevices, homeType) {
            allDevices.filter { HomeDeviceType.fromDeviceType(it.dtype) == homeType }
        }
    val isLoggedIn = viewModel.state.account.hasAnyToken

    val deviceTypeEntry =
        remember(viewModel.homeDeviceType) {
            DropdownEntry(
                items =
                    HomeDeviceType.entries.map { type ->
                        DropdownItem(
                            text = type.label,
                            selected = viewModel.homeDeviceType == type,
                            onClick = { viewModel.selectHomeDeviceType(type) },
                        )
                    },
            )
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                // 行高由图标按钮撑起，标题与图标中线对齐
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "常用设备",
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        enabled = isLoggedIn,
                        minHeight = 32.dp,
                        minWidth = 32.dp,
                        onClick = onDeviceAddClick,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Add,
                            contentDescription = "添加",
                            modifier = Modifier.size(20.dp),
                            tint =
                                if (isLoggedIn) {
                                    MiuixTheme.colorScheme.onSurface
                                } else {
                                    MiuixTheme.colorScheme.onSurface.copy(
                                        alpha = 0.3f,
                                    )
                                },
                        )
                    }
                    OverlayIconDropdownMenu(
                        entry = deviceTypeEntry,
                        enabled = isLoggedIn,
                        minHeight = 32.dp,
                        minWidth = 32.dp,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.More,
                            contentDescription = "选择设备类型",
                            modifier = Modifier.size(20.dp),
                            tint =
                                if (isLoggedIn) {
                                    MiuixTheme.colorScheme.onSurface
                                } else {
                                    MiuixTheme.colorScheme.onSurface.copy(
                                        alpha = 0.3f,
                                    )
                                },
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            if (devices.isEmpty()) {
                EmptyStateText(isLoggedIn, emptyText = "暂无${homeType.label}设备")
            } else {
                devices.forEachIndexed { index, device ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    DeviceItem(
                        device = device,
                        onOpen = { onDeviceClick(device.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device: com.github.ilife798.data.model.Device,
    onOpen: () -> Unit,
) {
    HeaderRow(
        modifier =
            Modifier
                .clickable(onClick = onOpen)
                .padding(vertical = 8.dp),
        icon = { DeviceIcon(dtype = device.dtype, size = 40.dp) },
        title = device.name.ifEmpty { device.id },
        titleStyle = MiuixTheme.textStyles.title4,
        trailing = {
            StatusPill(deviceDisplayStatus(device.deviceStatus, device.geneStatus, device.dtype))
        },
        id = if (device.name.isNotEmpty()) device.id else "",
    )
}
