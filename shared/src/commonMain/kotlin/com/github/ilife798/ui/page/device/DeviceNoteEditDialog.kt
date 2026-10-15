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
import com.github.ilife798.data.model.DEVICE_NOTE_MAX_LENGTH
import com.github.ilife798.data.model.codePointCount
import com.github.ilife798.data.model.truncateToCodePoints
import com.github.ilife798.ui.component.ConfirmDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备备注编辑弹窗。
 *
 * 输入内容按码点限长 DEVICE_NOTE_MAX_LENGTH，超出部分不进入输入框，
 * 避免用户看到已被截断却仍可继续输入的状态。确认时回调原始文本，
 * 由上层做归一化与持久化。
 */
@Composable
fun DeviceNoteEditDialog(
    deviceId: String,
    initialText: String,
    appBlur: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(deviceId) { mutableStateOf(initialText) }

    ConfirmDialog(
        title = "设备备注",
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
                text = input.truncateToCodePoints(DEVICE_NOTE_MAX_LENGTH)
            },
            modifier = Modifier.fillMaxWidth(),
            label = "备注名称",
            singleLine = true,
        )
        Spacer(Modifier.height(6.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "留空则清除备注 · ${text.codePointCount()}/$DEVICE_NOTE_MAX_LENGTH",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
