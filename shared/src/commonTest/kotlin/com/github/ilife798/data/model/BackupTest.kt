package com.github.ilife798.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupTest {
    private fun accountsGroup(vararg keys: String) =
        BackupGroup(
            id = "group:accounts",
            title = "账号",
            emptyHint = "无账号",
            options = keys.map { BackupOption(it, it) },
        )

    private fun devicesGroup(vararg keys: String) =
        BackupGroup(
            id = "group:devices",
            title = "设备自定义信息",
            emptyHint = "无设备",
            options = keys.map { BackupOption(it, it) },
        )

    @Test
    fun emptySelectionExportsNothing() {
        val groups = listOf(accountsGroup("account:0"), devicesGroup("device:a"))
        assertEquals(0, BackupSelection.EMPTY.selectedCount(groups))
        assertTrue(BackupSelection.EMPTY.selectedLeafKeys(groups).isEmpty())
    }

    @Test
    fun toggleOptionSelectsSingleLeaf() {
        val group = accountsGroup("account:0", "account:1")
        val selection = BackupSelection.EMPTY.toggleOption(group, group.options[0])
        assertEquals(setOf("account:0"), selection.selectedLeafKeys(listOf(group)))
        assertEquals(1, selection.selectedCount(listOf(group)))
    }

    @Test
    fun togglingSameOptionTwiceClearsIt() {
        val group = accountsGroup("account:0")
        val selection =
            BackupSelection.EMPTY
                .toggleOption(group, group.options[0])
                .toggleOption(group, group.options[0])
        assertEquals(0, selection.selectedCount(listOf(group)))
    }

    @Test
    fun groupStateIsPartialThenAll() {
        val group = accountsGroup("account:0", "account:1")
        val none = BackupSelection.EMPTY
        assertEquals(BackupGroupState.None, none.groupState(group))

        val partial = none.toggleOption(group, group.options[0])
        assertEquals(BackupGroupState.Partial, partial.groupState(group))
        assertFalse(partial.isGroupFullyChecked(group))

        val all = partial.toggleOption(group, group.options[1])
        assertEquals(BackupGroupState.All, all.groupState(group))
        assertTrue(all.isGroupFullyChecked(group))
    }

    @Test
    fun toggleGroupSelectsAndClearsWholeGroup() {
        val group = accountsGroup("account:0", "account:1", "account:2")
        val all = BackupSelection.EMPTY.toggleGroup(group)
        assertEquals(3, all.selectedCount(listOf(group)))
        assertEquals(BackupGroupState.All, all.groupState(group))

        val cleared = all.toggleGroup(group)
        assertEquals(0, cleared.selectedCount(listOf(group)))
        assertEquals(BackupGroupState.None, cleared.groupState(group))
    }

    @Test
    fun uncheckingOneLeafAfterGroupSelectLeavesOthersChecked() {
        val group = accountsGroup("account:0", "account:1", "account:2")
        val selection =
            BackupSelection.EMPTY
                .toggleGroup(group)
                .toggleOption(group, group.options[1])
        assertEquals(setOf("account:0", "account:2"), selection.selectedLeafKeys(listOf(group)))
        assertEquals(BackupGroupState.Partial, selection.groupState(group))
    }

    @Test
    fun emptyGroupIsNeverFullyChecked() {
        val group = accountsGroup()
        assertFalse(BackupSelection.EMPTY.isGroupFullyChecked(group))
        assertEquals(BackupGroupState.None, BackupSelection.EMPTY.groupState(group))
        // 空分组上切换不应产生任何勾选
        assertEquals(0, BackupSelection.EMPTY.toggleGroup(group).selectedCount(listOf(group)))
    }

    @Test
    fun groupKeyAloneDoesNotLeakIntoSelectedLeaves() {
        val group = accountsGroup("account:0", "account:1")
        val selection = BackupSelection.EMPTY.toggleGroup(group)
        // 分组 key 表示全选，本身不是待导出条目
        assertFalse("group:accounts" in selection.selectedLeafKeys(listOf(group)))
        assertEquals(2, selection.selectedCount(listOf(group)))
    }

    @Test
    fun selectionIsScopedPerGroup() {
        val accounts = accountsGroup("account:0")
        val devices = devicesGroup("device:a", "device:b")
        val selection = BackupSelection.EMPTY.toggleGroup(devices)
        assertEquals(setOf("device:a", "device:b"), selection.selectedLeafKeys(listOf(accounts, devices)))
        assertEquals(0, selection.selectedCount(listOf(accounts)))
    }

    @Test
    fun codecRoundTripKeepsAllSections() {
        val doc =
            BackupDocument(
                createdAt = 123L,
                accounts = listOf(BackupAccount(phone = "13800000000", uid = "u1", appToken = "t1", name = "甲")),
                deviceNotes = mapOf("dev1" to "客厅"),
                deviceAliases = mapOf("dev1" to "饮水机"),
            )
        val decoded = decodeBackup(encodeBackup(doc))
        assertNotNull(decoded)
        assertEquals(doc, decoded)
    }

    @Test
    fun codecRejectsGarbageInsteadOfReturningEmptyDocument() {
        assertNull(decodeBackup("not json"))
        assertNull(decodeBackup(""))
        assertNull(decodeBackup(null))
    }

    @Test
    fun codecIgnoresUnknownFutureFields() {
        val raw = """{"version":99,"accounts":[],"futureField":true}"""
        val decoded = decodeBackup(raw)
        assertNotNull(decoded)
        assertEquals(99, decoded.version)
    }

    @Test
    fun emptyDocumentReportsEmpty() {
        assertTrue(BackupDocument().isEmpty())
        assertFalse(BackupDocument(deviceNotes = mapOf("a" to "b")).isEmpty())
    }

    @Test
    fun accountConversionRoundTripsCredentials() {
        val entry =
            AccountEntry(
                account = Account(phone = "13800000000", uid = "u1", token = "tk", appToken = "at"),
                info = AccountInfo(name = "甲", img = "http://x/i.png"),
            )
        val backup = entry.toBackupAccount()
        assertEquals("13800000000", backup.phone)
        assertEquals("u1", backup.uid)
        assertEquals("tk", backup.token)
        assertEquals("at", backup.appToken)
        assertEquals("甲", backup.name)

        val restored = backup.toAccount()
        assertEquals("13800000000", restored.phone)
        assertEquals("u1", restored.uid)
        assertEquals("tk", restored.token)
        assertEquals("at", restored.appToken)
        assertTrue(restored.isLoggedIn)
    }

    @Test
    fun accountWithoutTokenIsNotLoggedInAfterRestore() {
        assertFalse(BackupAccount(phone = "13800000000").toAccount().isLoggedIn)
    }

    @Test
    fun displayNameFallsBackToPhoneThenUid() {
        assertEquals("甲", BackupAccount(name = "甲", phone = "138", uid = "u1").displayName())
        assertEquals("138", BackupAccount(phone = "138", uid = "u1").displayName())
        assertEquals("u1", BackupAccount(uid = "u1").displayName())
    }
}
