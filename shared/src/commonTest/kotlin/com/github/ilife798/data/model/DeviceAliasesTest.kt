package com.github.ilife798.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceAliasesTest {
    @Test
    fun aliasKeyedByDeviceId() {
        val aliases = DeviceAliases.EMPTY.withAlias("AAA", "阳台")
        assertEquals("阳台", aliases.textOf("AAA"))
        assertEquals("", aliases.textOf("BBB"))
        assertTrue(aliases.hasAlias("AAA"))
        assertFalse(aliases.hasAlias("BBB"))
    }

    @Test
    fun sameDeviceIdKeepsSingleAlias() {
        val aliases =
            DeviceAliases.EMPTY
                .withAlias("AAA", "第一次")
                .withAlias("AAA", "第二次")
        assertEquals("第二次", aliases.textOf("AAA"))
        assertEquals(1, aliases.entries().size)
    }

    @Test
    fun blankTextRemovesAlias() {
        val aliases = DeviceAliases.EMPTY.withAlias("AAA", "有别名")
        assertEquals("", aliases.withAlias("AAA", "   ").textOf("AAA"))
        assertTrue(aliases.withAlias("AAA", "").isEmpty())
    }

    @Test
    fun emptyDeviceIdIsIgnored() {
        assertEquals(DeviceAliases.EMPTY, DeviceAliases.EMPTY.withAlias("", "无主别名"))
    }

    @Test
    fun normalizeTrimsAndTruncates() {
        assertEquals("别名", normalizeDeviceAlias("  别名  "))
        assertEquals("", normalizeDeviceAlias("   "))
        assertEquals(DEVICE_ALIAS_MAX_LENGTH, normalizeDeviceAlias("字".repeat(60)).length)
    }

    @Test
    fun truncationKeepsSurrogatePairs() {
        val emojis = "🍎".repeat(60)
        val truncated = normalizeDeviceAlias(emojis)
        assertEquals(DEVICE_ALIAS_MAX_LENGTH, truncated.codePoints().toArray().size)
        assertFalse(truncated.last().isHighSurrogate())
        assertTrue(truncated.all { it != '\uFFFD' })
    }

    @Test
    fun retainedOnlyPrunesOrphans() {
        val aliases =
            DeviceAliases.EMPTY
                .withAlias("AAA", "一")
                .withAlias("BBB", "二")
        val kept = aliases.retainedOnly(listOf("AAA"))
        assertEquals("一", kept.textOf("AAA"))
        assertEquals("", kept.textOf("BBB"))
    }

    @Test
    fun entriesSortedByDeviceId() {
        val aliases =
            DeviceAliases.EMPTY
                .withAlias("CCC", "三")
                .withAlias("AAA", "一")
        assertEquals(listOf("AAA", "CCC"), aliases.entries().map { it.deviceId })
    }

    @Test
    fun codecRoundTrip() {
        val aliases =
            DeviceAliases.EMPTY
                .withAlias("AAA", "阳台")
                .withAlias("BBB", "厨房")
        assertEquals(aliases, decodeDeviceAliases(encodeDeviceAliases(aliases)))
    }

    @Test
    fun codecFallsBackToEmptyOnBadInput() {
        assertEquals(DeviceAliases.EMPTY, decodeDeviceAliases(null))
        assertEquals(DeviceAliases.EMPTY, decodeDeviceAliases(""))
        assertEquals(DeviceAliases.EMPTY, decodeDeviceAliases("not json"))
        assertEquals(DeviceAliases.EMPTY, decodeDeviceAliases("""{"aliases":123}"""))
    }

    @Test
    fun codecReNormalizesDirtyData() {
        val dirty = """{"aliases":{"AAA":"   ","BBB":"${"字".repeat(60)}"}}"""
        val decoded = decodeDeviceAliases(dirty)
        assertEquals("", decoded.textOf("AAA"))
        assertEquals(DEVICE_ALIAS_MAX_LENGTH, decoded.textOf("BBB").length)
    }

    @Test
    fun notesAndAliasesAreIndependent() {
        // 别名与备注是不同的存储键与不同的数据类型，互不影响
        val notes = DeviceNotes.EMPTY.withNote("AAA", "备注内容")
        val aliases = DeviceAliases.EMPTY.withAlias("AAA", "别名内容")
        assertEquals("备注内容", notes.textOf("AAA"))
        assertEquals("别名内容", aliases.textOf("AAA"))
        assertTrue(notes.withNote("AAA", "").isEmpty())
        assertEquals("别名内容", aliases.textOf("AAA"))
    }
}
