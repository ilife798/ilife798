package com.github.ilife798.ui.page.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.ilife798.data.model.SHORTCUT_SCHEMES
import com.github.ilife798.data.model.ShortcutAction
import com.github.ilife798.data.model.ShortcutCategory
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「URL Scheme」入口页。
 *
 * 上半是协议头选择（三种写法等价，默认 `com.github.ilife798`），下半按动作类型分组，
 * 点进任一项可以填写参数、看到最终链接，再决定复制还是直接打开。
 */
@Composable
fun ShortcutPage(
    scheme: String,
    onSchemeChange: (String) -> Unit,
    onActionClick: (ShortcutAction) -> Unit,
    onBack: () -> Unit,
    appBlur: Boolean,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(appBlur)
    val selectedIndex = SHORTCUT_SCHEMES.indexOf(scheme).coerceAtLeast(0)

    Scaffold(
        topBar = { BlurredTopAppBar("URL Scheme", blurBackdrop, scrollBehavior, onBack = onBack) },
    ) { paddingValues ->
        PageScrollColumn(
            blurBackdrop = blurBackdrop,
            scrollBehavior = scrollBehavior,
            contentPadding = paddingValues,
        ) {
            SmallTitle(text = "协议头", insideMargin = PaddingValues(4.dp, 8.dp, 4.dp, 0.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    OverlayDropdownPreference(
                        title = "选择协议头",
                        summary = "$scheme://",
                        items = SHORTCUT_SCHEMES.map { "$it://" },
                        selectedIndex = selectedIndex,
                        onSelectedIndexChange = { index ->
                            SHORTCUT_SCHEMES.getOrNull(index)?.let(onSchemeChange)
                        },
                    )
                }
            }

            ShortcutCategory.entries.forEach { category ->
                val actions = ShortcutAction.entries.filter { it.category == category }
                if (actions.isEmpty()) return@forEach
                SmallTitle(
                    text = category.title,
                    insideMargin = PaddingValues(4.dp, 8.dp, 4.dp, 0.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        actions.forEachIndexed { index, action ->
                            if (index > 0) {
                                Spacer(Modifier.height(1.dp))
                            }
                            ShortcutRow(action = action, onClick = { onActionClick(action) })
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ShortcutRow(
    action: ShortcutAction,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = action.title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = action.path,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Text(
            text = if (action.params.isEmpty()) "查看" else "填写参数",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}
