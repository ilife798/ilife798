package com.github.ilife798

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.darwin.NSObject

/**
 * iOS 侧的任务保活：循环播放一段静音音频。
 *
 * 为什么需要它：积分任务全程是网络请求，没有正在播放的媒体、没有定位、没有 VoIP，
 * 退到后台后系统随时会挂起进程，请求停在半路——用户看到的就是「任务卡住」。
 * 声明 audio 后台模式并保持一个活跃的 playback 音频会话，是 iOS 允许的少数
 * 能让普通网络任务继续跑下去的通道。
 *
 * 音频内容是静音 PCM，不是降低音量：把 volume 设成 0 在部分系统版本上会被
 * 优化掉音频会话，反而失去保活效果。真正静音靠的是采样值全 0。
 *
 * 只在任务执行期间开启，见 [com.github.ilife798.TaskKeepAlive]。
 */
@OptIn(ExperimentalForeignApi::class)
object IosTaskKeepAlive {
    private const val FILE_NAME = "ilife798-keepalive.wav"

    // 采样率取电话音质即可：内容全是 0，唯一目的是让音频会话保持活跃。
    private const val SAMPLE_RATE = 8000
    private const val SECONDS = 2
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16

    private var player: AVAudioPlayer? = null

    fun setEnabled(enabled: Boolean) {
        if (enabled) start() else stop()
    }

    private fun start() {
        if (player != null) return
        try {
            val session = AVAudioSession.sharedInstance()
            // playback 才能在后台上继续发声；default mode 足够，不需要混音等选项。
            // 会话的激活交给 AVAudioPlayer.play()（内部会激活），不去手工调 setActive：
            // 那个方法在 K/N 里被映射成属性，写法与签名随版本有差异，少一处就少一处编译风险。
            session.setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeDefault, options = 0u, error = null)
            val url = silentAudioUrl()
            if (url == null) {
                logDebug("ILife798", "IosTaskKeepAlive.start: 无法准备静音音频文件")
                return
            }
            val created = AVAudioPlayer(contentsOfURL = url, error = null)
            // -1 表示无限循环：一轮任务可能跑十几分钟，不循环就会在中途结束。
            created.numberOfLoops = -1
            created.prepareToPlay()
            created.play()
            player = created
            logDebug("ILife798", "IosTaskKeepAlive.start: 已开启静音保活")
        } catch (t: Throwable) {
            // 保活是增强而非前提：失败也只记录，任务照常在前台跑完。
            logDebug("ILife798", "IosTaskKeepAlive.start 失败: ${t.message}")
        }
    }

    private fun stop() {
        val current = player ?: return
        player = null
        try {
            // 停掉播放后没有活跃音频，系统会在进程挂起时自行停用会话。
            current.stop()
            logDebug("ILife798", "IosTaskKeepAlive.stop: 已关闭静音保活")
        } catch (t: Throwable) {
            logDebug("ILife798", "IosTaskKeepAlive.stop 失败: ${t.message}")
        }
    }

    // 复用临时目录里的同一份文件，避免每次开任务都重写一遍。
    private fun silentAudioUrl(): NSURL? {
        val path = NSTemporaryDirectory() + FILE_NAME
        val url = NSURL.fileURLWithPath(path)
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) return url
        return if (silentWavData().writeToFile(path, atomically = true)) url else null
    }

    private fun silentWavData(): NSData {
        val dataSize = SAMPLE_RATE * SECONDS * CHANNELS * BITS_PER_SAMPLE / 8
        val bytes = ByteArray(44 + dataSize)
        bytes.putAscii(0, "RIFF")
        bytes.putIntLe(4, 36 + dataSize)
        bytes.putAscii(8, "WAVE")
        bytes.putAscii(12, "fmt ")
        bytes.putIntLe(16, 16)
        bytes.putShortLe(20, 1)
        bytes.putShortLe(22, CHANNELS)
        bytes.putIntLe(24, SAMPLE_RATE)
        bytes.putIntLe(28, SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
        bytes.putShortLe(32, CHANNELS * BITS_PER_SAMPLE / 8)
        bytes.putShortLe(34, BITS_PER_SAMPLE)
        bytes.putAscii(36, "data")
        bytes.putIntLe(40, dataSize)
        // 数据区保持 0：这就是「静音」的全部来源。
        return bytes.toNSData()
    }

    private fun ByteArray.putAscii(
        offset: Int,
        value: String,
    ) {
        value.forEachIndexed { index, ch -> this[offset + index] = ch.code.toByte() }
    }

    private fun ByteArray.putIntLe(
        offset: Int,
        value: Int,
    ) {
        for (i in 0 until 4) this[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun ByteArray.putShortLe(
        offset: Int,
        value: Int,
    ) {
        for (i in 0 until 2) this[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun ByteArray.toNSData(): NSData =
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
}
