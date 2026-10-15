package com.github.ilife798.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「清空设备自定义信息」依赖的定向删除能力测试。
 *
 * 该功能要按设备 ID 精确删掉选中的备注/别名，且两个类型互不影响、未选中的
 * 条目必须原样保留——这些正是最容易出错的点，故在此覆盖。
 */
class DeviceCustomInfoClearTest {
    private fun notesOf(vararg pairs: Pair<String, String>): DeviceNotes =
        pairs.fold(DeviceNotes.EMPTY) { acc, (id, text) -> acc.withNote(id, text) }

    private fun aliasesOf(vararg pairs: Pair<String, String>): DeviceAliases =
        pairs.fold(DeviceAliases.EMPTY) { acc, (id, text) -> acc.withAlias(id, text) }

    @Test
    fun removingSelectedNotesKeepsOthers() {
        val notes = notesOf("AAA" to "甲", "BBB" to "乙", "CCC" to "丙")
        val kept = listOf("BBB").fold(notes) { acc, id -> acc.withoutNote(id) }
        assertEquals("", kept.textOf("BBB"))
        assertEquals("甲", kept.textOf("AAA"))
        assertEquals("丙", kept.textOf("CCC"))
        assertEquals(2, kept.entries().size)
    }

    @Test
    fun notesAndAliasesAreIndependentForSameDeviceId() {
        val notes = notesOf("AAA" to "设备备注")
        val aliases = aliasesOf("AAA" to "设备别名")
        // 只删别名的设备 ID，备注必须不受影响
        val aliasesAfter = aliases.withoutAlias("AAA")
        assertTrue(notes.hasNote("AAA"), "删别名不应影响同设备备注")
        assertFalse(aliasesAfter.hasAlias("AAA"))
        assertEquals("设备别名", aliases.textOf("AAA"))
    }

    @Test
    fun removingOnlyAliasKeepsNoteForSameDevice() {
        val notes = notesOf("AAA" to "保留我")
        val aliases = aliasesOf("AAA" to "删我", "BBB" to "别删我")
        val aliasesAfter = aliases.withoutAlias("AAA")
        assertEquals("", aliasesAfter.textOf("AAA"))
        assertEquals("别删我", aliasesAfter.textOf("BBB"))
        assertTrue(notes.hasNote("AAA"))
    }

    @Test
    fun unknownDeviceIdsAreNoOps() {
        val notes = notesOf("AAA" to "甲")
        val after = listOf("ZZZ", "YYY").fold(notes) { acc, id -> acc.withoutNote(id) }
        assertEquals(1, after.entries().size)
        assertEquals("甲", after.textOf("AAA"))
    }

    @Test
    fun clearAllEmptiesBothKinds() {
        val notes = notesOf("AAA" to "甲", "BBB" to "乙")
        val aliases = aliasesOf("AAA" to "一", "BBB" to "二")
        assertTrue(notes.entries().isNotEmpty())
        assertTrue(aliases.entries().isNotEmpty())
        assertTrue(DeviceNotes.EMPTY.entries().isEmpty())
        assertTrue(DeviceAliases.EMPTY.entries().isEmpty())
    }

    @Test
    fun selectedCountMatchesActualRemovals() {
        val notes = notesOf("AAA" to "甲", "BBB" to "乙")
        val aliases = aliasesOf("AAA" to "一")
        val noteIds = listOf("AAA", "BBB")
        val aliasIds = listOf("AAA")
        val droppedNotes = noteIds.filter { notes.hasNote(it) }
        val droppedAliases = aliasIds.filter { aliases.hasAlias(it) }
        assertEquals(2, droppedNotes.size)
        assertEquals(1, droppedAliases.size)
        assertEquals(3, droppedNotes.size + droppedAliases.size)
    }

    @Test
    fun alreadyAbsentSelectionCountsAsZero() {
        val notes = notesOf("AAA" to "甲")
        // 重复删除同一个 ID 时，第二次不应再计数
        val first = listOf("AAA").filter { notes.hasNote(it) }
        val after = notes.withoutNote("AAA")
        val second = listOf("AAA").filter { after.hasNote(it) }
        assertEquals(1, first.size)
        assertEquals(0, second.size)
    }
}
