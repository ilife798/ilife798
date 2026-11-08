package com.github.ilife798.util

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSString
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation

// iOS 用 CoreImage 的 CIQRCodeGenerator 编码，再转 PNG 交给公共层解码成 ImageBitmap。
// 任何一步失败都返回 null，调用方会提示改用文件/剪贴板导出。
@OptIn(ExperimentalForeignApi::class)
actual fun encodeQrCodePng(
    text: String,
    sizePx: Int,
): ByteArray? =
    runCatching {
        val message =
            NSString.create(text)?.dataAfterEncoding() ?: return@runCatching null
        val filter = CIFilter.filterWithName("CIQRCodeGenerator") ?: return@runCatching null
        filter.setValue(message, forKey = "inputMessage")
        filter.setValue("L", forKey = "inputCorrectionLevel")
        val base = filter.outputImage ?: return@runCatching null
        // 默认 1 像素/模块，按目标边长放大整数倍，避免缩放后模块边缘发虚
        val modules = base.extent.useContents { size.useContents { width } }
        val scale = if (modules > 0.0) (sizePx / modules).coerceAtLeast(1.0) else 8.0
        val scaled = base.imageByApplyingTransform(CGAffineTransformMakeScale(scale, scale))
        val cgImage = CIContext().createCGImage(scaled, fromRect = scaled.extent) ?: return@runCatching null
        val png = UIImagePNGRepresentation(UIImage.imageWithCGImage(cgImage)) ?: return@runCatching null
        png.bytes?.reinterpret<ByteVar>()?.readBytes(png.length.toInt())
    }.getOrNull()

@OptIn(ExperimentalForeignApi::class)
private fun NSString.dataAfterEncoding() = dataUsingEncoding(NSUTF8StringEncoding)
