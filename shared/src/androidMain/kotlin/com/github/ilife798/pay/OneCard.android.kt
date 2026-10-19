package com.github.ilife798.pay

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.alipay.sdk.app.OpenAuthTask
import com.github.ilife798.ActivityHolder
import com.github.ilife798.logDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 一卡通（`PaymentType.THIRD` = 93）支付。
 *
 * 与官方 `PayHelper;->A(Activity, String)` 等价：用支付宝 SDK 的 [OpenAuthTask] 以
 * `BizType.Deduct` 拉起「免密代扣授权」，不会真的扣款，只做授权签约。
 *
 * - `bizAppId` 官方写死为字符串 `"ilife798"`；
 * - `signParams` 是 `trans/prepay/93` 下发的 `data`，进 SDK 前必须 [URLDecoder.decode]。
 *
 * 回调 `onResult(int, String, Bundle)` 的 `resultCode == 9000`（`OpenAuthTask.OK`）视为授权成功。
 */
actual suspend fun payWithOneCard(signParams: String): AlipayPayResult {
    if (signParams.isEmpty()) {
        return AlipayPayResult(success = false, message = "支付失败：一卡通参数为空")
    }
    val activity =
        ActivityHolder.current
            ?: return AlipayPayResult(success = false, message = "支付失败：页面已关闭，请重试")

    // 官方把整个 scheme 串交给 OpenAuthTask 前先做一次 URL 解码
    val raw =
        runCatching { URLDecoder.decode(signParams, "UTF-8") }
            .getOrElse {
                logDebug("OneCard", "decode failed: ${it.message}")
                signParams
            }

    logDebug("OneCard", "start OpenAuthTask, bizAppId=$ONE_CARD_BIZ_APP_ID")
    return withContext(Dispatchers.Main) {
        withTimeoutOrNull(ONE_CARD_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                val mainHandler = Handler(Looper.getMainLooper())

                fun finish(result: AlipayPayResult) {
                    if (!finished.compareAndSet(false, true)) return
                    if (continuation.isActive) continuation.resume(result)
                }

                val callback =
                    object : OpenAuthTask.Callback {
                        override fun onResult(
                            resultCode: Int,
                            memo: String?,
                            data: Bundle?,
                        ) {
                            logDebug("OneCard", "onResult code=$resultCode memo=$memo")
                            val result =
                                when (resultCode) {
                                    OpenAuthTask.OK -> {
                                        AlipayPayResult(success = true, message = "支付成功")
                                    }

                                    OpenAuthTask.NOT_INSTALLED -> {
                                        AlipayPayResult(success = false, message = "未安装支付宝")
                                    }

                                    OpenAuthTask.SYS_ERR -> {
                                        AlipayPayResult(
                                            success = false,
                                            message = "支付失败：" + (memo ?: "系统错误"),
                                        )
                                    }

                                    else -> {
                                        AlipayPayResult(
                                            success = false,
                                            message = "支付失败" + (memo?.let { "：$it" } ?: ""),
                                        )
                                    }
                                }
                            finish(result)
                        }
                    }

                val task =
                    runCatching {
                        OpenAuthTask(activity).apply {
                            execute(
                                ONE_CARD_BIZ_APP_ID,
                                OpenAuthTask.BizType.Deduct,
                                mapOf("sign_params" to raw),
                                callback,
                                true,
                            )
                        }
                    }
                if (task.isFailure) {
                    logDebug("OneCard", "execute failed: ${task.exceptionOrNull()?.message}")
                    finish(
                        AlipayPayResult(
                            success = false,
                            message = "支付失败：${task.exceptionOrNull()?.message ?: "无法唤起支付宝"}",
                        ),
                    )
                    return@suspendCancellableCoroutine
                }

                continuation.invokeOnCancellation {
                    // 页面销毁时把 SDk 里的 handler 停掉，避免回调泄漏
                    runCatching { mainHandler.removeCallbacksAndMessages(null) }
                }
            }
        } ?: AlipayPayResult(success = false, message = "支付超时，请稍后在账单中确认结果")
    }
}

/** 官方 `PayHelper;->A` 里写死的 bizAppId。 */
private const val ONE_CARD_BIZ_APP_ID = "ilife798"

/** 用户切到支付宝 App 后可能停留很久，超时只用于避免协程永久挂起。 */
private const val ONE_CARD_TIMEOUT_MS = 10 * 60 * 1000L
