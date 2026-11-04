package com.github.ilife798

import androidx.compose.ui.window.ComposeUIViewController
import com.github.ilife798.buildConfig.IosBuildConfig
import com.github.ilife798.data.api.ApiConfig
import platform.UIKit.UIViewController

// iOS 平台入口：初始化序列对齐 MainActivity.onCreate。
// DeviceTile.controller 不设置——commonMain 注释已定义「未设置 = 不支持」语义。
// 命名遵循 CMP 官方模板入口约定（Swift 侧经 MainViewControllerKt 调用）。
@Suppress("ktlint:standard:function-naming")
fun MainViewController(): UIViewController {
    AppStorage.instance = PersistentStorage()
    ApiConfig.init(
        gateway = IosBuildConfig.API_GATEWAY,
        salt = IosBuildConfig.SIGN_SALT,
        clientId = IosBuildConfig.API_CID,
        // 小程序通道（1,5）的独立盐，缺省空串；未注入时积分通道的 score-send 必然验签失败
        miniSalt = IosBuildConfig.MINI_SIGN_SALT,
    )
    return ComposeUIViewController { App() }
}
