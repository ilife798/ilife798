package com.github.ilife798.ui.page.me

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.ilife798.data.model.CloudCard
import com.github.ilife798.ui.component.ConfirmDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 编辑云卡：卡名与每日额度。
 *
 * 两个字段都对应官方 ic/upt 的 CardBindParm：name 是卡片备注名，limit 是每日消费额度。
 * 依据官方 CardContentHolder 的展示格式「%.2f元/天」，limit 是按天计的额度；
 * 留空或填 0 表示不限制，与 CloudCard.limit 的约定一致。
 *
 * @param card 待编辑的云卡，初始值取自它。
 * @param onSubmit 提交回调，参数为规范化后的卡名与额度。
 */
@Composable
fun CardEditDialog(
    appBlur: Boolean,
    card: CloudCard,
    onDismiss: () -> Unit,
    onSubmit: (name: String, limit: Double) -> Unit,
) {
    var name by remember(card.id) {
        // 预填服务端下发的持卡人（卡名称）：用户改的就是 holder.name，
        // 存回后标题读的也是它。若为空再退回展示名，避免编辑框空白。
        mutableStateOf(card.holderName.ifEmpty { card.displayName })
    }
    var limitText by remember(card.id) {
        mutableStateOf(if (card.limit > 0.0) trimLimit(card.limit) else "")
    }

    val trimmedLimit = limitText.trim()
    val parsedLimit = trimmedLimit.toDoubleOrNull()
    val limitValid = trimmedLimit.isEmpty() || (parsedLimit != null && parsedLimit >= 0.0)

    ConfirmDialog(
        title = "编辑云卡",
        appBlur = appBlur,
        confirmText = "保存",
        confirmEnabled = limitValid,
        onDismiss = onDismiss,
        onConfirm = { onSubmit(name.trim(), parsedLimit ?: 0.0) },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = "卡名",
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            TextField(
                value = limitText,
                onValueChange = { limitText = it },
                modifier = Modifier.fillMaxWidth(),
                label = "每日额度",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            if (!limitValid) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "请输入有效的每日额度，留空表示不限制。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

// 额度回填用：整数不带 .0，非整数保留两位，与列表展示保持一致
private fun trimLimit(value: Double): String =
    if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        ((value * 100).toLong() / 100.0).toString()
    }
