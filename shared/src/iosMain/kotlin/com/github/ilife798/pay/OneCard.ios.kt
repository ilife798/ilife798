package com.github.ilife798.pay

import com.github.ilife798.logDebug

// 一卡通（免密代扣授权）依赖支付宝 OpenAuthTask，iOS 端未集成。
// 服务端若下发了 93，界面上会命中本分支并给出明确提示，不会静默失败。
actual suspend fun payWithOneCard(signParams: String): AlipayPayResult {
    logDebug("OneCard", "ios: one-card payment not supported")
    return AlipayPayResult(success = false, message = "当前平台暂不支持一卡通支付")
}

// 微信支付依赖微信开放平台 App 支付，iOS 端未集成。
actual suspend fun payWithWeChat(payInfo: String): AlipayPayResult {
    logDebug("WeChat", "ios: wechat payment not supported")
    return AlipayPayResult(success = false, message = "当前平台暂不支持微信支付")
}

/** iOS 端未集成微信 SDK，恒不可用。 */
actual fun isWeChatPayAvailable(): Boolean = false

/** iOS 端无微信 SDK，兜底 appId 无意义，忽略。 */
actual fun setWeChatFallbackAppId(appId: String) = Unit
