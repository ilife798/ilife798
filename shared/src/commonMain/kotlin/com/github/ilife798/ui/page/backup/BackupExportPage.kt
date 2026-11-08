package com.github.ilife798.ui.page.backup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.ilife798.copyToClipboard
import com.github.ilife798.data.model.BackupDocument
import com.github.ilife798.data.model.encodeBackup
import com.github.ilife798.data.model.encodeBackupCompact
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import com.github.ilife798.util.BACKUP_QR_MAX_CHARS
import com.github.ilife798.util.rememberQrCodeBitmap
import com.github.ilife798.util.rememberTextFileSaver
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「导出备份」方式选择页：同一份备份内容提供三种带出方式。
 *
 * - 文件：走系统另存为，适合长期保存与网盘同步（保留缩进，便于人工查看）
 * - 剪贴板：单行 JSON，适合通过聊天工具直接发给另一台设备（体积最小）
 * - 二维码：直接展示，另一台设备扫码导入；内容超过二维码容量时提示改用前两种
 */
@Composable
fun BackupExportPage(
    doc: BackupDocument,
    blurBackdropEnabled: Boolean,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(blurBackdropEnabled)
    // 与 BackupPage 相同：选择器必须在组合期注册，点击回调里注册会抛 Lifecycle 异常
    val fileSaver = rememberTextFileSaver()
    val prettyJson = remember(doc) { encodeBackup(doc) }
    val compactJson = remember(doc) { encodeBackupCompact(doc) }
    var exportedFileName by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { BlurredTopAppBar("导出备份", blurBackdrop, scrollBehavior, onBack = onBack) },
    ) { paddingValues ->
        PageScrollColumn(
            blurBackdrop = blurBackdrop,
            scrollBehavior = scrollBehavior,
            contentPadding = paddingValues,
        ) {
            SmallTitle(text = "保存为文件", insideMargin = PaddingValues(4.dp, 8.dp, 4.dp, 0.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val fileName = "ilife798-backup-${doc.createdAt}.json"
                            exportedFileName = null
                            fileSaver.save(fileName, prettyJson) { saved ->
                                exportedFileName = if (saved) fileName else null
                            }
                        },
                        text = "保存文件",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    exportedFileName?.let { name ->
                        Text(
                            text = "已保存 $name",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            SmallTitle(text = "复制到剪贴板", insideMargin = PaddingValues(4.dp, 12.dp, 4.dp, 0.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { copyToClipboard(compactJson, "已复制到剪贴板") },
                        text = "复制到剪贴板",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }

            SmallTitle(text = "显示二维码", insideMargin = PaddingValues(4.dp, 12.dp, 4.dp, 0.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!showQr) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showQr = true },
                            text = "生成二维码",
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    } else if (compactJson.length > BACKUP_QR_MAX_CHARS) {
                        Text(
                            text = "备份内容过长，此方法不可用。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        val qrBitmap = rememberQrCodeBitmap(compactJson)
                        if (qrBitmap == null) {
                            Text(
                                text = "二维码生成失败",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "备份二维码",
                                modifier = Modifier.size(260.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "在另一台设备扫码导入",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
