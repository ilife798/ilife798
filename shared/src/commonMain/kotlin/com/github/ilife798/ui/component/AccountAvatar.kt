package com.github.ilife798.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 账号头像：有远程图片则显示图片，否则用联系人图标兜底。
@Composable
fun AccountAvatar(
    img: String,
    size: Dp,
) {
    if (img.isNotEmpty()) {
        AsyncImage(
            model = img,
            contentDescription = null,
            modifier =
                Modifier
                    .size(size)
                    .clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Contacts,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}
