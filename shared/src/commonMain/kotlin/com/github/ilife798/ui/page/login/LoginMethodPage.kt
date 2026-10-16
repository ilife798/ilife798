package com.github.ilife798.ui.page.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 登录方式选择页。承接账号管理页的「登录其它账号」，把登录入口按方式拆开，
 * 具体流程交给 [LoginPage]（手机号）与 [TokenLoginPage]（账户凭证）。
 */
@Composable
fun LoginMethodPage(
    viewModel: AppViewModel,
    onPhoneLogin: () -> Unit,
    onCredentialLogin: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(viewModel.state.appBlur)

    Scaffold(
        topBar = { BlurredTopAppBar("登录方式", blurBackdrop, scrollBehavior, onBack = onBack) },
    ) { paddingValues ->
        PageScrollColumn(blurBackdrop, scrollBehavior, paddingValues) {
            SmallTitle(text = "选择登录方式", insideMargin = PaddingValues(16.dp, 8.dp, 16.dp, 0.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = "手机号登录",
                        summary = "通过短信验证码登录设备控制",
                        onClick = onPhoneLogin,
                    )
                    ArrowPreference(
                        title = "账户凭证",
                        summary = "自行填写已有登录信息，适合无法接收短信时使用",
                        onClick = onCredentialLogin,
                    )
                }
            }
        }
    }
}
