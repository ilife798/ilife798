package com.github.ilife798.ui.page.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.ConfirmDialog
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Create
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Remove
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MePage(
    viewModel: AppViewModel,
    onLoginClick: (isAlipay: Boolean) -> Unit = {},
    onScoreClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onBillClick: () -> Unit = {},
    onCardBagClick: () -> Unit = {},
    onLicenseClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    bottomPadding: Dp,
    wideScreen: Boolean,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(viewModel.state.appBlur)

    Scaffold(
        topBar = { BlurredTopAppBar("我的", blurBackdrop, scrollBehavior) },
    ) { paddingValues ->
        PageScrollColumn(
            blurBackdrop = blurBackdrop,
            scrollBehavior = scrollBehavior,
            contentPadding = paddingValues,
            topPadding = 4.dp,
            bottomPadding = bottomPadding,
        ) {
            AccountSection(viewModel, onLoginClick, onScoreClick, onAccountClick, onBillClick, onCardBagClick)
            SettingsSection(viewModel, onLicenseClick, onBackupClick, wideScreen)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AccountSection(
    viewModel: AppViewModel,
    onLoginClick: (isAlipay: Boolean) -> Unit,
    onScoreClick: () -> Unit,
    onAccountClick: () -> Unit,
    onBillClick: () -> Unit,
    onCardBagClick: () -> Unit,
) {
    val account = viewModel.state.account
    val accountInfo = viewModel.state.accountInfo
    val hasApp = account.appToken.isNotEmpty()
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }

    LaunchedEffect(hasApp) {
        if (hasApp) viewModel.loadAccountInfo()
    }

    SmallTitle(text = "账号", insideMargin = PaddingValues(16.dp, 8.dp, 16.dp, 0.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        // 列表非空 ⇒ 曾有账号（已登录必有条目），未登录且无账号时仅开发者模式可进
                        if (viewModel.developerMode || viewModel.state.accountList.isNotEmpty()) {
                            Modifier.clickable { onAccountClick() }
                        } else {
                            Modifier
                        },
                    ).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (accountInfo.img.isNotEmpty()) {
                AsyncImage(
                    model = accountInfo.img,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Contacts,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = accountInfo.name.ifEmpty { if (account.isLoggedIn) account.phone else "未登录" },
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                if (accountInfo.pn.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (showPhone) accountInfo.pn else maskPhoneMiddle4(accountInfo.pn),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        IconButton(
                            minHeight = 24.dp,
                            minWidth = 24.dp,
                            onClick = { showPhone = !showPhone },
                        ) {
                            Icon(
                                imageVector = if (showPhone) MiuixIcons.Hide else MiuixIcons.Show,
                                contentDescription = if (showPhone) "隐藏" else "显示",
                                modifier = Modifier.size(16.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
            if (hasApp) {
                IconButton(
                    minHeight = 35.dp,
                    minWidth = 35.dp,
                    backgroundColor = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                    onClick = { showLogoutDialog = true },
                ) {
                    Icon(
                        imageVector = MiuixIcons.Remove,
                        contentDescription = "退出登录",
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.7f else 0.9f),
                    )
                }
            } else {
                IconButton(
                    minHeight = 35.dp,
                    minWidth = 35.dp,
                    backgroundColor = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                    onClick = { onLoginClick(false) },
                ) {
                    Icon(
                        imageVector = MiuixIcons.Import,
                        contentDescription = "登录",
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.7f else 0.9f),
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onBillClick,
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Notes,
                        contentDescription = "账单",
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "账单")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onScoreClick,
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Create,
                        contentDescription = "积分",
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "积分")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onCardBagClick,
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Icon(
                        imageVector = MiuixIcons.BankCards,
                        contentDescription = "卡包",
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "卡包")
                }
            }
        }
    }

    if (showLogoutDialog) {
        ConfirmDialog(
            title = "退出登录",
            message = "确定要退出登录吗？",
            appBlur = viewModel.state.appBlur,
            confirmText = "退出",
            destructive = true,
            onConfirm = {
                viewModel.logout()
                showLogoutDialog = false
            },
            onDismiss = { showLogoutDialog = false },
        )
    }
}

private fun maskPhoneMiddle4(phone: String): String {
    if (phone.length < 7) return phone
    val start = (phone.length - 4) / 2
    return phone.substring(0, start) + "****" + phone.substring(start + 4)
}
