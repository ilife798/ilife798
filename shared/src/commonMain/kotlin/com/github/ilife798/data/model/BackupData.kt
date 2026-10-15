package com.github.ilife798.data.model

import kotlinx.serialization.Serializable

/** 备份文件的格式版本，用于将来兼容旧备份。 */
const val BACKUP_FORMAT_VERSION: Int = 1

/** 备份文件默认文件名（不含扩展名，实际导出时追加时间戳）。 */
const val BACKUP_FILE_NAME_PREFIX: String = "ilife798-backup"

/**
 * 备份文件根节点。
 *
 * 备份只包含用户在本机产生、且无法从服务端重新拉取的数据：账号凭据与设备自定义
 * 信息（别名、备注）。设备列表、积分、钱包等均来自服务端，恢复时重新请求即可，
 * 因此不入备份。
 */
@Serializable
data class BackupDocument(
    val version: Int = BACKUP_FORMAT_VERSION,
    val createdAt: Long = 0L,
    val accounts: List<BackupAccount> = emptyList(),
    val deviceNotes: Map<String, String> = emptyMap(),
    val deviceAliases: Map<String, String> = emptyMap(),
) {
    /** 备份是否不含任何条目。 */
    fun isEmpty(): Boolean = accounts.isEmpty() && deviceNotes.isEmpty() && deviceAliases.isEmpty()
}

/** 备份中的单个账号。只保留登录所需凭据与用于展示的资料，不含其它运行时状态。 */
@Serializable
data class BackupAccount(
    val phone: String = "",
    val uid: String = "",
    val token: String = "",
    val appToken: String = "",
    val name: String = "",
    val img: String = "",
)

/** 把备份账号还原为运行时账号对象。 */
fun BackupAccount.toAccount(): Account =
    Account(
        phone = phone,
        isLoggedIn = token.isNotEmpty() || appToken.isNotEmpty(),
        uid = uid,
        token = token,
        appToken = appToken,
    )

/** 把运行时账号条目转成备份账号。 */
fun AccountEntry.toBackupAccount(): BackupAccount =
    BackupAccount(
        phone = account.phone,
        uid = account.uid,
        token = account.token,
        appToken = account.appToken,
        name = info.name,
        img = info.img,
    )

/** 账号在备份/恢复界面上展示的名称，回退顺序为昵称、手机号、uid。 */
fun BackupAccount.displayName(): String = name.ifEmpty { phone.ifEmpty { uid } }

/** 账号在备份/恢复界面上的第二行说明。 */
fun BackupAccount.displaySummary(): String =
    when {
        phone.isNotEmpty() && phone != displayName() -> phone
        uid.isNotEmpty() -> "UID $uid"
        else -> "未命名账号"
    }
