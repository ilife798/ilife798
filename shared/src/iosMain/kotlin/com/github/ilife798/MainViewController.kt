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
    /*
     * 平台盐（2026-11-06 实测）：
     *   1,1 通道 → iOS 专属盐 IOS_SIGN_SALT（服务端按 UA 平台选盐；
     *              误用 Android 盐时登录返回「验签失败」，dtype 也会标成 1）
     *   1,5 通道 → 与 Android 相同的 MINI_SIGN_SALT（小程序是同一份支付宝 JS，盐跨平台通用；
     *              实测 iOS UA + mini 盐登录成功，dtype=2 + stype=5）
     * 未注入 IOS_SIGN_SALT 时回退 SIGN_SALT，保持与旧构建一致的行为。
     */
    val iosSalt = IosBuildConfig.IOS_SIGN_SALT.ifBlank { IosBuildConfig.SIGN_SALT }
    ApiConfig.init(
        gateway = IosBuildConfig.API_GATEWAY,
        salt = iosSalt,
        clientId = IosBuildConfig.API_CID,
        miniSalt = IosBuildConfig.MINI_SIGN_SALT,
    )
    return ComposeUIViewController { App() }
}
