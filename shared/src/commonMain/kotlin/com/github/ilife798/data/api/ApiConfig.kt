package com.github.ilife798.data.api

import com.github.ilife798.isIOS

// ApplicationType
const val APP_TYPE_DEVICE = "1,1" // 普通请求的固定值
const val APP_TYPE_POINTS = "1,5" // 积分登录及小程序积分请求渠道

/**
 * 签名盐只从构建期密钥注入，仓库内不留明文。
 *
 * 待签名原文 = adId + 时间桶 + token 后 8 位 + uid 后 8 位 + 盐，取 md5；
 * 时间桶 = 30 * (nowMs / 30000)，即 30 秒粒度。
 *
 * ★ 盐已确认（2026-11-05 实测，签到 +5 分真实到账）：
 *   本地 secrets.properties 的 SIGN_SALT 填 AbCdEfGhIjKlMnOpQrStUvWxYz12345 即可。
 *
 * ★ 版本号必须与 ApplicationType 严格配对，否则即使盐正确也会判验签失败：
 *   ApplicationType "1,1"（App）      → VersionCode "3.1.9"
 *   ApplicationType "1,5"（小程序）   → 登录走 2.0.83，业务走 2.0.178
 *
 * ★ acc/login 会校验 sign（2026-11-05/06 实测）：
 *   1,5 通道不带 sign →「请升级最新版应用」，带正确的 mini 盐 sign → 成功（stype:5）。
 *
 * ★ 平台盐矩阵（2026-11-06 实测，服务端按 UA 前缀识别平台）：
 *
 *           1,1 通道（App）      1,5 通道（小程序）
 *   Android  SIGN_SALT          MINI_SIGN_SALT
 *   iOS      IOS_SIGN_SALT      MINI_SIGN_SALT（与 Android 同一个，支付宝 JS 跨平台通用）
 *
 *   iOS 误用 Android 盐走 1,1 时登录返回「验签失败」，al.dtype 会标成 1；
 *   用对盐后 al.dtype = 2。iOS 通过 IOS_SIGN_SALT 独立注入（见 MainViewController）。
 */
object ApiConfig {
    var baseUrl: String = ""
    var signSalt: String = ""
    var cid: String = ""

    // 小程序通道（ApplicationType "1,5"）的独立盐，与 App 通道不可混用。
    // 未注入时回落空串，此时小程序通道的 score-send 必然验签失败。
    var miniSignSalt: String = ""

    // 调试开关：开启后 HttpClient 会把请求/响应全文打到 logcat（tag ILIFE798-API），仅 debug 包使用
    var debugLogging: Boolean = false
    const val VERSION_CODE: String = "3.1.9"

    // 小程序通道有两个版本号，用途不同，不可混用：
    //   登录（acc/login）走 MINI_LOGIN_VERSION = "2.0.83"
    //   业务接口（mission-lst / acc-score / score-send）走 MINI_VERSION = "2.0.178"
    // 取值来自下游 dong-dong6/ilife798 v1.3.15（实测可用）。
    // 交叉搭配（比如业务接口发 2.0.83）会被服务端判「验签失败」。
    const val MINI_LOGIN_VERSION: String = "2.0.83"
    const val MINI_VERSION: String = "2.0.178"

    // 旧名保留为别名，避免外部引用失效
    const val VERSION_CODE_MINI: String = MINI_LOGIN_VERSION

    val USER_AGENT: String = "${if (isIOS) "iOS" else "Android"}_ilife798_$VERSION_CODE"

    // 网络超时：连接 10s，单次请求 30s，读空闲 30s。
    // 服务端接口均为轻量 JSON，30s 足够；弱网下比引擎默认值更早失败，配合 withNetworkRetry 重试。
    const val CONNECT_TIMEOUT_MS: Long = 10_000
    const val REQUEST_TIMEOUT_MS: Long = 30_000
    const val SOCKET_TIMEOUT_MS: Long = 30_000

    fun init(
        gateway: String,
        salt: String,
        clientId: String,
        miniSalt: String = "",
    ) {
        baseUrl = gateway
        signSalt = salt
        cid = clientId
        miniSignSalt = miniSalt
    }

    // 盐只来自构建期注入；缺失时为空白，签名不可用（不再静默回落到内置值）
    val salt: String
        get() = signSalt

    val miniSalt: String
        get() = miniSignSalt
}
