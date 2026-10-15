package com.github.ilife798.data.model

import kotlinx.serialization.json.Json

private val backupJson =
    Json {
        // 备份文件可能由未来版本导出，忽略不认识的字段而不是直接解析失败
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

/** 序列化备份文档为可写入文件的文本。 */
fun encodeBackup(doc: BackupDocument): String = backupJson.encodeToString(doc)

/**
 * 解析备份文本。解析失败或内容为空返回 null，由调用方提示用户文件无效，
 * 避免把半截数据当成有效备份导入。
 */
fun decodeBackup(raw: String?): BackupDocument? {
    if (raw.isNullOrBlank()) return null
    return runCatching { backupJson.decodeFromString<BackupDocument>(raw) }.getOrNull()
}
