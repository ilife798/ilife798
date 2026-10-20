package com.github.ilife798.data.viewmodel

import kotlinx.coroutines.CancellationException

/**
 * 把「执行一段可能抛异常的表达式，失败时记日志并返回兜底值」的样板收成一行。
 *
 * 语义固定为：
 * - 成功 → 返回值；
 * - [CancellationException] → 原样抛出（协程取消不算业务失败，吞掉会破坏结构化并发）；
 * - 其他异常 → 调 [onError] 记日志后返回 [fallback]。
 *
 * 只用于**表达式形态**的短逻辑。包含多次赋值、提前 return 或循环控制的长业务流不要用这个包，
 * 直接写 try/catch 更清楚。
 */
internal inline fun <T> guarded(
    fallback: T,
    onError: (Exception) -> Unit,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
        fallback
    }
