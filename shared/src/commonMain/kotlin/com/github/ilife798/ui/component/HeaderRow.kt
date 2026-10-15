package com.github.ilife798.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HeaderRow(
    title: String,
    trailing: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MiuixTheme.textStyles.title3,
    id: String = "",
    onIdClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier),
                )
                Row(modifier = Modifier.padding(start = 4.dp)) { trailing() }
            }
            if (id.isNotEmpty()) {
                Text(
                    text = id,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier =
                        Modifier
                            .padding(top = 2.dp)
                            .then(if (onIdClick != null) Modifier.clickable(onClick = onIdClick) else Modifier),
                )
            }
        }
    }
}
