package com.github.ilife798.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.ilife798.ui.theme.appBarBlur
import com.github.ilife798.ui.theme.blurAppBarColor
import com.github.ilife798.ui.theme.captureForBlur
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

val DefaultRefreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新…", "刷新完成")

// 毛玻璃顶部栏，可选返回按钮与右侧操作。
@Composable
fun BlurredTopAppBar(
    title: String,
    blurBackdrop: LayerBackdrop?,
    scrollBehavior: ScrollBehavior,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = title,
        modifier = Modifier.appBarBlur(blurBackdrop),
        color = blurAppBarColor(blurBackdrop),
        scrollBehavior = scrollBehavior,
        titlePadding = 28.dp,
        navigationIconPadding = 20.dp,
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                }
            }
        },
        actions = actions,
    )
}

// 页面内容列：毛玻璃、滚动手势与统一内边距。
@Composable
fun PageScrollColumn(
    blurBackdrop: LayerBackdrop?,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    topPadding: Dp = 8.dp,
    bottomPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .captureForBlur(blurBackdrop)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .scrollEndHaptic()
                .overScrollVertical()
                .verticalScroll(scrollState)
                .padding(contentPadding)
                .padding(horizontal = 12.dp)
                .padding(top = topPadding, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

// 统一样式的下拉刷新容器。
@Composable
fun AppPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    PullToRefresh(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        topAppBarScrollBehavior = scrollBehavior,
        contentPadding = contentPadding,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        refreshTexts = DefaultRefreshTexts,
        content = content,
    )
}
