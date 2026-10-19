package com.github.ilife798.ui.page.me

import kotlin.test.Test
import kotlin.test.assertEquals

class CardIdMaskTest {
    @Test
    fun twelveDigitCardKeepsFirstAndLastTwo() {
        assertEquals("1*********12", maskCardId("123456789012"))
    }

    @Test
    fun shortCardIsFullyMasked() {
        assertEquals("***", maskCardId("123"))
        assertEquals("**", maskCardId("12"))
    }

    @Test
    fun singleCharacterIsMasked() {
        assertEquals("*", maskCardId("7"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", maskCardId(""))
    }
}
