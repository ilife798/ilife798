package com.github.ilife798.ui.page.device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.ilife798.data.model.DEVICE_ALIAS_MAX_LENGTH
import com.github.ilife798.ui.component.ConfirmDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备别名编辑弹窗。
 *
 * 别名只影响本机显示，不会修改服务端设备名。输入按码点限长
 * DEVICE_ALIAS_MAX_LENGTH，超出部分不进入输入框。确认时回调原始文本，
 * 由上层做归一化与持久化；留空即删除别名。
 */
@Composable
fun DeviceAliasEditDialog(
    deviceId: String,
    initialText: String,
    appBlur: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(deviceId) { mutableStateOf(initialText) }

    ConfirmDialog(
        title = "设备别名",
        appBlur = appBlur,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(text) },
        confirmText = "保存",
    ) {
        Text(
            text = "设备编号 $deviceId",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(12.dp))
        TextField(
            value = text,
            onValueChange = { input ->
                // 按码点限长，保证 emoji 不被截断，也不允许超长输入
                val codePoints = input.codePoints().toArray()
                text =
                    if (codePoints.size <= DEVICE_ALIAS_MAX_LENGTH) {
                        input
                    } else {
                        String(codePoints, 0, DEVICE_ALIAS_MAX_LENGTH)
                    }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "别名",
            singleLine = true,
        )
        Spacer(Modifier.height(6.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "留空则清除别名 · ${text.codePoints().toArray().size}/$DEVICE_ALIAS_MAX_LENGTH",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
