package com.github.ilife798.ui.page.backup

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.github.ilife798.data.model.BackupGroup
import com.github.ilife798.data.model.BackupGroupState
import com.github.ilife798.data.model.BackupOption
import com.github.ilife798.data.model.BackupSelection
import com.github.ilife798.data.model.ImportPreview
import com.github.ilife798.data.model.encodeBackup
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import com.github.ilife798.util.BACKUP_MIME_TYPE
import com.github.ilife798.util.rememberTextFilePicker
import com.github.ilife798.util.rememberTextFileSaver
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「备份数据」页面。
 *
 * 按分组列出本机可备份的数据，勾选后导出为 JSON 文件。落盘位置由系统文件选择器决定，
 * 应用自身不申请存储权限。
 *
 * 只覆盖无法从服务端重新拉取的数据（账号凭据、设备别名与备注）；设备列表、积分、钱包
 * 等恢复时重新请求即可，故不在此列出。
 */
@Composable
fun BackupPage(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onImportReady: (ImportPreview) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(viewModel.state.appBlur)
    // 必须在此注册文件选择器：Android 的 ActivityResultRegistry 要求注册发生在
    // Activity 进入 STARTED 之前，从点击回调里注册会抛 IllegalStateException。
    val fileSaver = rememberTextFileSaver()
    val filePicker = rememberTextFilePicker()
    // 分组数据进入页面时构建一次；数据量很小，不值得做增量更新
    val groups = remember { viewModel.backupGroups() }
    var selection by remember { mutableStateOf(BackupSelection.EMPTY) }
    var exportedFileName by remember { mutableStateOf<String?>(null) }
    var importedError by remember { mutableStateOf<String?>(null) }
    val selectedCount = selection.selectedCount(groups)

    Scaffold(
        topBar = { BlurredTopAppBar("备份数据", blurBackdrop, scrollBehavior, onBack = onBack) },
    ) { paddingValues ->
        PageScrollColumn(
            blurBackdrop = blurBackdrop,
            scrollBehavior = scrollBehavior,
            contentPadding = paddingValues,
        ) {
            Text(
                text = "选择要备份的内容，导出为文件后可自行保存到网盘或其它设备。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )

            groups.forEach { group ->
                BackupGroupCard(
                    group = group,
                    selection = selection,
                    onToggleGroup = { selection = selection.toggleGroup(group) },
                    onToggleOption = { option -> selection = selection.toggleOption(group, option) },
                )
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val doc = viewModel.buildBackup(groups, selection.selectedLeafKeys(groups))
                    val fileName = "ilife798-backup-${doc.createdAt}.json"
                    exportedFileName = null
                    fileSaver.save(fileName, encodeBackup(doc)) { saved ->
                        exportedFileName = if (saved) fileName else null
                    }
                },
                text = if (selectedCount > 0) "导出备份（$selectedCount）" else "导出备份",
                enabled = selectedCount > 0,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )

            exportedFileName?.let { name ->
                Text(
                    text = "已保存 $name",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    importedError = null
                    filePicker.pick { raw ->
                        // 取消（null）不提示，避免用户每次点返回都收到一条无意义的错误
                        if (raw == null) return@pick
                        val preview = viewModel.buildImportPreview(raw)
                        when {
                            preview == null -> importedError = "无法读取该文件，请确认是本应用导出的备份"
                            preview.isEmpty() -> importedError = "备份文件中没有任何可导入的内容"
                            else -> onImportReady(preview)
                        }
                    }
                },
                text = "导入备份",
            )

            importedError?.let { message ->
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 一个备份分组：标题行带三态全选复选框，下面逐条列出可勾选内容。 */
@Composable
private fun BackupGroupCard(
    group: BackupGroup,
    selection: BackupSelection,
    onToggleGroup: () -> Unit,
    onToggleOption: (BackupOption) -> Unit,
) {
    SmallTitle(text = group.title, insideMargin = PaddingValues(4.dp, 8.dp, 4.dp, 0.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = group.options.isNotEmpty(), onClick = onToggleGroup)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "${group.title}列表", style = MiuixTheme.textStyles.body1)
                    Text(
                        text = "${selection.selectedCount(listOf(group))}/${group.options.size}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Checkbox(
                    state = selection.groupState(group).toToggleableState(),
                    onClick = onToggleGroup,
                    enabled = group.options.isNotEmpty(),
                )
            }

            if (group.options.isEmpty()) {
                Text(
                    text = group.emptyHint,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                )
            } else {
                group.options.forEach { option ->
                    BackupOptionRow(
                        option = option,
                        checked = selection.isChecked(group, option),
                        onCheckedChange = { onToggleOption(option) },
                    )
                }
            }
        }
    }
}

/** 单条可勾选内容：左侧标题与说明，右侧复选框。整行可点。 */
@Composable
private fun BackupOptionRow(
    option: BackupOption,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCheckedChange)
                .padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = option.title, style = MiuixTheme.textStyles.body1)
            if (option.summary.isNotEmpty()) {
                Text(
                    text = option.summary,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = onCheckedChange,
        )
    }
}

private fun BackupGroupState.toToggleableState(): ToggleableState =
    when (this) {
        BackupGroupState.None -> ToggleableState.Off
        BackupGroupState.Partial -> ToggleableState.Indeterminate
        BackupGroupState.All -> ToggleableState.On
    }
