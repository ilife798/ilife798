package com.github.ilife798.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 导入逻辑测试：预览构建、冲突识别、勾选与裁决、合并结果。
 *
 * 这里只测纯函数，不碰 ViewModel 的账号写入（那部分依赖 AppStorage 与协程作用域，
 * 属 Android 宿主测试的范畴）。
 */
class BackupImportTest {
    private fun doc(
        accounts: List<BackupAccount> = emptyList(),
        notes: Map<String, String> = emptyMap(),
        aliases: Map<String, String> = emptyMap(),
    ) = BackupDocument(createdAt = 111L, accounts = accounts, deviceNotes = notes, deviceAliases = aliases)

    private fun previewOf(
        doc: BackupDocument,
        currentAccounts: List<BackupAccount> = emptyList(),
        currentNotes: Map<String, String> = emptyMap(),
        currentAliases: Map<String, String> = emptyMap(),
    ) = buildImportPreview(doc, currentAccounts, currentNotes, currentAliases)

    // ---------- 预览构建 ----------

    @Test
    fun newDeviceNoteIsNotConflict() {
        val preview = previewOf(doc(notes = mapOf("dev1" to "客厅")))
        val item = preview.notes.single()
        assertTrue(item.isNew)
        assertFalse(item.isConflict)
        assertEquals("客厅", item.incomingValue)
        assertEquals("", item.currentValue)
    }

    @Test
    fun differingValueIsConflict() {
        val preview = previewOf(doc(notes = mapOf("dev1" to "客厅")), currentNotes = mapOf("dev1" to "卧室"))
        val item = preview.notes.single()
        assertTrue(item.isConflict)
        assertEquals("卧室", item.currentValue)
        assertEquals("客厅", item.incomingValue)
        assertEquals(1, preview.conflictCount)
    }

    @Test
    fun identicalValueIsNotConflict() {
        val preview = previewOf(doc(notes = mapOf("dev1" to "客厅")), currentNotes = mapOf("dev1" to "客厅"))
        val item = preview.notes.single()
        assertFalse(item.isConflict)
        assertTrue(item.isIdentical)
    }

    @Test
    fun existingAccountIsMarkedAsAlreadyPresent() {
        val incoming = BackupAccount(phone = "13800000000", uid = "u1", name = "甲")
        val preview = previewOf(doc(accounts = listOf(incoming)), currentAccounts = listOf(BackupAccount(uid = "u1")))
        val item = preview.accounts.single()
        assertFalse(item.isNew)
        assertEquals("本机已有该账号", item.currentValue)
    }

    @Test
    fun accountMatchedByPhoneEvenWhenUidDiffers() {
        val incoming = BackupAccount(phone = "13800000000", uid = "new-uid")
        val preview = previewOf(doc(accounts = listOf(incoming)), currentAccounts = listOf(BackupAccount(phone = "13800000000")))
        assertFalse(preview.accounts.single().isNew)
    }

    @Test
    fun accountKeyPrefersUidThenPhoneThenName() {
        assertEquals("account:u1", accountImportKey(BackupAccount(uid = "u1", phone = "138", name = "甲")))
        assertEquals("account:138", accountImportKey(BackupAccount(phone = "138", name = "甲")))
        assertEquals("account:甲", accountImportKey(BackupAccount(name = "甲")))
    }

    @Test
    fun emptyDocumentYieldsEmptyPreview() {
        assertTrue(previewOf(doc()).isEmpty())
        assertEquals(0, previewOf(doc()).totalCount)
    }

    // ---------- 勾选与裁决 ----------

    @Test
    fun toggleAddsAndRemovesItem() {
        val preview = previewOf(doc(notes = mapOf("dev1" to "a", "dev2" to "b")))
        val first = preview.notes.first()
        val checked = ImportSelection.EMPTY.toggle(first)
        assertTrue(checked.isChecked(first))
        assertEquals(1, checked.checkedCount(preview))
        assertFalse(checked.toggle(first).isChecked(first))
    }

    @Test
    fun unresolvedConflictDefaultsToKeep() {
        val preview = previewOf(doc(notes = mapOf("dev1" to "备份值")), currentNotes = mapOf("dev1" to "本机值"))
        val item = preview.notes.single()
        assertEquals(ConflictChoice.Keep, ImportSelection.EMPTY.resolutionOf(item))
    }

    @Test
    fun resolveRecordsUserChoice() {
        val preview = previewOf(doc(notes = mapOf("dev1" to "备份值")), currentNotes = mapOf("dev1" to "本机值"))
        val item = preview.notes.single()
        val resolved = ImportSelection.EMPTY.resolve(item, ConflictChoice.Overwrite)
        assertEquals(ConflictChoice.Overwrite, resolved.resolutionOf(item))
    }

    @Test
    fun resolutionSurvivesToggle() {
        val preview = previewOf(doc(notes = mapOf("dev1" to "备份值")), currentNotes = mapOf("dev1" to "本机值"))
        val item = preview.notes.single()
        // 先裁决再勾选，裁决不应被 toggle 丢掉
        val result = ImportSelection.EMPTY.resolve(item, ConflictChoice.Overwrite).toggle(item)
        assertTrue(result.isChecked(item))
        assertEquals(ConflictChoice.Overwrite, result.resolutionOf(item))
    }

    // ---------- 合并 ----------

    @Test
    fun uncheckedItemsAreNotImported() {
        val preview = previewOf(doc(notes = mapOf("dev1" to "a")))
        val result = mergeImport(preview, ImportSelection.EMPTY)
        assertTrue(result.deviceNotes.isEmpty())
        assertEquals(0, result.totalImported)
    }

    @Test
    fun checkedNewItemIsImported() {
        val preview = previewOf(doc(notes = mapOf("dev1" to "客厅")))
        val selection = ImportSelection.EMPTY.toggle(preview.notes.single())
        val result = mergeImport(preview, selection)
        assertEquals(mapOf("dev1" to "客厅"), result.deviceNotes)
        assertEquals(1, result.notesImported)
    }

    @Test
    fun keepResolutionKeepsLocalValue() {
        val preview = previewOf(doc(notes = mapOf("dev1" to "备份值")), currentNotes = mapOf("dev1" to "本机值"))
        val item = preview.notes.single()
        val selection = ImportSelection.EMPTY.toggle(item).resolve(item, ConflictChoice.Keep)
        val result = mergeImport(preview, selection)
        assertEquals(mapOf("dev1" to "本机值"), result.deviceNotes)
    }

    @Test
    fun overwriteResolutionTakesIncomingValue() {
        val preview = previewOf(doc(notes = mapOf("dev1" to "备份值")), currentNotes = mapOf("dev1" to "本机值"))
        val item = preview.notes.single()
        val selection = ImportSelection.EMPTY.toggle(item).resolve(item, ConflictChoice.Overwrite)
        val result = mergeImport(preview, selection)
        assertEquals(mapOf("dev1" to "备份值"), result.deviceNotes)
    }

    @Test
    fun notesAndAliasesForSameDeviceAreIndependent() {
        val preview =
            previewOf(
                doc(notes = mapOf("dev1" to "客厅"), aliases = mapOf("dev1" to "饮水机")),
            )
        val selection =
            ImportSelection.EMPTY
                .toggle(preview.notes.single())
                .toggle(preview.aliases.single())
        val result = mergeImport(preview, selection)
        assertEquals(mapOf("dev1" to "客厅"), result.deviceNotes)
        assertEquals(mapOf("dev1" to "饮水机"), result.deviceAliases)
    }

    /**
     * 回归：别名与备注曾共用 `device:<id>` 作 key，导致同一个勾选集合里两者互相覆盖——
     * 勾上别名的备注会被顶掉。key 必须按类型加前缀。
     */
    @Test
    fun sameDeviceNoteAndAliasHaveDistinctKeys() {
        val preview = previewOf(doc(notes = mapOf("dev1" to "客厅"), aliases = mapOf("dev1" to "饮水机")))
        val noteKey = preview.notes.single().key
        val aliasKey = preview.aliases.single().key
        assertTrue(noteKey != aliasKey, "备注与别名的 key 必须不同，实际都是 $noteKey")

        // 勾选两者后应各自独立计数
        val selection =
            ImportSelection.EMPTY
                .toggle(preview.notes.single())
                .toggle(preview.aliases.single())
        assertEquals(2, selection.checkedCount(preview))
    }

    @Test
    fun deviceIdIsRecoveredFromKindPrefixedKey() {
        assertEquals("dev1", deviceIdOfImportKey(deviceImportKey(ImportItemKind.Note, "dev1")))
        assertEquals("dev1", deviceIdOfImportKey(deviceImportKey(ImportItemKind.Alias, "dev1")))
        // 设备 ID 含冒号时只剥掉类型前缀
        assertEquals("a:b", deviceIdOfImportKey(deviceImportKey(ImportItemKind.Note, "a:b")))
    }

    @Test
    fun importingOnlyAliasLeavesNoteUntouched() {
        val preview = previewOf(doc(notes = mapOf("dev1" to "客厅"), aliases = mapOf("dev1" to "饮水机")))
        val selection = ImportSelection.EMPTY.toggle(preview.aliases.single())
        val result = mergeImport(preview, selection)
        assertTrue(result.deviceNotes.isEmpty())
        assertEquals(mapOf("dev1" to "饮水机"), result.deviceAliases)
    }

    @Test
    fun mergeNeverClearsDevicesAbsentFromBackup() {
        // 备份里只有 dev1，本机另有 dev2——合并结果不应包含 dev2，由调用方做增量写入
        val preview = previewOf(doc(notes = mapOf("dev1" to "a")), currentNotes = mapOf("dev1" to "x", "dev2" to "保留"))
        val result = mergeImport(preview, ImportSelection.EMPTY.toggle(preview.notes.single()))
        assertEquals(setOf("dev1"), result.deviceNotes.keys)
    }

    @Test
    fun accountForImportKeyResolvesCredentials() {
        val incoming = BackupAccount(uid = "u1", phone = "138", token = "tk", appToken = "at", name = "甲")
        val document = doc(accounts = listOf(incoming))
        val restored = document.accountForImportKey(accountImportKey(incoming))
        assertNotNull(restored)
        assertEquals("tk", restored.token)
        assertEquals("at", restored.appToken)
        assertNull(document.accountForImportKey("account:missing"))
    }

    // ---------- 与 codec 串联 ----------

    @Test
    fun roundTrippedBackupBuildsSamePreview() {
        val original =
            doc(
                accounts = listOf(BackupAccount(uid = "u1", phone = "138", token = "tk", name = "甲")),
                notes = mapOf("dev1" to "客厅"),
                aliases = mapOf("dev2" to "饮水机"),
            )
        val decoded = decodeBackup(encodeBackup(original))
        assertNotNull(decoded)
        val a = previewOf(original)
        val b = previewOf(decoded)
        assertEquals(a.totalCount, b.totalCount)
        assertEquals(a.conflictCount, b.conflictCount)
    }
}
