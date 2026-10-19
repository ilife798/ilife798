package com.github.ilife798.ui.page.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.ilife798.ui.component.ConfirmDialog
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 绑定云卡对话框。
 *
 * 表单固定三行：卡名 → 卡号 → 校验码。
 * 卡号一行右侧始终有扫码入口；扫码回填后卡号固定显示，不再给编辑框，
 * 避免用户误改后对不上实体卡。
 *
 * 「校验码」对应官方 CardBindParm.token。官方流程是首提 ic/bind 被 -15 驳回后
 * 才索取该字段，本对话框改为常显但允许留空：不填先按原两段式走，填了则一并提交。
 * 详见 CardController.bindCard。
 *
 * @param scannedCardId 扫码得到的卡号；为空时卡号行显示输入框供手动填写。
 * @param keyRequired 服务端已要求提供校验码（上次提交被 -15 驳回）。
 * @param submitting 正在提交，禁用确认按钮避免重复请求。
 * @param onScanClick 请求进入扫码页；为 null 时不显示扫码入口。
 */
@Composable
fun CardBindDialog(
    appBlur: Boolean,
    scannedCardId: String,
    keyRequired: Boolean,
    submitting: Boolean,
    onScanClick: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (cardId: String, cardKey: String, name: String) -> Unit,
) {
    var manualCardId by remember { mutableStateOf("") }
    var cardName by remember { mutableStateOf("") }
    var cardKey by remember { mutableStateOf("") }

    val cardId = scannedCardId.ifEmpty { manualCardId }
    val canConfirm = cardId.isNotBlank() && !submitting

    ConfirmDialog(
        title = "绑定云卡",
        appBlur = appBlur,
        confirmText = "绑定",
        confirmEnabled = canConfirm,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(cardId.trim(), cardKey.trim(), cardName.trim()) },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 第一行：卡名
            TextField(
                value = cardName,
                onValueChange = { cardName = it },
                modifier = Modifier.fillMaxWidth(),
                label = "卡名",
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))

            // 第二行：卡号，右侧固定扫码入口
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (scannedCardId.isNotEmpty()) {
                    Text(
                        text = "卡号：$scannedCardId",
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        style = MiuixTheme.textStyles.body2,
                    )
                } else {
                    TextField(
                        value = manualCardId,
                        onValueChange = { manualCardId = it },
                        modifier = Modifier.weight(1f),
                        label = "卡号",
                        singleLine = true,
                    )
                }
                if (onScanClick != null) {
                    IconButton(onClick = onScanClick) {
                        Icon(
                            imageVector = MiuixIcons.Scan,
                            contentDescription = "扫码填写卡号",
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 第三行：校验码，常显、可留空
            TextField(
                value = cardKey,
                onValueChange = { cardKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = "校验码（可选）",
                singleLine = true,
            )

            if (keyRequired) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "该卡需要校验码，请按卡面或发卡方提示填写。",
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
    }
}
