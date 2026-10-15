package com.github.ilife798.util

import androidx.compose.runtime.Composable

/**
 * 让用户用系统文件选择器挑一个文本文件并读回内容。
 *
 * 与 [TextFileSaver] 对称：导出走系统「另存为」，导入走系统「打开」，应用自身既不申请
 * 存储权限，也不假设文件落在哪里。
 *
 * 同样是 Composable：Android 侧的 `registerForActivityResult` 必须在 LifecycleOwner
 * 进入 STARTED 之前注册（从点击回调里注册会抛 `IllegalStateException`），注册时机只能
 * 由组合决定。
 */
@Composable
expect fun rememberTextFilePicker(): TextFilePicker

/**
 * 一次「打开文件」的发起器。由 [rememberTextFilePicker] 创建，在组合期间完成平台注册，
 * 之后可在任意回调中调用 [pick]。
 */
interface TextFilePicker {
    /**
     * 发起选择。回调收到文件内容；返回 null 表示用户取消、未选中、读取失败或文件过大，
     * 调用方应据此区分「取消」与「内容无效」，不要一律当作失败提示。
     */
    fun pick(onPicked: (String?) -> Unit)
}

/** 导入备份时可接受的文件类型；系统选择器据此过滤。 */
const val BACKUP_PICK_MIME_TYPE: String = "application/json"

/** 备份文件扩展名，用于 iOS 侧按类型过滤。 */
const val BACKUP_FILE_EXTENSION: String = "json"

/**
 * 可读入的备份文件大小上限。
 *
 * 备份只含账号凭据与设备别名备注，正常体积在几十 KB 量级；设上限是为了避免用户误选
 * 一个巨大的文件（例如几十 MB 的日志或安装包）时把主线程读爆。
 */
const val BACKUP_MAX_FILE_BYTES: Long = 4L * 1024L * 1024L
