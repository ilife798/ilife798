package com.github.ilife798.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [truncateToCodePoints] / [codePointCount] 的回归测试。
 *
 * 这两个函数替换了原先用 `String.codePoints()` 的实现——后者是 java.lang.String 的
 * API，在 commonMain 里能过 Android 编译但 iOS 目标直接编译失败。这里同时锁住「按码点
 * 计数」这一语义，避免以后有人改回按 UTF-16 单元截断。
 */
class TextTruncationTest {
    @Test
    fun shorterThanLimitIsReturnedUnchanged() {
        assertEquals("abc", "abc".truncateToCodePoints(10))
        assertEquals("abc", "abc".truncateToCodePoints(3))
    }

    @Test
    fun truncatesByCodePointNotUtf16Unit() {
        // 每字 1 码点，截 2 个
        assertEquals("汉字", "汉字测试".truncateToCodePoints(2))
    }

    @Test
    fun surrogatePairCountsAsOneCodePoint() {
        // 🍎 是 1 码点 / 2 个 UTF-16 单元
        assertEquals(1, "🍎".codePointCount())
        assertEquals(2, "🍎🍎".codePointCount())
        // 若按 UTF-16 单元截断，这里会留下半个代理对
        assertEquals("🍎🍎", "🍎🍎🍎".truncateToCodePoints(2))
    }

    @Test
    fun truncationNeverLeavesDanglingHighSurrogate() {
        val truncated = "🍎".repeat(50).truncateToCodePoints(7)
        assertEquals(7, truncated.codePointCount())
        assertEquals(14, truncated.length)
    }

    @Test
    fun zeroLimitYieldsEmpty() {
        assertEquals("", "abc".truncateToCodePoints(0))
        assertEquals("", "abc".truncateToCodePoints(-1))
    }

    @Test
    fun countMatchesLengthForBmpOnlyText() {
        val text = "纯中文与ascii"
        assertEquals(text.length, text.codePointCount())
    }

    @Test
    fun emptyStringIsZero() {
        assertEquals(0, "".codePointCount())
        assertEquals("", "".truncateToCodePoints(5))
    }
}
