package com.github.ilife798.wxapi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.github.ilife798.pay.WeChatCallbackRelay
import com.github.ilife798.pay.WeChatPayGateway
import com.github.ilife798.pay.handleIntentQuietly
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler

/**
 * 微信支付回调入口。
 *
 * 微信 SDK 会按 `<applicationId>.wxapi.WXPayEntryActivity` 的固定路径拉起本页面，
 * 因此类名与包名都不能改（本工程 applicationId 为 `com.github.ilife798`）。
 * 支付结果经 [WeChatPayGateway] 交回等待中的协程，随后立即关闭本页面。
 */
class WXPayEntryActivity :
    Activity(),
    IWXAPIEventHandler {
    private val relay = WeChatCallbackRelay()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dispatch(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            dispatch(intent)
        }
    }

    private fun dispatch(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }
        val api = WeChatPayGateway.takeApi()
        // handleIntent 返回 false 说明这不是微信支付回调，直接退出避免白屏
        val consumed = api?.handleIntentQuietly(intent, relay) ?: false
        if (!consumed) finish()
    }

    override fun onReq(req: com.tencent.mm.opensdk.modelbase.BaseReq?) = Unit

    override fun onResp(resp: com.tencent.mm.opensdk.modelbase.BaseResp?) {
        relay.onResp(resp)
        finish()
    }
}
