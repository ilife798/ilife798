package com.github.ilife798.ui.page.login

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.theme.primaryButtonColors
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 账户凭证登录页。用户直接粘贴已有登录信息，跳过短信验证码流程。
 *
 * 两个输入框对应 [com.github.ilife798.data.model.Account] 的两个独立凭据槽位：
 * 设备控制（appToken，必填）与积分任务（token，可选）。提交后由 ViewModel
 * 校验有效性并确认两条凭据属于同一账户。
 */
@Composable
fun TokenLoginPage(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    var deviceToken by remember { mutableStateOf("") }
    var pointsToken by remember { mutableStateOf("") }
    val isLoading = viewModel.isLoading
    val errorMessage = viewModel.errorMessage
    val dynamicColor = viewModel.state.dynamicColor
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(viewModel.state.appBlur)

    // 登录成功后账号会带上凭据，据此自动返回上一页，与手机号登录页行为一致
    val initialAccount = remember { viewModel.state.account }
    val loginDone =
        viewModel.state.account != initialAccount &&
            viewModel.state.account.appToken
                .isNotEmpty()
    LaunchedEffect(loginDone) {
        if (loginDone) onBack()
    }

    Scaffold(
        topBar = {
            BlurredTopAppBar(
                title = "账户凭证",
                blurBackdrop = blurBackdrop,
                scrollBehavior = scrollBehavior,
                onBack = {
                    viewModel.clearError()
                    onBack()
                },
            )
        },
    ) { paddingValues ->
        PageScrollColumn(
            blurBackdrop = blurBackdrop,
            scrollBehavior = scrollBehavior,
            contentPadding = paddingValues,
            modifier =
                Modifier.pointerInput(Unit) {
                    detectTapGestures {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                },
        ) {
            SmallTitle(text = "填写登录信息", insideMargin = PaddingValues(16.dp, 8.dp, 16.dp, 0.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = deviceToken,
                        onValueChange = { deviceToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "设备控制（必填）",
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TextField(
                        value = pointsToken,
                        onValueChange = { pointsToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "积分任务（可选）",
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
            ) {
                Text(
                    text = "登录信息即账号凭据，填写后由本机校验有效性并保存，不会上传到其它位置。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp),
                )
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                )
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.loginWithCredentials(deviceToken, pointsToken) },
                enabled = !isLoading && deviceToken.isNotBlank(),
                colors = primaryButtonColors(dynamicColor),
            ) {
                Text(text = "立即登录")
            }
        }
    }
}
