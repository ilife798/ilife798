package com.github.ilife798.data.model

/**
 * 备份页面的一项可勾选条目。`key` 在同一分组内唯一，用于记住勾选状态。
 */
data class BackupOption(
    val key: String,
    val title: String,
    val summary: String = "",
)

/**
 * 一个备份分组：一个分组级的全选条目 + 若干叶子条目。
 *
 * 顶层条目（如「账号」「设备自定义信息」）勾选表示该分组全选，行为与叶子条目一致，
 * 只是它的 key 固定为分组 id，不参与实际导出内容，导出时只看叶子条目。
 */
data class BackupGroup(
    val id: String,
    val title: String,
    val emptyHint: String,
    val options: List<BackupOption>,
) {
    /** 分组内可勾选的叶子 key（不含分组自身的全选 key）。 */
    val optionKeys: List<String> get() = options.map { it.key }
}

/**
 * 备份页的勾选状态。
 *
 * 勾选以 key 集合表示，不依赖列表顺序，因此页面重建后仍能保留选择。分组级勾选与
 * 叶子级勾选共用同一个集合：勾中分组 key 等价于全选该分组，渲染时由 [isChecked] 与
 * [groupState] 把两种写法归一化。
 */
data class BackupSelection(
    private val checked: Set<String> = emptySet(),
) {
    /** 分组是否全选（分组 key 被勾中，或该分组所有叶子都被勾中）。 */
    fun isGroupFullyChecked(group: BackupGroup): Boolean =
        if (group.optionKeys.isEmpty()) {
            false
        } else {
            group.id in checked || group.optionKeys.all { it in checked }
        }

    /** 分组是否有任一叶子被勾中。 */
    fun isGroupPartiallyChecked(group: BackupGroup): Boolean =
        !isGroupFullyChecked(group) && (group.id in checked || group.optionKeys.any { it in checked })

    /** 分组级复选框的三态。空分组恒为未选中。 */
    fun groupState(group: BackupGroup): BackupGroupState =
        when {
            isGroupFullyChecked(group) -> BackupGroupState.All
            isGroupPartiallyChecked(group) -> BackupGroupState.Partial
            else -> BackupGroupState.None
        }

    /** 叶子条目是否被勾中。分组被全选时其下所有叶子视为勾中。 */
    fun isChecked(
        group: BackupGroup,
        option: BackupOption,
    ): Boolean = group.id in checked || option.key in checked

    /** 切换分组级勾选：未全选则全选，已全选则全不选。 */
    fun toggleGroup(group: BackupGroup): BackupSelection {
        if (group.optionKeys.isEmpty()) return this
        return if (isGroupFullyChecked(group)) {
            BackupSelection(checked - group.id - group.optionKeys.toSet())
        } else {
            BackupSelection(checked + group.id + group.optionKeys)
        }
    }

    /** 切换单个叶子条目。 */
    fun toggleOption(
        group: BackupGroup,
        option: BackupOption,
    ): BackupSelection {
        if (isGroupFullyChecked(group) && group.id in checked) {
            // 分组处于「整组勾中」写法时，先展开成逐个叶子的显式勾选，再取消这一项
            val expanded = checked - group.id + group.optionKeys.toSet()
            return BackupSelection(expanded - option.key)
        }
        return if (option.key in checked) {
            BackupSelection(checked - option.key)
        } else {
            BackupSelection(checked + option.key)
        }
    }

    /** 当前实际会导出的叶子 key（分组 key 只代表全选，本身不导出）。 */
    fun selectedLeafKeys(groups: List<BackupGroup>): Set<String> =
        groups
            .flatMap { group ->
                if (isGroupFullyChecked(group)) group.optionKeys else group.optionKeys.filter { it in checked }
            }.toSet()

    /** 实际导出条目数，用于按钮文案与禁用判断。 */
    fun selectedCount(groups: List<BackupGroup>): Int = selectedLeafKeys(groups).size

    companion object {
        val EMPTY = BackupSelection()
    }
}

/** 分组级复选框的三态。 */
enum class BackupGroupState { None, Partial, All }
