package com.github.ilife798.ui.page.backup

import androidx.compose.foundation.background
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
import com.github.ilife798.data.model.ConflictChoice
import com.github.ilife798.data.model.ImportItem
import com.github.ilife798.data.model.ImportPreview
import com.github.ilife798.data.model.ImportSelection
import com.github.ilife798.data.model.mergeImport
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.ConfirmDialog
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
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
 * 「导入备份」页面。
 *
 * 展示备份文件与本机现状的对照，用户勾选要导入的条目；有冲突的条目展开为「保留本机 /
 * 用备份覆盖」二选一，逐条裁决。确认后合并写入——导入永远是合并，本机其它数据不受影响。
 *
 * 账号只区分「新增」与「本机已有」：账号凭据是一组，拆开或让用户裁决半边没有意义，旧备份
 * 覆盖还会把用户登出，因此已存在的账号一律不导入。
 */
@Composable
fun BackupImportPage(
    viewModel: AppViewModel,
    preview: ImportPreview,
    onBack: () -> Unit,
    onFinished: (Int) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(viewModel.state.appBlur)
    // 新增条目默认勾上；已是本机数据的默认不勾，避免用户误以为要重复写入
    var selection by remember(preview) {
        mutableStateOf(
            preview
                .allItems()
                .filter { it.isNew }
                .fold(ImportSelection.EMPTY) { acc, item -> acc.toggle(item) },
        )
    }
    var showConfirm by remember { mutableStateOf(false) }
    val checkedCount = selection.checkedCount(preview)

    Scaffold(
        topBar = { BlurredTopAppBar("导入备份", blurBackdrop, scrollBehavior, onBack = onBack) },
    ) { paddingValues ->
        PageScrollColumn(
            blurBackdrop = blurBackdrop,
            scrollBehavior = scrollBehavior,
            contentPadding = paddingValues,
        ) {
            Text(
                text = importHint(preview),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )

            if (preview.accounts.isNotEmpty()) {
                ImportSection(title = "账号") {
                    preview.accounts.forEach { item ->
                        ImportAccountRow(
                            item = item,
                            checked = selection.isChecked(item),
                            enabled = item.isNew,
                            onToggle = { selection = selection.toggle(item) },
                        )
                    }
                }
            }

            if (preview.aliases.isNotEmpty()) {
                ImportSection(title = "设备别名") {
                    preview.aliases.forEach { item ->
                        ImportConflictRow(
                            item = item,
                            checked = selection.isChecked(item),
                            resolution = selection.resolutionOf(item),
                            onToggle = { selection = selection.toggle(item) },
                            onResolve = { choice -> selection = selection.resolve(item, choice) },
                        )
                    }
                }
            }

            if (preview.notes.isNotEmpty()) {
                ImportSection(title = "设备备注") {
                    preview.notes.forEach { item ->
                        ImportConflictRow(
                            item = item,
                            checked = selection.isChecked(item),
                            resolution = selection.resolutionOf(item),
                            onToggle = { selection = selection.toggle(item) },
                            onResolve = { choice -> selection = selection.resolve(item, choice) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { showConfirm = true },
                text = if (checkedCount > 0) "导入选中项（$checkedCount）" else "导入选中项",
                enabled = checkedCount > 0,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showConfirm) {
        ConfirmDialog(
            title = "导入备份",
            appBlur = viewModel.state.appBlur,
            onDismiss = { showConfirm = false },
            onConfirm = {
                showConfirm = false
                val outcome = viewModel.applyImport(mergeImport(preview, selection))
                onFinished(outcome.total)
            },
            message = "将导入 $checkedCount 项内容，本机其它数据不受影响。",
            destructive = true,
        )
    }
}

/** 顶部说明：交代文件来源与冲突数量，让用户先知道要处理什么。 */
private fun importHint(preview: ImportPreview): String {
    val conflicts = preview.conflictCount
    val base = "备份共 ${preview.totalCount} 项，已勾选本机没有的内容。"
    return if (conflicts > 0) {
        "$base 其中 $conflicts 项与本机数据不同，请在下方逐条选择保留哪一份。"
    } else {
        base
    }
}

@Composable
private fun ImportSection(
    title: String,
    content: @Composable () -> Unit,
) {
    SmallTitle(text = title, insideMargin = PaddingValues(4.dp, 8.dp, 4.dp, 0.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

/**
 * 账号行。账号只有「新增」或「本机已有」两种状态，没有可裁决的冲突：备份里的凭据可能比
 * 本机旧，覆盖会让用户登出，故已存在的账号不可勾选。
 */
@Composable
private fun ImportAccountRow(
    item: ImportItem,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MiuixTheme.textStyles.body1,
                color =
                    if (enabled) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
            )
            Text(
                text = if (enabled) "新账号，将加入账号列表" else "本机已有该账号，跳过",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = onToggle,
            enabled = enabled,
        )
    }
}

/**
 * 设备别名/备注行。有冲突时展示本机值与备份值的对比，并给出「保留本机 / 用备份覆盖」两个
 * 选项；无冲突（本机为空）时只是一条普通的可勾选新增项。
 */
@Composable
private fun ImportConflictRow(
    item: ImportItem,
    checked: Boolean,
    resolution: ConflictChoice,
    onToggle: () -> Unit,
    onResolve: (ConflictChoice) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.title, style = MiuixTheme.textStyles.body1)
                Text(
                    text = if (item.isConflict) "与本机不同，请选择保留哪一份" else "本机没有，将新增",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Checkbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                onClick = onToggle,
            )
        }

        if (item.isConflict) {
            Spacer(Modifier.height(6.dp))
            CompareLine(label = "本机", value = item.currentValue, selected = resolution == ConflictChoice.Keep)
            Spacer(Modifier.height(4.dp))
            CompareLine(label = "备份", value = item.incomingValue, selected = resolution == ConflictChoice.Overwrite)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(
                    text = "保留本机",
                    selected = resolution == ConflictChoice.Keep,
                    onClick = { onResolve(ConflictChoice.Keep) },
                )
                ChoiceChip(
                    text = "用备份覆盖",
                    selected = resolution == ConflictChoice.Overwrite,
                    onClick = { onResolve(ConflictChoice.Overwrite) },
                )
            }
        }
    }
}

/** 冲突对比的一行：左侧标签固定宽度，右侧值，选中态用背景色区分。 */
@Composable
private fun CompareLine(
    label: String,
    value: String,
    selected: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (selected) {
                        MiuixTheme.colorScheme.primaryContainer
                    } else {
                        MiuixTheme.colorScheme.surfaceVariant
                    },
                ).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = value.ifEmpty { "（空）" },
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 冲突裁决的小按钮。 */
@Composable
private fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (selected) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.surfaceVariant
                    },
                ).clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color =
                if (selected) {
                    MiuixTheme.colorScheme.onPrimary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
        )
    }
}
