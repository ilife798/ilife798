package com.github.ilife798.util

/**
 * 把文本交给系统文件选择器保存。
 *
 * 各平台一律走系统「另存为」界面，由用户决定落盘位置，应用自身不申请存储权限、
 * 也不写应用私有目录，因此导出的备份文件在卸载后仍可由用户自行保管。
 *
 * 返回值为「是否成功发起保存」而非「是否保存成功」：用户在系统界面点取消同样返回
 * false，调用方据此不做成功提示即可。
 */
expect fun saveTextToFile(
    fileName: String,
    content: String,
    mimeType: String = "application/json",
): Boolean

/** 备份文件使用的 MIME 类型。 */
const val BACKUP_MIME_TYPE: String = "application/json"
