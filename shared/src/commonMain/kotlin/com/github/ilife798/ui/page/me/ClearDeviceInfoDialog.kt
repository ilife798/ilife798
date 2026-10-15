package com.github.ilife798.ui.page.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.github.ilife798.data.model.DeviceAlias
import com.github.ilife798.data.model.DeviceNote
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「清空设备自定义信息」选项对话框。
 *
 * 按「别名」「备注」两个分组展示本机保存的全部条目，默认折叠。每个叶子行对应
 * 一个设备上的一项数据，勾选后被底部「清空选中设备」收集。分组行本身也能勾选，
 * 含义是选中/取消该分组下所有叶子行（全选为勾选、部分为半选、全不选为未选）。
 *
 * 对话框只负责收集选择，不做任何删除：确认动作由上层执行，删除后由上层再次
 * 弹出二次确认，避免误触。
 */
@Composable
fun ClearDeviceInfoDialog(
    aliases: List<DeviceAlias>,
    notes: List<DeviceNote>,
    appBlur: Boolean,
    onDismiss: () -> Unit,
    onClearSelected: (noteDeviceIds: List<String>, aliasDeviceIds: List<String>) -> Unit,
) {
    // 勾选状态按「分组 + 设备 ID」存放，避免别名与备注在同一设备上互相干扰
    var checkedAliases by remember { mutableStateOf(emptySet<String>()) }
    var checkedNotes by remember { mutableStateOf(emptySet<String>()) }
    var aliasesExpanded by remember { mutableStateOf(false) }
    var notesExpanded by remember { mutableStateOf(false) }

    val totalSelected = checkedAliases.size + checkedNotes.size

    WindowDialog(
        show = true,
        title = "清空设备自定义信息",
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "选择要清空的内容，仅影响本机保存的数据。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .overScrollVertical()
                            .verticalScroll(rememberScrollState()),
                ) {
                    DeviceGroupSection(
                        title = "别名",
                        entries = aliases.map { it.deviceId to it.text },
                        emptyHint = "本机没有保存任何别名",
                        checked = checkedAliases,
                        expanded = aliasesExpanded,
                        onToggleExpand = { aliasesExpanded = !aliasesExpanded },
                        onCheckedChange = { checkedAliases = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    DeviceGroupSection(
                        title = "备注",
                        entries = notes.map { it.deviceId to it.text },
                        emptyHint = "本机没有保存任何备注",
                        checked = checkedNotes,
                        expanded = notesExpanded,
                        onToggleExpand = { notesExpanded = !notesExpanded },
                        onCheckedChange = { checkedNotes = it },
                    )
                }

                Spacer(Modifier.height(12.dp))
                ClearActionRow(
                    enabled = totalSelected > 0,
                    totalSelected = totalSelected,
                    onClick = {
                        onClearSelected(checkedNotes.toList(), checkedAliases.toList())
                    },
                )
            }
        },
    )
}

/**
 * 单个分组：可折叠标题行 + 叶子选项行。
 *
 * 标题行的复选框是三态：全选中显示勾选，部分选中显示半选，全不选显示未选。
 * 点击标题行文字区域只切换折叠，点击复选框只切换全选，两者互不干扰。
 */
@Composable
private fun DeviceGroupSection(
    title: String,
    entries: List<Pair<String, String>>,
    emptyHint: String,
    checked: Set<String>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onCheckedChange: (Set<String>) -> Unit,
) {
    val allIds = entries.map { it.first }
    val selectedCount = allIds.count { it in checked }
    val groupState =
        when {
            entries.isEmpty() || selectedCount == 0 -> ToggleableState.Off
            selectedCount == allIds.size -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleExpand)
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = "${title}列表",
                style = MiuixTheme.textStyles.title4,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$selectedCount/${allIds.size}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.size(8.dp))
            Checkbox(
                state = groupState,
                onClick = {
                    onCheckedChange(
                        if (groupState == ToggleableState.On) {
                            emptySet()
                        } else {
                            allIds.toSet()
                        },
                    )
                },
                enabled = entries.isNotEmpty(),
            )
        }

        if (expanded) {
            if (entries.isEmpty()) {
                Text(
                    text = emptyHint,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp),
                )
            } else {
                entries.forEach { (deviceId, text) ->
                    LeafOptionRow(
                        title = deviceId,
                        summary = text,
                        checked = deviceId in checked,
                        onCheckedChange = { nowChecked ->
                            onCheckedChange(
                                if (nowChecked) checked + deviceId else checked - deviceId,
                            )
                        },
                    )
                }
            }
        }
    }
}

/** 叶子选项行：设备 ID 作标题、别名或备注内容作副标题，右侧复选框。 */
@Composable
private fun LeafOptionRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onCheckedChange(!checked) }
                .padding(start = 24.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MiuixTheme.textStyles.body1)
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.size(8.dp))
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = { onCheckedChange(!checked) },
        )
    }
}

/** 底部「清空选中设备」操作行，未选中任何条目时禁用。 */
@Composable
private fun ClearActionRow(
    enabled: Boolean,
    totalSelected: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (enabled) {
                        MiuixTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MiuixTheme.colorScheme.surfaceContainer
                    },
                ).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (enabled) "清空选中设备（$totalSelected）" else "清空选中设备",
            style = MiuixTheme.textStyles.button,
            color =
                if (enabled) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
        )
    }
}
