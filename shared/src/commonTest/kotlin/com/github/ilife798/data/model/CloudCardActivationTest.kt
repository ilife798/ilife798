package com.github.ilife798.data.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudCardActivationTest {
    private val now = 1_760_600_000_000L

    private fun card(
        activatedAt: Long = 0L,
        validTime: Long = 0L,
    ) = CloudCard(id = "123456789012", activatedAt = activatedAt, validTime = validTime)

    @Test
    fun validTimeInFutureMeansNoActivation() {
        assertFalse(card(validTime = now + 60_000L).needsActivation(now))
    }

    @Test
    fun validTimeInPastMeansActivation() {
        assertTrue(card(validTime = now - 60_000L).needsActivation(now))
    }

    // validTime 缺失时退回 activatedAt + 12 小时
    @Test
    fun fallsBackToActivatedAtPlusInterval() {
        assertFalse(card(activatedAt = now - 1000L).needsActivation(now))
        assertTrue(card(activatedAt = now - CARD_ACTIVATE_INTERVAL_MS - 1000L).needsActivation(now))
    }

    @Test
    fun missingBothTimestampsMeansActivation() {
        assertTrue(card().needsActivation(now))
    }

    // 回归：若时间戳是秒级却被当作毫秒，会恒判定为过期（每 12 小时的激活机制失效）。
    // 该值在 1970 年的毫秒尺度上远小于 now，未归一化时必然返回 true。
    @Test
    fun secondScaleValueWouldAlwaysLookExpired() {
        val secondsNow = now / 1000
        val secondsValid = secondsNow + 12 * 3600

        // 正确（已归一为毫秒）时：有效期在未来，不需激活
        assertFalse(card(validTime = secondsValid * 1000).needsActivation(now))

        // 反例：未归一化则恒为需激活
        assertTrue(card(validTime = secondsValid).needsActivation(now))
    }

    @Test
    fun boundaryIsInclusiveAtDeadline() {
        assertTrue(card(validTime = now).needsActivation(now))
    }
}
