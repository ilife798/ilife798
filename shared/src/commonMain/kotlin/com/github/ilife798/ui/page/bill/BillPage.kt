package com.github.ilife798.ui.page.bill

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.ilife798.data.model.BillRecord
import com.github.ilife798.data.model.PendingRecharge
import com.github.ilife798.data.model.RechargeProduct
import com.github.ilife798.data.model.RefundProgress
import com.github.ilife798.data.model.WalletAccount
import com.github.ilife798.data.model.billStatusName
import com.github.ilife798.data.model.paymentTypeName
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.showToast
import com.github.ilife798.ui.component.AppPullToRefresh
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.ConfirmDialog
import com.github.ilife798.ui.component.EmptyStateText
import com.github.ilife798.ui.component.LOAD_MORE_THRESHOLD_PX
import com.github.ilife798.ui.component.ListLoadMoreFooter
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.component.SectionHeader
import com.github.ilife798.ui.theme.primaryButtonColors
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import com.github.ilife798.util.formatDateTime
import com.github.ilife798.util.formatMoney
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.menu.OverlayDropdownMenu
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class BillPanel { Records, Recharge, Refund }

private data class BillStatusOption(
    val status: Int,
    val label: String,
)

private val billStatusOptions =
    listOf(
        BillStatusOption(1, "未付款"),
        BillStatusOption(3, "已付款"),
        BillStatusOption(2, "待确认"),
        BillStatusOption(4, "付款失败"),
        BillStatusOption(9, "已取消"),
    )

@Composable
fun BillPage(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onRecordClick: (String) -> Unit = {},
) {
    val state = viewModel.state
    val wallets = state.wallets
    val isLoggedIn = state.account.hasAnyToken
    val activeWallet = wallets.firstOrNull { it.id == state.activeWalletId } ?: wallets.firstOrNull()
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(state.appBlur)
    val scrollState = rememberScrollState()
    var panel by remember { mutableStateOf(BillPanel.Records) }
    val scope = rememberCoroutineScope()
    var selectedProductId by remember { mutableStateOf<String?>(null) }
    var showRefundDialog by remember { mutableStateOf(false) }

    val billStatusEntry =
        remember(viewModel.billStatus) {
            DropdownEntry(
                items =
                    billStatusOptions.map { option ->
                        DropdownItem(
                            text = option.label,
                            selected = option.status == viewModel.billStatus,
                            onClick = { viewModel.selectBillStatus(option.status) },
                        )
                    },
            )
        }

    LaunchedEffect(Unit) {
        if (state.account.hasAnyToken) {
            viewModel.refreshBillPage()
        }
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val max = scrollState.maxValue
            max > 0 && scrollState.value >= max - LOAD_MORE_THRESHOLD_PX
        }
    }
    LaunchedEffect(shouldLoadMore, state.billRecords.size) {
        if (shouldLoadMore && panel == BillPanel.Records) viewModel.loadMoreBills()
    }
    LaunchedEffect(panel) {
        scrollState.animateScrollTo(0)
        if (panel == BillPanel.Recharge) {
            selectedProductId = null
            viewModel.loadRechargeProducts()
        }
    }
    LaunchedEffect(viewModel.billStatus) {
        scrollState.animateScrollTo(0)
    }

    Scaffold(
        topBar = {
            BlurredTopAppBar(
                title = "我的账单",
                blurBackdrop = blurBackdrop,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                actions = {
                    OverlayIconDropdownMenu(entry = billStatusEntry) {
                        Icon(
                            imageVector = MiuixIcons.More,
                            contentDescription = "选择账单类型",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        AppPullToRefresh(viewModel.billRefreshing, { viewModel.refreshBills() }, scrollBehavior, paddingValues) {
            PageScrollColumn(
                blurBackdrop = blurBackdrop,
                scrollBehavior = scrollBehavior,
                contentPadding = paddingValues,
                scrollState = scrollState,
            ) {
                WalletHeaderCard(
                    wallets = wallets,
                    activeWallet = activeWallet,
                    panel = panel,
                    isLoggedIn = isLoggedIn,
                    onSelectWallet = { viewModel.selectWallet(it) },
                    onToggleRecharge = {
                        panel = if (panel == BillPanel.Recharge) BillPanel.Records else BillPanel.Recharge
                    },
                    onToggleRefund = {
                        panel = if (panel == BillPanel.Refund) BillPanel.Records else BillPanel.Refund
                    },
                )

                AnimatedContent(
                    targetState = panel,
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 10 }) togetherWith
                            (fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 10 })
                    },
                    label = "billPanel",
                ) { target ->
                    when (target) {
                        BillPanel.Records -> {
                            BillRecordsCard(
                                records = state.billRecords,
                                loadingMore = viewModel.billLoadingMore,
                                hasMore = viewModel.billHasMore,
                                isLoggedIn = isLoggedIn,
                                onRecordClick = onRecordClick,
                            )
                        }

                        BillPanel.Recharge -> {
                            RechargeCard(
                                products = viewModel.rechargeProducts,
                                loading = viewModel.rechargeLoading,
                                paying = viewModel.rechargePaying,
                                chargeEnabled = activeWallet?.chargeEnabled ?: true,
                                selectedProductId = selectedProductId,
                                dynamicColor = state.dynamicColor,
                                isLoggedIn = isLoggedIn,
                                onSelectProduct = { selectedProductId = it },
                                onRecharge = {
                                    val product =
                                        viewModel.rechargeProducts
                                            .firstOrNull { it.id == selectedProductId }
                                    if (product != null) {
                                        scope.launch {
                                            // 第一步只下单并查渠道，成功后由 pendingRecharge 弹出选择框
                                            val error = viewModel.startRecharge(product)
                                            if (!error.isNullOrEmpty()) showToast(error)
                                        }
                                    }
                                },
                            )
                        }

                        BillPanel.Refund -> {
                            RefundCard(
                                wallet = activeWallet,
                                refundProgress = viewModel.refundProgress,
                                paying = viewModel.refundSubmitting,
                                isLoggedIn = isLoggedIn,
                                onRefund = { showRefundDialog = true },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRefundDialog) {
        RefundConfirmDialog(
            wallet = activeWallet,
            appBlur = state.appBlur,
            paying = viewModel.refundSubmitting,
            onDismiss = { if (!viewModel.refundSubmitting) showRefundDialog = false },
            onConfirm = {
                scope.launch {
                    val result = viewModel.submitRefund()
                    if (result.success) {
                        showRefundDialog = false
                        showToast(result.message.ifEmpty { "退款申请已提交" })
                    } else if (result.message.isNotEmpty()) {
                        showToast(result.message)
                    }
                }
            },
        )
    }

    // 下单成功后弹出支付方式选择框，选定渠道才真正唤起收银台
    viewModel.pendingRecharge?.let { pending ->
        PayMethodPickerDialog(
            pending = pending,
            appBlur = state.appBlur,
            paying = viewModel.rechargePaying,
            onDismiss = { viewModel.dismissRecharge() },
            onPick = { payType ->
                scope.launch {
                    val result = viewModel.payRecharge(payType)
                    if (result.message.isNotEmpty()) showToast(result.message)
                }
            },
        )
    }
}

@Composable
private fun PayMethodPickerDialog(
    pending: PendingRecharge,
    appBlur: Boolean,
    paying: Boolean,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    var selectedType by remember(pending.orderId) {
        mutableStateOf(pending.channels.firstOrNull()?.type ?: 0)
    }
    ConfirmDialog(
        title = "选择支付方式",
        appBlur = appBlur,
        confirmText = if (paying) "支付中…" else "确认支付",
        cancelEnabled = !paying,
        confirmEnabled = !paying && selectedType != 0,
        onConfirm = { if (selectedType != 0) onPick(selectedType) },
        onDismiss = onDismiss,
    ) {
        Text(
            text = "应付 ¥${formatMoney(pending.amount)}",
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(12.dp))
        // 渠道数量少，直接纵向排列，选中项用主色描边标示
        pending.channels.forEach { channel ->
            val selected = channel.type == selectedType
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                onClick = { if (!paying) selectedType = channel.type },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = channel.name,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(
                            imageVector = MiuixIcons.Ok,
                            contentDescription = "已选择",
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RefundConfirmDialog(
    wallet: WalletAccount?,
    appBlur: Boolean,
    paying: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        title = "确认退款",
        appBlur = appBlur,
        confirmText = if (paying) "提交中…" else "确认退款",
        cancelEnabled = !paying,
        confirmEnabled = !paying,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        Text(
            text = "可退金额 ¥${formatMoney(wallet?.refundable)}",
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "余额将退款至支付宝账号，赠送部分清零，提交后需商家审核。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun WalletHeaderCard(
    wallets: List<WalletAccount>,
    activeWallet: WalletAccount?,
    panel: BillPanel,
    isLoggedIn: Boolean,
    onSelectWallet: (String) -> Unit,
    onToggleRecharge: () -> Unit,
    onToggleRefund: () -> Unit,
) {
    val entries =
        remember(wallets, activeWallet?.id) {
            listOf(
                DropdownEntry(
                    items =
                        wallets.map { wallet ->
                            DropdownItem(
                                text = wallet.name.ifEmpty { "钱包" },
                                selected = wallet.id == activeWallet?.id,
                                onClick = { onSelectWallet(wallet.id) },
                            )
                        },
                ),
            )
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            OverlayDropdownMenu(
                entries = entries,
                title = activeWallet?.name?.ifEmpty { "钱包" } ?: "钱包",
                summary = if (wallets.size > 1) "点击切换钱包" else null,
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.BankCards,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                },
                enabled = wallets.isNotEmpty(),
            )
            HorizontalDivider()
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "总余额",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = if (isLoggedIn) "¥${formatMoney(activeWallet?.total)}" else "--",
                    style = MiuixTheme.textStyles.title1,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onToggleRecharge,
                        colors =
                            if (panel == BillPanel.Recharge) {
                                ButtonDefaults.buttonColorsPrimary()
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                    ) {
                        Text(text = "充值")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onToggleRefund,
                        colors =
                            if (panel == BillPanel.Refund) {
                                ButtonDefaults.buttonColorsPrimary()
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                    ) {
                        Text(text = "退款")
                    }
                }
            }
        }
    }
}

@Composable
private fun BillRecordsCard(
    records: List<BillRecord>,
    loadingMore: Boolean,
    hasMore: Boolean,
    isLoggedIn: Boolean,
    onRecordClick: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader("账单记录")
            if (records.isEmpty()) {
                EmptyStateText(isLoggedIn)
            } else {
                records.forEach { record ->
                    BillRecordRow(record, onRecordClick)
                }
                ListLoadMoreFooter(loadingMore = loadingMore, hasMore = hasMore)
            }
        }
    }
}

@Composable
private fun BillRecordRow(
    record: BillRecord,
    onRecordClick: (String) -> Unit,
) {
    val isRefund = record.dir == 2
    val amountColor = if (isRefund) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
    val statusColor = billStatusColor(record.status, record.dir)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onRecordClick(record.id) }
                .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.msg.ifEmpty { "账单记录" },
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = formatDateTime(record.time),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = billStatusName(record.status, record.dir),
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                )
                Text(
                    text = "·",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = paymentTypeName(record.type),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Text(
            text = (if (isRefund) "+¥" else "¥") + formatMoney(record.payment),
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Medium,
            color = amountColor,
        )
    }
}

@Composable
private fun RechargeCard(
    products: List<RechargeProduct>,
    loading: Boolean,
    paying: Boolean,
    chargeEnabled: Boolean,
    selectedProductId: String?,
    dynamicColor: Boolean,
    isLoggedIn: Boolean,
    onSelectProduct: (String) -> Unit,
    onRecharge: () -> Unit,
) {
    val selectedProduct = products.firstOrNull { it.id == selectedProductId }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader("充值金额")
            if (!isLoggedIn) {
                EmptyStateText(isLoggedIn = false)
            } else {
                when {
                    loading && products.isEmpty() -> {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            InfiniteProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                size = 20.dp,
                                strokeWidth = 2.dp,
                                orbitingDotSize = 3.dp,
                            )
                        }
                    }

                    products.isEmpty() -> {
                        Text(
                            text = "暂无可充值金额",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }

                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            products.chunked(3).forEach { rowProducts ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    rowProducts.forEach { product ->
                                        Button(
                                            modifier = Modifier.weight(1f),
                                            onClick = { onSelectProduct(product.id) },
                                            enabled = !paying && chargeEnabled,
                                            colors =
                                                if (product.id == selectedProductId) {
                                                    ButtonDefaults.buttonColorsPrimary()
                                                } else {
                                                    ButtonDefaults.buttonColors()
                                                },
                                        ) {
                                            Text(text = "¥${formatMoney(product.price)}")
                                        }
                                    }
                                    repeat(3 - rowProducts.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (!chargeEnabled) {
                    Text(
                        text = "该钱包暂不支持充值",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRecharge,
                    enabled = selectedProduct != null && !paying && chargeEnabled,
                    colors = primaryButtonColors(dynamicColor),
                ) {
                    Text(text = if (paying) "充值中…" else "立即充值")
                }
            }
        }
    }
}

@Composable
private fun RefundCard(
    wallet: WalletAccount?,
    refundProgress: RefundProgress?,
    paying: Boolean,
    isLoggedIn: Boolean,
    onRefund: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader("可退余额")
            if (!isLoggedIn) {
                EmptyStateText(isLoggedIn = false)
            } else {
                Text(
                    text = "¥${formatMoney(wallet?.refundable)}",
                    style = MiuixTheme.textStyles.title1,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.error,
                )
                Text(
                    text = "赠送余额不支持退款",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                BalanceBreakdown(wallet)
                refundProgress?.takeIf { it.active }?.let { progress ->
                    val statusText =
                        when (progress.fail) {
                            1 -> "退款失败"
                            0 -> "退款成功"
                            else -> "退款审核中"
                        }
                    val statusColor =
                        when (progress.fail) {
                            1 -> MiuixTheme.colorScheme.error
                            0 -> MiuixTheme.colorScheme.primary
                            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                        }
                    Text(
                        text = "$statusText：${progress.count}笔 ¥${formatMoney(progress.total)}元",
                        style = MiuixTheme.textStyles.body2,
                        color = statusColor,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRefund,
                    enabled = (wallet?.refundEnabled ?: true) && (wallet?.refundable ?: 0.0) > 0.0 && !paying,
                ) {
                    Text(text = if (paying) "提交中…" else "立即退款")
                }
            }
        }
    }
}

@Composable
private fun BalanceBreakdown(wallet: WalletAccount?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BalanceText(label = "线上余额", value = wallet?.olCash, modifier = Modifier.weight(1f))
            BalanceText(label = "线下余额", value = wallet?.ofCash, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BalanceText(label = "线上赠送", value = wallet?.olGift, modifier = Modifier.weight(1f))
            BalanceText(label = "线下赠送", value = wallet?.ofGift, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun BalanceText(
    label: String,
    value: Double?,
    modifier: Modifier,
) {
    Text(
        text = "$label：${formatMoney(value)}元",
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier,
    )
}

// 账单状态展示色（列表行与详情页共用）
@Composable
internal fun billStatusColor(
    status: Int,
    dir: Int,
): Color =
    when {
        dir == 2 -> if (status == 3) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error
        status == 3 -> MiuixTheme.colorScheme.primary
        status == 4 -> MiuixTheme.colorScheme.error
        status == 1 -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
