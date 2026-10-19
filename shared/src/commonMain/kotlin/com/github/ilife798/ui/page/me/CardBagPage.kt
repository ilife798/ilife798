package com.github.ilife798.ui.page.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.ilife798.copyToClipboard
import com.github.ilife798.data.model.CloudCard
import com.github.ilife798.data.model.needsActivation
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.showToast
import com.github.ilife798.ui.component.AppPullToRefresh
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.ConfirmDialog
import com.github.ilife798.ui.component.EmptyStateText
import com.github.ilife798.ui.component.InfoRow
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.component.SectionHeader
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import com.github.ilife798.util.CardQrParser
import com.github.ilife798.util.currentTimeMillis
import com.github.ilife798.util.formatDateTime
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 我的卡包。展示账号内已绑定的云卡，支持绑定、挂失（解绑）与自动激活。
 * 绑定可扫码或手输卡号；部分卡/商户另需「绑卡密钥」，由服务端在首提时按需索取。
 */
@Composable
fun CardBagPage(
    viewModel: AppViewModel,
    onScannerRequest: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(viewModel.state.appBlur)
    val cards = viewModel.cards
    var pendingUnbind by remember { mutableStateOf<CloudCard?>(null) }
    var pendingEdit by remember { mutableStateOf<CloudCard?>(null) }

    // 绑定流程：scannedCardId 非空表示卡号来自扫码，空串表示手动输入
    var showBind by remember { mutableStateOf(false) }
    var scannedCardId by remember { mutableStateOf("") }
    var bindSubmitting by remember { mutableStateOf(false) }

    // 扫码页返回后，从 ViewModel 取回原文并解析出卡号；解析失败则回到手输模式
    val scannedRaw = viewModel.scannedCardRaw
    LaunchedEffect(scannedRaw) {
        if (scannedRaw.isEmpty()) return@LaunchedEffect
        scannedCardId = CardQrParser.parse(scannedRaw).cardNo
        if (scannedCardId.isEmpty()) {
            showToast("无法识别该二维码，请手动输入卡号")
        }
        showBind = true
        viewModel.clearScannedCardRaw()
    }

    // 服务端要求绑卡密钥时，控制器会置位 pendingCardKeyId；此时补开绑定对话框
    val pendingKeyId = viewModel.pendingCardKeyId
    LaunchedEffect(pendingKeyId) {
        if (pendingKeyId.isNotEmpty()) {
            scannedCardId = pendingKeyId
            showBind = true
        }
    }

    // 进入页面即加载；云卡的 12 小时激活在控制器内静默完成
    LaunchedEffect(Unit) {
        viewModel.loadCards()
    }

    Scaffold(
        topBar = {
            BlurredTopAppBar("我的卡包", blurBackdrop, scrollBehavior, onBack = onBack)
        },
    ) { paddingValues ->
        AppPullToRefresh(
            isRefreshing = viewModel.cardsLoading,
            onRefresh = { viewModel.loadCards(force = true) },
            scrollBehavior = scrollBehavior,
            contentPadding = paddingValues,
        ) {
            PageScrollColumn(blurBackdrop, scrollBehavior, paddingValues) {
                when {
                    cards.isEmpty() -> {
                        CardEmptyState(
                            loaded = viewModel.cardsLoaded,
                            loading = viewModel.cardsLoading,
                            onBindScan = {
                                scannedCardId = ""
                                showBind = true
                            },
                        )
                    }

                    else -> {
                        cards.forEach { card ->
                            CloudCardItem(
                                card = card,
                                onActivate = { viewModel.activateCard(card.id) },
                                onEdit = { pendingEdit = card },
                                onUnbind = { pendingUnbind = card },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scannedCardId = ""
                                showBind = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("绑定云卡")
                        }
                    }
                }
            }
        }
    }

    if (showBind) {
        CardBindDialog(
            appBlur = viewModel.state.appBlur,
            scannedCardId = scannedCardId,
            keyRequired = pendingKeyId.isNotEmpty(),
            submitting = bindSubmitting,
            onScanClick = {
                // 对话框是本页内的 Compose 覆盖层，不归导航栈管；
                // 不先关掉就会压在扫码页上面，用户看不到扫码界面。
                showBind = false
                bindSubmitting = false
                onScannerRequest()
            },
            onDismiss = {
                showBind = false
                bindSubmitting = false
                // 放弃补填校验码：清掉待补状态，否则下次为其它卡打开对话框会误显示提示
                viewModel.clearPendingCardKey()
            },
            onConfirm = { cardId, cardKey, name ->
                bindSubmitting = true
                viewModel.bindCard(
                    cardId = cardId,
                    cardKey = cardKey,
                    name = name,
                    onSuccess = { showBind = false },
                    // 无论成功、需要密钥还是失败都要复位，否则 -15 之后按钮永久禁用
                    onSettled = { bindSubmitting = false },
                )
            },
        )
    }

    pendingEdit?.let { card ->
        CardEditDialog(
            appBlur = viewModel.state.appBlur,
            card = card,
            onDismiss = { pendingEdit = null },
            onSubmit = { name, limit ->
                pendingEdit = null
                viewModel.updateCard(
                    cardId = card.id,
                    name = name,
                    limit = limit,
                    otherEp = card.otherEp,
                )
            },
        )
    }

    pendingUnbind?.let { card ->
        ConfirmDialog(
            title = "解绑云卡",
            appBlur = viewModel.state.appBlur,
            message = "解绑后该卡将无法在本机使用，重新使用需再次绑定云卡。确定解绑「${card.displayName}」吗？",
            confirmText = "解绑",
            destructive = true,
            onDismiss = { pendingUnbind = null },
            onConfirm = {
                viewModel.unbindCard(card.id)
                pendingUnbind = null
            },
        )
    }
}

@Composable
private fun CardEmptyState(
    loaded: Boolean,
    loading: Boolean,
    onBindScan: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyStateText(
                isLoggedIn = true,
                emptyText =
                    when {
                        loading -> "正在加载…"
                        loaded -> "还没有绑定云卡"
                        else -> "卡包加载失败，下拉重试"
                    },
            )
            if (!loading && loaded) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBindScan, modifier = Modifier.fillMaxWidth()) {
                    Text("绑定云卡")
                }
            }
        }
    }
}

@Composable
private fun CloudCardItem(
    card: CloudCard,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onUnbind: () -> Unit,
) {
    val now = currentTimeMillis()
    val needsActivation = card.needsActivation(now)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        // 标题用持卡人（卡名称）：holder.name 优先，
                        // 缺失时退回用户自定义卡名，再退回发卡商，最后才是 id
                        text = card.cardTitle,
                        style = MiuixTheme.textStyles.title4,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 卡号作标题副行，默认遮挡；点一下复制完整卡号
                    if (card.id.isNotEmpty()) {
                        Text(
                            text = maskCardId(card.id),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier =
                                Modifier.clickable {
                                    copyToClipboard(card.id, "已复制卡号")
                                },
                        )
                    }
                }
                Text(
                    text = if (needsActivation) "待激活" else "已激活",
                    style = MiuixTheme.textStyles.footnote1,
                    color =
                        if (needsActivation) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                )
            }
            if (card.enterpriseName.isNotEmpty()) {
                InfoRow(label = "发卡商", value = card.enterpriseName)
            }
            if (card.enterpriseAbbr.isNotEmpty()) {
                InfoRow(label = "发卡商简称", value = card.enterpriseAbbr)
            }
            InfoRow(label = "状态", value = if (needsActivation) "待激活" else "已激活")
            // 额度恒显示：为 0 时给出占位，避免「未设置」被误认为不可编辑
            InfoRow(
                label = "每日额度",
                value = if (card.limit > 0.0) "¥${formatLimit(card.limit)}/天" else "",
                placeholder = "不限制",
            )
            InfoRow(
                label = "适用范围",
                value = if (card.otherEp > 0) "全国设备" else "仅发卡商设备",
            )
            InfoRow(label = "上次激活", value = formatDateTime(card.activatedAt))
            InfoRow(label = "激活有效期", value = formatDateTime(card.validTime))
            Spacer(Modifier.height(12.dp))
            if (needsActivation) {
                Button(
                    onClick = onActivate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("激活")
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surfaceVariant),
                ) {
                    Text("编辑")
                }
                Button(
                    onClick = onUnbind,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surfaceVariant),
                ) {
                    Text("解绑")
                }
            }
        }
    }
}

// 卡号遮挡：保留前 1 位与后 2 位，中间用 * 填充；过短则整串遮挡
internal fun maskCardId(id: String): String =
    when {
        id.length <= 3 -> id.map { '*' }.joinToString("")
        else -> id.first() + "*".repeat(id.length - 3) + id.takeLast(2)
    }

// 额度去掉多余小数：整数不带 .0，非整数保留两位
private fun formatLimit(value: Double): String =
    if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        ((value * 100).toLong() / 100.0).toString()
    }
