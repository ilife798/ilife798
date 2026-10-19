package com.github.ilife798.pay

import android.content.Intent
import com.github.ilife798.ActivityHolder
import com.github.ilife798.logDebug
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.modelpay.PayReq
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * 微信 App 支付（`PaymentType.WECHAT_APP` = 11）。
 *
 * 与官方 `PayHelper;->E(PayInfo)` 等价：把 `trans/prepay/11` 下发的 `PayInfo`（JSON 对象）
 * 逐字段搬进 [PayReq]，交给 [IWXAPI.sendReq]。
 *
 * **已知限制**：微信只允许「登记过的包名 + 签名」调起收银台。本端 applicationId 为
 * `com.github.ilife798` 且沿用调试证书，与官方登记不符，微信侧会拒绝；[payWithWeChat]
 * 会返回明确失败文案，界面据此把渠道灰显为「（第三方不可用）」。
 */
actual suspend fun payWithWeChat(payInfo: String): AlipayPayResult {
    if (payInfo.isEmpty()) {
        return AlipayPayResult(success = false, message = "支付失败：微信下单信息为空")
    }
    val activity =
        ActivityHolder.current
            ?: return AlipayPayResult(success = false, message = "支付失败：页面已关闭，请重试")

    val info =
        runCatching { Json.parseToJsonElement(payInfo) as? JsonObject }.getOrNull()
            ?: return AlipayPayResult(success = false, message = "支付失败：微信下单信息格式错误")

    fun str(key: String): String = runCatching { info[key]?.jsonPrimitive?.content }.getOrNull().orEmpty()

    val appId = str("appid").ifEmpty { weChatFallbackAppId.orEmpty() }
    if (appId.isEmpty()) {
        return AlipayPayResult(success = false, message = "支付失败：未配置微信 AppID")
    }
    val partnerId = str("partnerid")
    val prepayId = str("prepayid")
    if (partnerId.isEmpty() || prepayId.isEmpty()) {
        return AlipayPayResult(
            success = false,
            message = "支付失败：服务端未返回微信商户单号",
        )
    }

    val wxApi =
        runCatching { WXAPIFactory.createWXAPI(activity, appId, true) }.getOrNull()
            ?: return AlipayPayResult(success = false, message = "支付失败：微信 SDK 初始化失败")
    WeChatPayGateway.install(wxApi)

    if (!runCatching { wxApi.isWXAppInstalled() }.getOrDefault(false)) {
        return AlipayPayResult(success = false, message = "未安装微信")
    }

    val req =
        PayReq().apply {
            this.appId = appId
            this.partnerId = partnerId
            this.prepayId = prepayId
            this.nonceStr = str("noncestr")
            this.timeStamp = str("timestamp")
            this.packageValue = str("package").ifEmpty { "Sign=WXPay" }
            this.sign = str("sign")
            this.signType = "MD5"
        }

    if (WECHAT_PAY_UNAVAILABLE) {
        return AlipayPayResult(
            success = false,
            message = "微信支付不可用：第三方支付资质受限$WECHAT_UNAVAILABLE_HINT",
        )
    }

    logDebug("WeChat", "sendReq appId=$appId prepayId=$prepayId")
    return withContext(Dispatchers.Main) {
        val result =
            withTimeoutOrNull(WECHAT_TIMEOUT_MS) {
                suspendCancellableCoroutine<AlipayPayResult> { continuation ->
                    WeChatPayGateway.await(continuation)
                    val sent = runCatching { wxApi.sendReq(req) }.getOrDefault(false)
                    if (!sent) {
                        WeChatPayGateway.deliver(
                            AlipayPayResult(
                                success = false,
                                message = "支付失败：无法唤起微信，请确认微信版本或稍后重试",
                            ),
                        )
                    }
                }
            }
        result ?: AlipayPayResult(success = false, message = "支付超时，请稍后在账单中确认结果")
    }
}

/**
 * 微信回调桥。
 *
 * `WXPayEntryActivity` 由微信按固定类名拉起，拿不到调用方协程，因此用一个进程级单例
 * 把「SDK 实例」与「等待中的 continuation」转交给它。
 */
internal object WeChatPayGateway {
    private var api: IWXAPI? = null
    private var pending: CancellableContinuation<AlipayPayResult>? = null

    fun install(wxApi: IWXAPI) {
        api = wxApi
    }

    fun takeApi(): IWXAPI? = api

    fun await(continuation: CancellableContinuation<AlipayPayResult>) {
        pending = continuation
    }

    fun deliver(result: AlipayPayResult) {
        val continuation = pending ?: return
        pending = null
        if (continuation.isActive) continuation.resume(result)
    }

    /** 把微信原始回调翻成统一结果，供 `WXPayEntryActivity` 调用。 */
    fun onResp(
        errCode: Int,
        errStr: String?,
    ) {
        logDebug("WeChat", "PayResp errCode=$errCode errStr=$errStr")
        val message =
            when (errCode) {
                0 -> "支付成功"
                -2 -> "已取消支付"
                -1 -> "支付失败"
                else -> "支付失败：${errStr ?: "错误码 $errCode"}"
            }
        deliver(AlipayPayResult(success = errCode == 0, message = message))
    }
}

/**
 * 供 `WXPayEntryActivity` 使用的极简 `IWXAPIEventHandler`：把回调转给 [WeChatPayGateway]。
 */
internal class WeChatCallbackRelay : IWXAPIEventHandler {
    override fun onReq(req: BaseReq?) = Unit

    override fun onResp(resp: BaseResp?) {
        WeChatPayGateway.onResp(resp?.errCode ?: -1, resp?.errStr)
    }
}

/** 让回调页面统一处理 intent 的扩展，避免 Activity 里重复写 handleIntent。 */
internal fun IWXAPI.handleIntentQuietly(
    intent: Intent,
    relay: IWXAPIEventHandler,
): Boolean = runCatching { handleIntent(intent, relay) }.getOrDefault(false)

/**
 * 兜底 appId，由宿主在启动时注入（Android 来自编译期常量 `BuildConfig.WECHAT_FALLBACK_APP_ID`）。
 *
 * 该值属于第三方应用凭据，仓库内**不得**出现明文：本地构建在 `secrets.properties`
 * 写 `WECHAT_FALLBACK_APP_ID=...`，CI 用同名仓库 Secret 注入环境变量即可。
 * 未注入时为 null，此时完全依赖服务端下发的 `PayInfo.appid`。
 */
private var weChatFallbackAppId: String? = null

actual fun setWeChatFallbackAppId(appId: String) {
    weChatFallbackAppId = appId.takeIf { it.isNotBlank() }
}

/**
 * 微信 App 支付在本端**始终不可用**。
 *
 * 微信只允许开放平台登记过的「包名 + 签名」调起收银台，本端 applicationId 为
 * `com.github.ilife798`、签名沿用调试证书，与官方登记不符，拉起必被拒。
 * 与其让用户点下去才失败，不如把入口直接灰显。[payWithWeChat] 的实现保留完整：
 * 将来若去微信开放平台登记了同名包名与签名，把本常量改为 false 即可启用。
 */
internal const val WECHAT_PAY_UNAVAILABLE = true

/** 见 [WECHAT_PAY_UNAVAILABLE]：恒为 false，界面据此把微信画成灰条。 */
actual fun isWeChatPayAvailable(): Boolean = !WECHAT_PAY_UNAVAILABLE

private const val WECHAT_UNAVAILABLE_HINT = "（第三方不可用）"

/** 用户切到微信后可能停留很久，超时只用于避免协程永久挂起。 */
private const val WECHAT_TIMEOUT_MS = 10 * 60 * 1000L
