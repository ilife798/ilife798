package com.github.ilife798.ui.page.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.ilife798.ui.theme.WindowBlurEffect
import com.github.ilife798.util.SPONSOR_URL
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「赞助支持」入口选项对话框。
 */
@Composable
fun SponsorSupportDialog(
    appBlur: Boolean,
    onDismiss: () -> Unit,
    onSelect: (SponsorTarget) -> Unit,
) {
    WindowDialog(
        show = true,
        title = "赞助支持",
        onDismissRequest = onDismiss,
        content = {
            WindowBlurEffect(useBlur = appBlur)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "",
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
                    sponsorTargets.forEach { target ->
                        SponsorTargetRow(target = target, onClick = { onSelect(target) })
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        },
    )
}

// 单个赞助对象：标题 + 说明，整行可点。
@Composable
private fun SponsorTargetRow(
    target: SponsorTarget,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = target.title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
            Text(
                text = target.summary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * 一个可赞助对象。
 *
 * @param title 展示名，例如「Jursin」。
 * @param summary 一句话说明，例如「爱发电」。
 * @param url 爱发电主页地址。
 */
data class SponsorTarget(
    val title: String,
    val summary: String,
    val url: String,
)

// 赞助对象列表，顺序即展示顺序。
val sponsorTargets =
    listOf(
        SponsorTarget(
            title = "Jursin",
            summary = "爱发电",
            url = SPONSOR_URL,
        ),
    )
