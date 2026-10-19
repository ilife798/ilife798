package com.github.ilife798.pay

data class AlipayPayResult(
    val success: Boolean,
    val message: String,
)

expect suspend fun payWithAlipay(orderInfo: String): AlipayPayResult

/**
 * 一卡通（`PaymentType.THIRD` = 93）支付。
 *
 * Android 走支付宝 SDK 的 `OpenAuthTask` + `BizType.Deduct`「免密代扣授权」，
 * [signParams] 是 `trans/prepay/93` 下发的 `data`（需 URL 解码后交给 SDK）。
 * iOS 端未集成，返回不可用。
 */
expect suspend fun payWithOneCard(signParams: String): AlipayPayResult

/**
 * 微信（`PaymentType.WECHAT_APP` = 11）支付。
 *
 * [payInfo] 是 `trans/prepay/11` 下发的 `data` 对象（字段 `appid` / `partnerid` /
 * `prepayid` / `noncestr` / `timestamp` / `package` / `sign`），需原样转成 JSON 串传入。
 * iOS 端未集成，返回不可用。
 */
expect suspend fun payWithWeChat(payInfo: String): AlipayPayResult

/**
 * 微信 App 支付在本端是否可用。
 *
 * 微信只允许开放平台登记过的「包名 + 签名」调起收银台，本端 applicationId 与官方登记不符，
 * 因此恒为 false，界面据此把微信画成灰条并追加「（第三方不可用）」。
 * 将来去微信开放平台登记了同名包名与签名后，把各平台实现改为 true 即可。
 */
expect fun isWeChatPayAvailable(): Boolean

/**
 * 注入微信兜底 appId（Android 侧取自编译期常量 `BuildConfig.WECHAT_FALLBACK_APP_ID`）。
 *
 * 该值属于第三方应用凭据，仓库内不留明文：本地写 `secrets.properties`，
 * CI 用同名 Secret 注入环境变量。为空时完全依赖服务端下发的 `PayInfo.appid`。
 */
expect fun setWeChatFallbackAppId(appId: String)
