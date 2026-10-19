package com.github.ilife798.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardQrParserTest {
    // 实体卡实证格式：卡号是 URL 最后一段的 12 位数字
    private val realQr = "https://i.hnkzy.com/q/1/123456789012"

    @Test
    fun realQrExtractsCardNo() {
        assertEquals("123456789012", CardQrParser.parse(realQr).cardNo)
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("123456789012", CardQrParser.parse("  $realQr  ").cardNo)
    }

    @Test
    fun ignoresTrailingSlash() {
        assertEquals("123456789012", CardQrParser.parse("$realQr/").cardNo)
    }

    @Test
    fun ignoresQueryAndFragment() {
        assertEquals("123456789012", CardQrParser.parse("$realQr?from=scan").cardNo)
        assertEquals("123456789012", CardQrParser.parse("$realQr#top").cardNo)
    }

    @Test
    fun acceptsBareCardNo() {
        assertEquals("123456789012", CardQrParser.parse("123456789012").cardNo)
    }

    // 官方规则另外允许「2 位 hex 前缀 + 10 位数字」与纯 10 位
    @Test
    fun acceptsTenDigitAndHexPrefixedForms() {
        assertEquals("1234567890", CardQrParser.parse("1234567890").cardNo)
        assertEquals("ab1234567890", CardQrParser.parse("ab1234567890").cardNo)
    }

    @Test
    fun fallsBackToAnySegmentMatchingCardNo() {
        // 卡号不在末段的历史/其他域名格式
        assertEquals("123456789012", CardQrParser.parse("https://other.example.com/c/123456789012/info").cardNo)
    }

    @Test
    fun rejectsWrongLength() {
        assertEquals("", CardQrParser.parse("https://i.hnkzy.com/q/1/12345").cardNo)
        assertEquals("", CardQrParser.parse("https://i.hnkzy.com/q/1/1234567890123").cardNo)
    }

    @Test
    fun rejectsNonNumericAndEmpty() {
        assertEquals("", CardQrParser.parse("").cardNo)
        assertEquals("", CardQrParser.parse("https://i.hnkzy.com/q/1/abcdefghijkl").cardNo)
    }

    @Test
    fun rejectsOverlongRawText() {
        assertEquals("", CardQrParser.parse("https://i.hnkzy.com/q/1/" + "9".repeat(600)).cardNo)
    }

    @Test
    fun isValidCardNoMatchesOfficialRule() {
        assertTrue(CardQrParser.isValidCardNo("123456789012"))
        assertTrue(CardQrParser.isValidCardNo("1234567890"))
        assertTrue(CardQrParser.isValidCardNo("ab1234567890"))
        assertFalse(CardQrParser.isValidCardNo("12345"))
        assertFalse(CardQrParser.isValidCardNo(""))
    }

    @Test
    fun resultReportsValidity() {
        assertTrue(CardQrParser.parse(realQr).isValid)
        assertFalse(CardQrParser.parse("not a card").isValid)
    }
}
