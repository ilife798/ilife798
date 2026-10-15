package com.github.ilife798.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun InfoRow(
    label: String,
    value: String,
    onValueClick: (() -> Unit)? = null,
    placeholder: String = "",
) {
    val showPlaceholder = value.isEmpty() && placeholder.isNotEmpty()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = if (showPlaceholder) placeholder else value,
            style = MiuixTheme.textStyles.body2,
            color =
                if (showPlaceholder) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            modifier =
                if (onValueClick != null) {
                    Modifier.clickable(onClick = onValueClick)
                } else {
                    Modifier
                },
        )
    }
}
