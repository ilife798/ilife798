package com.github.ilife798.util

import androidx.compose.runtime.Composable

/**
 * 把文本交给系统文件选择器保存。
 *
 * 各平台一律走系统「另存为」界面，由用户决定落盘位置，应用自身不申请存储权限、
 * 也不写应用私有目录，因此导出的备份文件在卸载后仍可由用户自行保管。
 *
 * 这是 Composable 而非普通函数：Android 侧的 `registerForActivityResult` 必须在
 * LifecycleOwner 进入 STARTED 之前注册，从点击回调里注册会抛
 * `IllegalStateException: LifecycleOwners must call register before they are STARTED`。
 * 因此注册时机必须由组合（composition）决定，调用方在 Composable 里拿到返回值再在
 * 回调中使用。
 */
@Composable
expect fun rememberTextFileSaver(): TextFileSaver

/**
 * 一次「另存为」的发起器。由 [rememberTextFileSaver] 创建，在组合期间完成平台注册，
 * 之后可在任意回调中调用 [save]。
 */
interface TextFileSaver {
    /**
     * 发起保存。返回 false 表示未能发起（平台不支持、没有可用 Activity 等），
     * 用户在系统界面点取消同样走不到成功分支，调用方不应据 true 就当作已保存。
     */
    fun save(
        fileName: String,
        content: String,
        onFinished: (Boolean) -> Unit = {},
    )
}

/** 备份文件使用的 MIME 类型。 */
const val BACKUP_MIME_TYPE: String = "application/json"
