package com.github.ilife798.data.model

/**
 * 导入时一个条目的落点：当前值、导入值与二者关系。
 *
 * 界面据此决定是「直接可加」还是「需要用户裁决冲突」，并展示对比。
 */
data class ImportItem(
    /** 稳定标识，用于记住勾选：账号用 `account:<uid|phone>`，设备用 `device:<设备ID>`。 */
    val key: String,
    val title: String,
    /** 该条目在本机的当前值；为空表示本机没有这条数据。 */
    val currentValue: String,
    /** 备份文件里的值。 */
    val incomingValue: String,
) {
    /** 是否冲突：本机已有该条目，且值与导入值不同。 */
    val isConflict: Boolean get() = currentValue.isNotEmpty() && incomingValue != currentValue

    /** 本机没有这条数据，导入即为新增。 */
    val isNew: Boolean get() = currentValue.isEmpty()

    /** 值与导入值完全一致，导入无意义（但也不冲突）。 */
    val isIdentical: Boolean get() = currentValue.isNotEmpty() && incomingValue == currentValue
}

/**
 * 导入预览：把备份文件与本机现状对照后的结果。
 *
 * 账号与设备自定义信息分开成组，与导出页的分组保持一致。账号只按「是否已存在」区分，
 * 因为账号是整体覆盖（凭据是一组，拆开导入没有意义）；设备别名与备注是按 deviceId 键
 * 的单值，冲突时逐条裁决。
 */
data class ImportPreview(
    val accounts: List<ImportItem> = emptyList(),
    val notes: List<ImportItem> = emptyList(),
    val aliases: List<ImportItem> = emptyList(),
    /** 备份文件的生成时间，用于界面提示；0 表示文件未记录。 */
    val createdAt: Long = 0L,
) {
    val totalCount: Int get() = accounts.size + notes.size + aliases.size
    val conflictCount: Int get() = allItems().count { it.isConflict }

    fun allItems(): List<ImportItem> = accounts + notes + aliases

    fun isEmpty(): Boolean = totalCount == 0
}

/**
 * 用户在导入界面上对某个冲突条目的裁决。
 *
 * [Keep] 保留本机现值（忽略备份里的），[Overwrite] 用备份值覆盖本机。
 */
enum class ConflictChoice { Keep, Overwrite }

/**
 * 导入界面的勾选与冲突裁决状态。
 *
 * 勾选与裁决分开保存：勾选决定「这条要不要导入」，裁决决定「冲突时用哪边的值」。
 * 未裁决的冲突默认 [ConflictChoice.Keep]，即宁可不动本机数据——这是更安全的默认。
 */
data class ImportSelection(
    private val checked: Set<String> = emptySet(),
    private val resolutions: Map<String, ConflictChoice> = emptyMap(),
) {
    fun isChecked(item: ImportItem): Boolean = item.key in checked

    /** 用户对该冲突条目的裁决；未裁决时默认保留本机值。 */
    fun resolutionOf(item: ImportItem): ConflictChoice = resolutions[item.key] ?: ConflictChoice.Keep

    fun toggle(item: ImportItem): ImportSelection =
        if (item.key in checked) {
            ImportSelection(checked - item.key, resolutions)
        } else {
            ImportSelection(checked + item.key, resolutions)
        }

    fun resolve(
        item: ImportItem,
        choice: ConflictChoice,
    ): ImportSelection = ImportSelection(checked, resolutions + (item.key to choice))

    fun checkedItems(preview: ImportPreview): List<ImportItem> = preview.allItems().filter { it.key in checked }

    fun checkedCount(preview: ImportPreview): Int = checkedItems(preview).size

    companion object {
        val EMPTY = ImportSelection()
    }
}

/**
 * 把导入预览与本机现状合并，返回各类最终值。
 *
 * 只处理预览里出现过的条目，本机其它数据不受影响——导入永远是「合并」而非「替换」。
 * 未被勾选的条目原样保留本机值；勾选的冲突条目按用户裁决取值。
 */
fun mergeImport(
    preview: ImportPreview,
    selection: ImportSelection,
): ImportMergeResult {
    val notes = mutableMapOf<String, String>()
    val aliases = mutableMapOf<String, String>()
    var accountsAdded = 0
    var notesChanged = 0
    var aliasesChanged = 0

    preview.notes.forEach { item ->
        if (!selection.isChecked(item)) return@forEach
        val value = if (item.isConflict && selection.resolutionOf(item) == ConflictChoice.Keep) item.currentValue else item.incomingValue
        val deviceId = deviceIdOfImportKey(item.key)
        if (value.isNotEmpty()) {
            notes[deviceId] = value
            notesChanged++
        }
    }

    preview.aliases.forEach { item ->
        if (!selection.isChecked(item)) return@forEach
        val value = if (item.isConflict && selection.resolutionOf(item) == ConflictChoice.Keep) item.currentValue else item.incomingValue
        val deviceId = deviceIdOfImportKey(item.key)
        if (value.isNotEmpty()) {
            aliases[deviceId] = value
            aliasesChanged++
        }
    }

    val accounts = preview.accounts.filter { selection.isChecked(it) }
    accountsAdded = accounts.size

    return ImportMergeResult(
        accounts = accounts,
        deviceNotes = notes,
        deviceAliases = aliases,
        accountsImported = accountsAdded,
        notesImported = notesChanged,
        aliasesImported = aliasesChanged,
    )
}

/** [mergeImport] 的结果：要写入本机的各类数据与计数。 */
data class ImportMergeResult(
    val accounts: List<ImportItem> = emptyList(),
    val deviceNotes: Map<String, String> = emptyMap(),
    val deviceAliases: Map<String, String> = emptyMap(),
    val accountsImported: Int = 0,
    val notesImported: Int = 0,
    val aliasesImported: Int = 0,
) {
    val totalImported: Int get() = accountsImported + notesImported + aliasesImported
}

/** 一次导入实际写入本机的数量，用于结果提示。 */
data class ImportApplyOutcome(
    /** 新增的账号数（已存在的账号不重复加入，也不算在内）。 */
    val accountsAdded: Int = 0,
    /** 设备别名与备注合计变更条数。 */
    val deviceChanged: Int = 0,
) {
    val total: Int get() = accountsAdded + deviceChanged
}

/**
 * 导入条目的 key。账号用 uid 优先、其次手机号、最后昵称。
 *
 * 与 [buildImportPreview] 里生成 key 的逻辑必须保持一致，否则应用导入时回查不到原始
 * 账号对象，凭据会丢失。
 */
fun accountImportKey(account: BackupAccount): String = "account:${account.uid.ifEmpty { account.phone }.ifEmpty { account.name }}"

/**
 * 设备自定义信息条目的 key。
 *
 * 别名与备注是同一设备上的两项独立数据。若两者共用 `device:<id>`，同一个勾选集合里的
 * 两者会互相覆盖——勾备注会把别名的勾选顶掉。故按类型加前缀区分。
 */
fun deviceImportKey(
    kind: ImportItemKind,
    deviceId: String,
): String = "${kind.name.lowercase()}:$deviceId"

/**
 * 从设备条目 key 还原 deviceId。key 形如 `note:dev1` / `alias:dev1`。
 *
 * 设备 ID 本身可能含冒号，故只去掉第一段前缀。
 */
fun deviceIdOfImportKey(key: String): String = key.substringAfter(':')

/** 设备自定义信息的两类，用于区分同一设备 ID 上的别名与备注。 */
enum class ImportItemKind { Note, Alias }

/** 按导入条目的 key 找回备份里的原始账号，找不到返回 null。 */
fun BackupDocument.accountForImportKey(key: String): Account? = accounts.firstOrNull { accountImportKey(it) == key }?.toAccount()

/**
 * 把备份文档与本机现状对照，生成导入预览。
 *
 * @param doc 解析成功的备份文档
 * @param currentAccounts 本机账号，(uid, phone) 用于判定是否已存在；账号无冲突概念，
 *   已存在的账号整条跳过（凭据可能已更新，用旧备份覆盖会把用户登出）
 * @param currentNotes 本机设备备注，deviceId → 文本
 * @param currentAliases 本机设备别名，deviceId → 文本
 * @param nameOfAccount 用于界面展示备份账号名称
 */
fun buildImportPreview(
    doc: BackupDocument,
    currentAccounts: List<BackupAccount>,
    currentNotes: Map<String, String>,
    currentAliases: Map<String, String>,
): ImportPreview {
    val accounts =
        doc.accounts.map { incoming ->
            val existing =
                currentAccounts.firstOrNull {
                    (it.uid.isNotEmpty() && it.uid == incoming.uid) ||
                        (it.phone.isNotEmpty() && it.phone == incoming.phone)
                }
            ImportItem(
                key = accountImportKey(incoming),
                title = incoming.displayName(),
                // 账号已存在时标记为「本机已有」，界面上不勾选、不导入
                currentValue = existing?.let { "本机已有该账号" } ?: "",
                incomingValue = incoming.displaySummary(),
            )
        }
    val notes =
        doc.deviceNotes.map { (deviceId, text) ->
            ImportItem(
                key = deviceImportKey(ImportItemKind.Note, deviceId),
                title = deviceId,
                currentValue = currentNotes[deviceId] ?: "",
                incomingValue = text,
            )
        }
    val aliases =
        doc.deviceAliases.map { (deviceId, text) ->
            ImportItem(
                key = deviceImportKey(ImportItemKind.Alias, deviceId),
                title = deviceId,
                currentValue = currentAliases[deviceId] ?: "",
                incomingValue = text,
            )
        }
    return ImportPreview(
        accounts = accounts,
        notes = notes.sortedBy { it.title },
        aliases = aliases.sortedBy { it.title },
        createdAt = doc.createdAt,
    )
}
