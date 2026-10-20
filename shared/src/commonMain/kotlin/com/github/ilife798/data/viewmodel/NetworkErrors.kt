package com.github.ilife798.data.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// 瞬时网络异常的最大重试次数（含首次）
internal const val NETWORK_RETRY_ATTEMPTS = 3

// 首次重试前的退避时长，之后每次翻倍
private const val NETWORK_RETRY_BASE_BACKOFF_MS = 2_000L

// 是否为可重试的瞬时网络异常（如 DNS 解析失败、连接/读取超时）。
internal expect fun isTransientNetworkError(e: Throwable): Boolean

/**
 * 对瞬时网络异常做有限次指数退避重试。
 *
 * 只重试 [isTransientNetworkError] 认定的异常（DNS 失败、连接/读取超时、断网），
 * SSL 证书错误与业务异常原样抛出；协程取消不重试。
 *
 * @param onRetry 每次准备重试时回调，参数为本次退避秒数与当前已重试次数
 */
internal suspend fun <T> withNetworkRetry(
    onRetry: (suspend (seconds: Long, attempt: Int) -> Unit)? = null,
    block: suspend () -> T,
): T {
    var backoffMs = NETWORK_RETRY_BASE_BACKOFF_MS
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (attempt >= NETWORK_RETRY_ATTEMPTS || !isTransientNetworkError(e)) throw e
            onRetry?.invoke(backoffMs / 1000, attempt)
            delay(backoffMs.milliseconds)
            backoffMs *= 2
            attempt++
        }
    }
}
