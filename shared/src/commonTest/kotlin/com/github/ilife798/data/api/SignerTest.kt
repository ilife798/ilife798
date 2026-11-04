package com.github.ilife798.data.api

import com.github.ilife798.util.md5
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SignerTest {
    private val salt = "test-salt"

    @Test
    fun producesLowerCaseHexMd5() {
        ApiConfig.init("https://example.com", salt, "cid")
        val sign = Signer.sign(adId = "ad1", token = "token-12345678", uid = "uid-12345678")
        assertEquals(32, sign.length)
        assertTrue(sign.all { it in "0123456789abcdef" })
    }

    @Test
    fun differsWhenInputChanges() {
        ApiConfig.init("https://example.com", salt, "cid")
        val a = Signer.sign("ad1", "token", "uid")
        val b = Signer.sign("ad2", "token", "uid")
        assertNotEquals(a, b)
    }

    @Test
    fun bucketsTimeIn30SecondIntervals() {
        ApiConfig.init("https://example.com", salt, "cid")
        val base = 1790266560000L
        val a = Signer.signAt("ad", "token-12345678", "uid-12345678", base)
        val b = Signer.signAt("ad", "token-12345678", "uid-12345678", base + 29999)
        val c = Signer.signAt("ad", "token-12345678", "uid-12345678", base + 30000)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // 原文为 adId + 时间桶 + token 尾 8 位 + uid 尾 8 位 + 盐
    @Test
    fun signsWithInjectedSalt() {
        ApiConfig.init("https://example.com", salt, "cid")
        val base = 1790266560000L
        val bucket = 30 * (base / 30000)
        val expected = md5("ad-1" + bucket + "abcdefgh" + "12345678" + salt)
        assertEquals(expected, Signer.signAt("ad-1", "token-abcdefgh", "uid-12345678", base))
    }

    // 盐未注入时不应有内置回落值，签名直接用空盐
    @Test
    fun doesNotFallBackToBuiltinSalt() {
        ApiConfig.init("https://example.com", "", "cid")
        val base = 1790266560000L
        val bucket = 30 * (base / 30000)
        val expected = md5("ad-1" + bucket + "abcdefgh" + "12345678")
        assertEquals(expected, Signer.signAt("ad-1", "token-abcdefgh", "uid-12345678", base))
    }

    // 参数表签名：key 升序、"k=v" 以 & 连接、末尾追加盐。
    // 注意：acc/login 实测不校验 sign，此函数目前是备用工具，不对应任何强制验签的接口。
    @Test
    fun signsSortedQueryWithSalt() {
        ApiConfig.init("https://example.com", salt, "cid")
        val sign =
            Signer.signQuery(
                mapOf(
                    "un" to "13800000000",
                    "authCode" to "1234",
                    "cid" to "client",
                    "openCode" to "",
                ),
            )
        val expected = md5("authCode=1234&cid=client&openCode=&un=13800000000" + salt)
        assertEquals(expected, sign)
    }

    // 通道决定用哪把盐：App 用 SIGN_SALT，小程序用 MINI_SIGN_SALT，两者不可互换。
    // 这正是 issue #3 的根因所在——通道与盐错配，服务端一律判「验签失败」。
    @Test
    fun signsWithChannelSpecificSalt() {
        val appSalt = "app-salt"
        val miniSalt = "mini-salt"
        ApiConfig.init("https://example.com", appSalt, "cid", miniSalt)
        val base = 1790266560000L
        val bucket = 30 * (base / 30000)
        val raw = "ad-1" + bucket + "abcdefgh" + "12345678"

        assertEquals(md5(raw + appSalt), Signer.task("ad-1", "token-abcdefgh", "uid-12345678", isApp = true, now = base))
        assertEquals(md5(raw + miniSalt), Signer.task("ad-1", "token-abcdefgh", "uid-12345678", isApp = false, now = base))
        assertNotEquals(
            Signer.task("ad-1", "token-abcdefgh", "uid-12345678", isApp = true, now = base),
            Signer.task("ad-1", "token-abcdefgh", "uid-12345678", isApp = false, now = base),
        )
    }

    // 登录签名：md5("authCode={code}&un={phone}{App 盐}")，盐固定取 SIGN_SALT。
    // ⚠ acc/login 实测不校验 sign，此函数只是与官方 App 保持一致的实现。
    @Test
    fun loginSignsAuthCodeAndPhone() {
        ApiConfig.init("https://example.com", salt, "cid")
        val expected = md5("authCode=1234&un=13800000000" + salt)
        assertEquals(expected, Signer.login(phone = "13800000000", code = "1234"))
    }

    // 小程序通道未注入盐时同样是空串签名，不得静默回落到 App 通道的盐
    @Test
    fun miniChannelDoesNotBorrowAppSalt() {
        ApiConfig.init("https://example.com", salt, "cid")
        assertEquals("", ApiConfig.miniSalt)
        val base = 1790266560000L
        val bucket = 30 * (base / 30000)
        val expected = md5("ad-1" + bucket + "abcdefgh" + "12345678")
        assertEquals(expected, Signer.task("ad-1", "token-abcdefgh", "uid-12345678", isApp = false, now = base))
    }
}
