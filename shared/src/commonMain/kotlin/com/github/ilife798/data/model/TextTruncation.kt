package com.github.ilife798.data.model

/**
 * 按 Unicode 码点截断字符串，超出 [maxCodePoints] 时丢弃尾部。
 *
 * 不能用 `String.codePoints()`：那是 java.lang.String 的 API，commonMain 里编译不过
 * iOS 目标。这里手工按 UTF-16 单元推进——遇高代理（0xD800..0xDBFF）且后面跟着低代理
 * （0xDC00..0xDFFF）时算作一个码点，从而保证 emoji 等增补平面字符不被拦腰截断。
 *
 * 不截断时原样返回 [this]，避免无谓的字符串复制。
 */
internal fun String.truncateToCodePoints(maxCodePoints: Int): String {
    if (maxCodePoints <= 0) return ""
    var count = 0
    var index = 0
    while (index < length) {
        if (count == maxCodePoints) return substring(0, index)
        val high = this[index]
        index +=
            if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
                2
            } else {
                1
            }
        count++
    }
    return this
}

/**
 * 按 Unicode 码点计的长度。代理对算一个码点，与 [truncateToCodePoints] 的计法一致，
 * 因此可用于展示「已输入 N/上限」。
 */
internal fun String.codePointCount(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        val high = this[index]
        index +=
            if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
                2
            } else {
                1
            }
        count++
    }
    return count
}
