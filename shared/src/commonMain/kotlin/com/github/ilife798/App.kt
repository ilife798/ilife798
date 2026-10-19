package com.github.ilife798

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.pay.setWeChatFallbackAppId
import com.github.ilife798.ui.component.DisclaimerDialog
import com.github.ilife798.ui.component.SponsorDialog
import com.github.ilife798.ui.navigation.MainScaffold
import com.github.ilife798.ui.theme.AppTheme

@Composable
fun App(weChatFallbackAppId: String = "") {
    // 仅 Android 写一次；iOS 无微信 SDK，忽略该值。
    remember(weChatFallbackAppId) { setWeChatFallbackAppId(weChatFallbackAppId) }
    val viewModel = viewModel { AppViewModel() }
    val state = viewModel.state
    AppTheme(
        themeMode = state.themeMode,
        customColor = state.customColor,
        dynamicColor = state.dynamicColor,
        paletteStyle = state.paletteStyle,
        seedColor = Color(state.seedColor),
    ) {
        MainScaffold(viewModel = viewModel)

        // 首次启动时弹出免责声明，确认后持久化不再弹出
        if (!state.disclaimerAcknowledged) {
            DisclaimerDialog(
                appBlur = state.appBlur,
                onDismiss = { viewModel.acknowledgeDisclaimer() },
            )
        }

        // 启动次数达标后的赞助提示
        if (viewModel.sponsorPromptCount > 0) {
            SponsorDialog(
                appBlur = state.appBlur,
                message = "您已启动应用 ${viewModel.sponsorPromptCount} 次了，考虑赞助支持一下吗？",
                onDismiss = { viewModel.dismissSponsor() },
                onNeverRemind = { viewModel.neverRemindSponsor() },
                onSponsor = { viewModel.openSponsor() },
            )
        }
    }
}
