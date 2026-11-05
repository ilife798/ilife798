package com.github.ilife798.data.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

// 请求体默认值与 JSON 契约（对齐 IlifeApi.requestJson 的 encodeDefaults = true + explicitNulls = false）
class ApiRequestsTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun missionExecDefaultType() {
        val request = MissionExecRequest(adId = "ad1")
        assertEquals(101, request.type)
        assertEquals(
            """{"adId":"ad1","type":101}""",
            json.encodeToString(MissionExecRequest.serializer(), request),
        )
    }

    // 小程序通道不加 addScore/addScoreType，且 null 字段不进请求体（explicitNulls = false）
    @Test
    fun missionExecNonAppOmitsAddFields() {
        val request = MissionExecRequest.forChannel("ad1", 30, isApp = false)
        assertEquals(
            """{"adId":"ad1","type":101}""",
            json.encodeToString(MissionExecRequest.serializer(), request),
        )
    }

    @Test
    fun refundWalletDefaultType() {
        val request = RefundWalletRequest(eid = "e1")
        assertEquals(23, request.type)
        assertEquals(
            """{"eid":"e1","type":23}""",
            json.encodeToString(RefundWalletRequest.serializer(), request),
        )
    }

    @Test
    fun exchangeScoreDefaultType() {
        val request = ExchangeScoreRequest(ep = EndpointRef("ep1"), score = 500)
        assertEquals(1, request.type)
        assertEquals(
            """{"ep":{"id":"ep1"},"score":500,"type":1}""",
            json.encodeToString(ExchangeScoreRequest.serializer(), request),
        )
    }

    @Test
    fun createRechargeOrderDefaults() {
        val request =
            CreateRechargeOrderRequest(
                contact = EndpointRef("c"),
                ep = EndpointRef("e"),
                owner = EndpointRef("o"),
                prds = listOf(ProductRef(id = "p", count = 2)),
            )
        assertEquals(1, request.cata)
        assertEquals("钱包充值", request.note)
        assertEquals(
            """{"cata":1,"contact":{"id":"c"},"ep":{"id":"e"},"note":"钱包充值","owner":{"id":"o"},"prds":[{"id":"p","count":2}]}""",
            json.encodeToString(CreateRechargeOrderRequest.serializer(), request),
        )
    }

    @Test
    fun loginAndSignInFields() {
        assertEquals(
            """{"openCode":"","authCode":"a","un":"u","cid":"c"}""",
            json.encodeToString(
                LoginRequest.serializer(),
                LoginRequest(authCode = "a", un = "u", cid = "c"),
            ),
        )
        // SignInRequest 不走 @Serializable：字段名按通道变化（App→weekday，小程序→weekDay），
        // 改用手动 toJson()，这里验证两个通道的字段名契约。
        assertEquals(
            """{"weekday":2,"adId":"ad","addScore":5,"addScoreType":1}""",
            SignInRequest.forChannel(weekDay = 2, adId = "ad", score = 5, isApp = true).toJson(),
        )
        assertEquals(
            """{"weekDay":2,"adId":"ad"}""",
            SignInRequest.forChannel(weekDay = 2, adId = "ad", score = 5, isApp = false).toJson(),
        )
        assertEquals(
            """{"un":"u","authCode":"a","s":"s"}""",
            json.encodeToString(SmsCodeRequest.serializer(), SmsCodeRequest(un = "u", authCode = "a", s = "s")),
        )
    }
}
