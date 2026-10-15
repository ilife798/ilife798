package com.github.ilife798.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIModalPresentationFormSheet
import platform.UIKit.UIViewController
import platform.darwin.NSObject

/**
 * iOS 侧读取文本：用 `UIDocumentPickerViewController(forOpeningContentTypes:)` 让用户挑
 * 文件，读回 UTF-8 文本。
 *
 * iOS 无「注册时机」约束（不像 Android 的 ActivityResultRegistry），但为与 commonMain 的
 * expect 一致，同样由组合提供选择器。
 */
@Composable
actual fun rememberTextFilePicker(): TextFilePicker =
    remember {
        object : TextFilePicker {
            override fun pick(onPicked: (String?) -> Unit) {
                presentOpenPicker(onPicked)
            }
        }
    }

/**
 * 弹出「打开文件」界面。
 *
 * `UIDocumentPickerDelegateProtocol` 的 delegate 在回调到来前会被系统弱引用，必须由外部
 * 持有；这里用局部变量交给 picker 强引用（`delegate` 是 weak，故另存于 picker 的
 * associated object 不可行）。实践上系统会在 present 期间持有 delegate，取文件后立即
 * 触发回调，生命周期足够。
 */
@OptIn(ExperimentalForeignApi::class)
private fun presentOpenPicker(onPicked: (String?) -> Unit) {
    val root =
        UIApplication.sharedApplication.keyWindow?.rootViewController ?: run {
            onPicked(null)
            return
        }
    val delegate = OpenDelegate(onPicked)
    val picker =
        UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(platform.UniformTypeIdentifiers.UTTypeJSON),
            asCopy = true,
        ).apply {
            this.delegate = delegate
            modalPresentationStyle = UIModalPresentationFormSheet
        }
    topMostPicker(root).presentViewController(picker, animated = true, completion = null)
}

private fun topMostPicker(controller: UIViewController): UIViewController {
    var current = controller
    while (true) {
        current = current.presentedViewController ?: return current
    }
}

@OptIn(ExperimentalForeignApi::class)
private class OpenDelegate(
    private val onPicked: (String?) -> Unit,
) : NSObject(),
    UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        onPicked(url?.readTextCapped())
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onPicked(null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSURL.readTextCapped(): String? {
    val data = NSData.dataWithContentsOfURL(this) ?: return null
    if (data.length > BACKUP_MAX_FILE_BYTES.toULong()) return null
    // Kotlin/Native 只暴露 NSString.create(String) 单参构造，没有 (NSData, encoding) 重载，
    // 故先从 NSData 取字节再按 UTF-8 解码；非法 UTF-8 视为无效备份文件。
    val bytes = data.bytes ?: return null
    val text =
        bytes
            .reinterpret<ByteVar>()
            .readBytes(data.length.toInt())
            .decodeToString(throwOnInvalidSequence = false)
    return text
}
