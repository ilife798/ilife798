package com.github.ilife798.util

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIModalPresentationFormSheet
import platform.UIKit.UIViewController
import platform.darwin.NSObject

/**
 * iOS 侧保存文本：先把内容写到临时目录的临时文件，再用
 * `UIDocumentPickerViewController(forExportingURLs:)` 让用户选择保存位置。
 *
 * 选「存储到文件」后系统会拷贝临时文件，因此在回调里删掉临时文件不会影响已保存的
 * 副本，同时避免临时目录堆积。delegate 需要在回调期间保持存活，故由局部变量持有。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun saveTextToFile(
    fileName: String,
    content: String,
    mimeType: String,
): Boolean {
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return false
    val tmpPath = NSTemporaryDirectory() + fileName
    val data = content.encodeToNSData() ?: return false
    if (!data.writeToFile(tmpPath, atomically = true)) return false

    val tempUrl = NSURL.fileURLWithPath(tmpPath)
    val delegate = ExportDelegate(tempUrl)
    val picker =
        UIDocumentPickerViewController(forExportingURLs = listOf(tempUrl), asCopy = true).apply {
            this.delegate = delegate
            modalPresentationStyle = UIModalPresentationFormSheet
        }
    topMost(root).presentViewController(picker, animated = true, completion = null)
    return true
}

/** 沿 presentedViewController 找到最上层控制器，保证文件选择器一定可见。 */
private fun topMost(controller: UIViewController): UIViewController {
    var current = controller
    while (true) {
        current = current.presentedViewController ?: return current
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun String.encodeToNSData() =
    (this as? platform.Foundation.NSString)?.dataUsingEncoding(NSUTF8StringEncoding)
        ?: (platform.Foundation.NSString.create(this))?.dataUsingEncoding(NSUTF8StringEncoding)

/** 导出结束后清理临时文件。 */
private class ExportDelegate(
    private val tempUrl: NSURL,
) : NSObject(),
    UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        removeTemp()
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        removeTemp()
    }

    private fun removeTemp() {
        NSFileManager.defaultManager.removeItemAtURL(tempUrl, error = null)
    }
}
