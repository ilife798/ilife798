package com.github.ilife798.ui.page.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.ilife798.data.model.ImportPreview
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.readTextFromClipboard
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.page.device.QrScannerPage
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import com.github.ilife798.util.rememberTextFilePicker
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「导入备份」方式选择页：文件 / 剪贴板 / 扫码，三条路径最终都汇聚到同一个预览页。
 */
@Composable
fun BackupImportSourcePage(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onImportReady: (ImportPreview) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(viewModel.state.appBlur)
    // 文件选择器必须在组合期注册（同 BackupPage 的说明），不能放到点击回调里
    val filePicker = rememberTextFilePicker()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    fun handle(raw: String?) {
        if (raw.isNullOrBlank()) {
            errorMessage = "剪贴板为空"
            return
        }
        val preview = viewModel.buildImportPreview(raw)
        when {
            preview == null -> errorMessage = "非备份内容"
            preview.isEmpty() -> errorMessage = "备份里没有可导入的内容"
            else -> onImportReady(preview)
        }
    }

    if (scanning) {
        QrScannerPage(
            onBack = { scanning = false },
            onResult = { raw ->
                scanning = false
                handle(raw)
            },
        )
        return
    }

    Scaffold(
        topBar = { BlurredTopAppBar("导入备份", blurBackdrop, scrollBehavior, onBack = onBack) },
    ) { paddingValues ->
        PageScrollColumn(
            blurBackdrop = blurBackdrop,
            scrollBehavior = scrollBehavior,
            contentPadding = paddingValues,
        ) {
            SmallTitle(text = "从文件导入", insideMargin = PaddingValues(4.dp, 8.dp, 4.dp, 0.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            errorMessage = null
                            filePicker.pick { raw ->
                                // 取消（null）不提示，避免用户点返回就收到一条无意义错误
                                if (raw != null) handle(raw)
                            }
                        },
                        text = "选择文件",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }

            SmallTitle(text = "从剪贴板导入", insideMargin = PaddingValues(4.dp, 12.dp, 4.dp, 0.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            errorMessage = null
                            handle(readTextFromClipboard())
                        },
                        text = "读取剪贴板",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }

            SmallTitle(text = "扫码导入", insideMargin = PaddingValues(4.dp, 12.dp, 4.dp, 0.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            errorMessage = null
                            scanning = true
                        },
                        text = "开始扫码",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }

            errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
