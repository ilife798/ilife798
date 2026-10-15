package com.github.ilife798.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceNotesTest {
    @Test
    fun noteKeyedByDeviceId() {
        val notes = DeviceNotes.EMPTY.withNote("AAA", "阳台那台")
        assertEquals("阳台那台", notes.textOf("AAA"))
        assertEquals("", notes.textOf("BBB"))
        assertTrue(notes.hasNote("AAA"))
        assertFalse(notes.hasNote("BBB"))
    }

    @Test
    fun sameDeviceIdKeepsSingleNote() {
        val notes =
            DeviceNotes.EMPTY
                .withNote("AAA", "第一次")
                .withNote("AAA", "第二次")
        assertEquals("第二次", notes.textOf("AAA"))
        assertEquals(1, notes.entries().size)
    }

    @Test
    fun blankTextRemovesNote() {
        val notes = DeviceNotes.EMPTY.withNote("AAA", "有备注")
        assertEquals("", notes.withNote("AAA", "   ").textOf("AAA"))
        assertTrue(notes.withNote("AAA", "").isEmpty())
    }

    @Test
    fun emptyDeviceIdIsIgnored() {
        assertEquals(DeviceNotes.EMPTY, DeviceNotes.EMPTY.withNote("", "无主备注"))
    }

    @Test
    fun normalizeTrimsAndTruncates() {
        assertEquals("备注", normalizeDeviceNote("  备注  "))
        assertEquals("", normalizeDeviceNote("   "))
        val long = "字".repeat(80)
        assertEquals(DEVICE_NOTE_MAX_LENGTH, normalizeDeviceNote(long).length)
    }

    @Test
    fun truncationKeepsSurrogatePairs() {
        // 每个 emoji 占 2 个 UTF-16 单元、1 个码点；超长截断后不应留下半个代理对
        val emojis = "🍎".repeat(80)
        val truncated = normalizeDeviceNote(emojis)
        assertEquals(DEVICE_NOTE_MAX_LENGTH, truncated.codePointCount())
        assertFalse(truncated.last().isHighSurrogate())
        assertTrue(truncated.all { it != '\uFFFD' })
    }

    @Test
    fun retainedOnlyPrunesOrphans() {
        val notes =
            DeviceNotes.EMPTY
                .withNote("AAA", "一")
                .withNote("BBB", "二")
                .withNote("CCC", "三")
        val kept = notes.retainedOnly(listOf("AAA", "CCC"))
        assertEquals("一", kept.textOf("AAA"))
        assertEquals("", kept.textOf("BBB"))
        assertEquals("三", kept.textOf("CCC"))
    }

    @Test
    fun entriesSortedByDeviceId() {
        val notes =
            DeviceNotes.EMPTY
                .withNote("CCC", "三")
                .withNote("AAA", "一")
                .withNote("BBB", "二")
        assertEquals(listOf("AAA", "BBB", "CCC"), notes.entries().map { it.deviceId })
    }

    @Test
    fun codecRoundTrip() {
        val notes =
            DeviceNotes.EMPTY
                .withNote("AAA", "阳台那台")
                .withNote("BBB", "厨房")
        assertEquals(notes, decodeDeviceNotes(encodeDeviceNotes(notes)))
    }

    @Test
    fun codecFallsBackToEmptyOnBadInput() {
        assertEquals(DeviceNotes.EMPTY, decodeDeviceNotes(null))
        assertEquals(DeviceNotes.EMPTY, decodeDeviceNotes(""))
        assertEquals(DeviceNotes.EMPTY, decodeDeviceNotes("   "))
        assertEquals(DeviceNotes.EMPTY, decodeDeviceNotes("not json at all"))
        assertEquals(DeviceNotes.EMPTY, decodeDeviceNotes("""{"notes":123}"""))
    }

    @Test
    fun codecReNormalizesDirtyData() {
        // 历史脏数据里的超长与纯空白值不应原样进入内存
        val dirty = """{"notes":{"AAA":"   ","BBB":"${"字".repeat(90)}"}}"""
        val decoded = decodeDeviceNotes(dirty)
        assertEquals("", decoded.textOf("AAA"))
        assertEquals(DEVICE_NOTE_MAX_LENGTH, decoded.textOf("BBB").length)
    }
}
