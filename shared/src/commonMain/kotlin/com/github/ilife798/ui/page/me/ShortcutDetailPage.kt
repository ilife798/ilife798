package com.github.ilife798.ui.page.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.github.ilife798.copyToClipboard
import com.github.ilife798.data.model.ShortcutAction
import com.github.ilife798.data.model.ShortcutParam
import com.github.ilife798.data.model.buildShortcutUrl
import com.github.ilife798.data.model.isSatisfied
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.component.SwitchPreference
import com.github.ilife798.ui.theme.primaryButtonColors
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 预选卡片的候选项：[value] 写进链接参数，[label] 是主文案，[secondary] 是次要说明（如编号）。 */
data class ShortcutOption(
    val value: String,
    val label: String,
    val secondary: String = "",
)

// 参数的预选来源。
private enum class PickerKind {
    Device,
    Account,
    Uids,
}

// 设备/账号类参数改用预选卡片从应用当前配置里选；添加设备的编号仍手动填写。
private fun pickerKindOf(
    action: ShortcutAction,
    key: String,
): PickerKind? =
    when {
        key == "id" && action != ShortcutAction.DeviceAdd -> PickerKind.Device
        key == "account" && action == ShortcutAction.SwitchAccount -> PickerKind.Account
        key == "uids" && action == ShortcutAction.RunBatch -> PickerKind.Uids
        else -> null
    }

private fun optionsOf(
    kind: PickerKind,
    deviceOptions: List<ShortcutOption>,
    accountOptions: List<ShortcutOption>,
    uidOptions: List<ShortcutOption>,
): List<ShortcutOption> =
    when (kind) {
        PickerKind.Device -> deviceOptions
        PickerKind.Account -> accountOptions
        PickerKind.Uids -> uidOptions
    }

/**
 * 「URL Scheme」动作详情页。
 *
 * 设备/账号类参数用预选卡片从应用当前配置里点选回填；支持 bg 的动作可切「后台执行」。
 * 最终链接实时生成，确认无误后可以复制链接，或直接打开——打开等价于立即在本应用执行该动作。
 */
@Composable
fun ShortcutDetailPage(
    action: ShortcutAction,
    scheme: String,
    deviceOptions: List<ShortcutOption> = emptyList(),
    accountOptions: List<ShortcutOption> = emptyList(),
    uidOptions: List<ShortcutOption> = emptyList(),
    onBack: () -> Unit,
    appBlur: Boolean,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(appBlur)
    val uriHandler = LocalUriHandler.current
    var values by remember(action) { mutableStateOf(action.params.associate { it.key to "" }) }
    var bg by remember(action) { mutableStateOf(action.bgDefault == true) }
    val url = buildShortcutUrl(scheme, action, values + ("bg" to bg.toString()))
    val valid = action.isSatisfied(values)

    Scaffold(
        topBar = { BlurredTopAppBar(action.title, blurBackdrop, scrollBehavior, onBack = onBack) },
    ) { paddingValues ->
        PageScrollColumn(
            blurBackdrop = blurBackdrop,
            scrollBehavior = scrollBehavior,
            contentPadding = paddingValues,
        ) {
            SmallTitle(text = "链接模板", insideMargin = PaddingValues(4.dp, 8.dp, 4.dp, 0.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = action.path,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }

            if (action.params.isNotEmpty()) {
                SmallTitle(text = "参数", insideMargin = PaddingValues(4.dp, 8.dp, 4.dp, 0.dp))

                val freeParams =
                    action.params.filter { param ->
                        val kind = pickerKindOf(action, param.key)
                        kind == null || optionsOf(kind, deviceOptions, accountOptions, uidOptions).isEmpty()
                    }
                if (freeParams.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            freeParams.forEachIndexed { index, param ->
                                if (index > 0) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                                TextField(
                                    value = values[param.key].orEmpty(),
                                    onValueChange = { values = values + (param.key to it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = param.label + if (param.required) "" else "（可留空）",
                                )
                                if (param.placeholder.isNotBlank()) {
                                    Text(
                                        text = param.placeholder,
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                action.params.forEach { param ->
                    val kind = pickerKindOf(action, param.key) ?: return@forEach
                    val options = optionsOf(kind, deviceOptions, accountOptions, uidOptions)
                    if (options.isEmpty()) return@forEach
                    PickCard(
                        param = param,
                        options = options,
                        multi = kind == PickerKind.Uids,
                        selected = values[param.key].orEmpty(),
                        onSelect = { values = values + (param.key to it) },
                    )
                }
            }

            if (action.bgDefault != null) {
                SmallTitle(text = "执行方式", insideMargin = PaddingValues(4.dp, 8.dp, 4.dp, 0.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = if (action == ShortcutAction.AccountAddToken) "静默导入" else "后台执行",
                        checked = bg,
                        onCheckedChange = { bg = it },
                    )
                }
            }

            SmallTitle(text = "最终链接", insideMargin = PaddingValues(4.dp, 8.dp, 4.dp, 0.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (valid) url else "填写完整参数后显示最终链接",
                        style = MiuixTheme.textStyles.body1,
                        color =
                            if (valid) {
                                MiuixTheme.colorScheme.onSurface
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { copyToClipboard(url, "已复制链接") },
                            enabled = valid,
                            colors = primaryButtonColors(),
                        ) {
                            Text(text = "复制")
                        }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { uriHandler.openUri(url) },
                            enabled = valid,
                            colors = primaryButtonColors(),
                        ) {
                            Text(text = "打开")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// 预选卡片：从应用当前配置里点选回填参数；uids 多选，其余单选，再点一次取消。
@Composable
private fun PickCard(
    param: ShortcutParam,
    options: List<ShortcutOption>,
    multi: Boolean,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val chosen = selected.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text = param.label,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            options.forEach { option ->
                val isChosen = option.value in chosen
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (multi) {
                                    val next = if (isChosen) chosen - option.value else chosen + option.value
                                    onSelect(next.joinToString(","))
                                } else {
                                    onSelect(if (isChosen) "" else option.value)
                                }
                            }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        if (option.secondary.isNotBlank()) {
                            Text(
                                text = option.secondary,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    if (isChosen) {
                        Text(
                            text = "✓",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
