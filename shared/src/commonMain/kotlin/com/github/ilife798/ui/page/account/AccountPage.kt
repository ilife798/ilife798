package com.github.ilife798.ui.page.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.ilife798.copyToClipboard
import com.github.ilife798.data.model.Account
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.ConfirmDialog
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class CredentialField(
    val key: String,
    val title: String,
) {
    APP_TOKEN("appToken", "设备控制"),
    TOKEN("token", "积分任务"),
    UID("uid", "用户 ID"),
}

private fun valueOf(
    account: Account,
    field: CredentialField,
): String =
    when (field) {
        CredentialField.APP_TOKEN -> account.appToken
        CredentialField.TOKEN -> account.token
        CredentialField.UID -> account.uid
    }

private fun maskCredential(value: String): String {
    if (value.length <= 8) return value
    return value.take(4) + "*".repeat(value.length - 8) + value.takeLast(4)
}

@Composable
fun AccountPage(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onLoginClick: (isAlipay: Boolean) -> Unit = {},
) {
    val state = viewModel.state
    val account = state.account
    val accounts = state.accountList
    val currentIndex = state.currentAccountIndex
    val developerMode = viewModel.developerMode
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(state.appBlur)

    var editingField by remember { mutableStateOf<CredentialField?>(null) }
    var inputValue by remember { mutableStateOf("") }

    // 进入页面时校验其它账号有效性，过期的自动移除
    LaunchedEffect(Unit) {
        viewModel.validateAccounts()
    }

    Scaffold(
        topBar = { BlurredTopAppBar("账号管理", blurBackdrop, scrollBehavior, onBack = onBack) },
    ) { paddingValues ->
        PageScrollColumn(blurBackdrop, scrollBehavior, paddingValues) {
            if (accounts.isNotEmpty()) {
                SmallTitle(text = "账号列表", insideMargin = PaddingValues(16.dp, 8.dp, 16.dp, 0.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        accounts.forEachIndexed { index, entry ->
                            val isCurrent = index == currentIndex
                            val acc = if (isCurrent) account else entry.account
                            val info =
                                if (isCurrent) {
                                    state.accountInfo
                                        .takeIf { it.name.isNotEmpty() || it.img.isNotEmpty() || it.pn.isNotEmpty() }
                                        ?: entry.info
                                } else {
                                    entry.info
                                }
                            RadioButtonPreference(
                                title = info.name.ifEmpty { acc.phone }.ifEmpty { "-" },
                                summary = info.pn.ifEmpty { acc.phone }.ifEmpty { "-" },
                                selected = isCurrent,
                                onClick = { viewModel.switchAccount(index) },
                                radioButtonLocation = RadioButtonLocation.End,
                                startAction = { AccountAvatar(info.img, 40.dp) },
                            )
                        }
                    }
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onLoginClick(false) },
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Import,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "登录其它账号")
                }
            }

            if (developerMode) {
                SmallTitle(text = "账号凭据", insideMargin = PaddingValues(16.dp, 8.dp, 16.dp, 0.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        CredentialField.entries.forEach { field ->
                            val value = valueOf(account, field)
                            val display = if (value.isEmpty()) "-" else maskCredential(value)
                            BasicComponent(
                                title = field.title,
                                summary = "${field.key}: $display",
                                onClick = {
                                    when {
                                        value.isNotEmpty() -> {
                                            copyToClipboard(value, "已复制 ${field.key}")
                                        }

                                        field != CredentialField.UID -> {
                                            inputValue = ""
                                            editingField = field
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                            contentColor = MiuixTheme.colorScheme.onSurface,
                        ),
                ) {
                    Text(
                        text = "设备控制/积分任务为空时点击输入，有值时点击复制，用户 ID 有值时点击复制。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    val currentEditing = editingField
    if (currentEditing != null) {
        ConfirmDialog(
            title = "填写 ${currentEditing.key}",
            appBlur = state.appBlur,
            onDismiss = { editingField = null },
            onConfirm = {
                val input = inputValue.trim()
                if (input.isNotEmpty()) viewModel.updateAccountField(currentEditing.key, input)
                editingField = null
            },
        ) {
            TextField(
                value = inputValue,
                onValueChange = { inputValue = it },
                modifier = Modifier.fillMaxWidth(),
                label = currentEditing.key,
            )
        }
    }
}

@Composable
private fun AccountAvatar(
    img: String,
    size: Dp,
) {
    if (img.isNotEmpty()) {
        AsyncImage(
            model = img,
            contentDescription = null,
            modifier =
                Modifier
                    .size(size)
                    .clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier =
                Modifier
                    .size(size)
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
}
