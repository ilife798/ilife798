package com.github.ilife798

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.github.ilife798.data.api.ApiConfig
import com.github.ilife798.update.onNotificationPermissionResult

// NDEF payload 文本中嵌入的链接（绝对 URI 或以 /q/ 开头的路径）
private val embeddedLink = Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S+|/q/\\S+")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ApplicationContext.instance = applicationContext
        ActivityHolder.current = this
        AppStorage.instance = PersistentStorage(applicationContext)
        DeviceTile.controller = AndroidDeviceTileController(applicationContext)
        ApiConfig.init(
            gateway = BuildConfig.API_GATEWAY,
            salt = BuildConfig.SIGN_SALT,
            clientId = BuildConfig.API_CID,
        )

        handleIntent(intent)

        setContent {
            App(weChatFallbackAppId = BuildConfig.WECHAT_FALLBACK_APP_ID)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppLifecycle.notifyResumed()
    }

    override fun onStop() {
        super.onStop()
        AppLifecycle.notifyStopped()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onNotificationPermissionResult(requestCode)
    }

    private fun handleIntent(intent: Intent?) {
        // NFC 载荷两种形态：直接 NDEF 分发带 EXTRA_NDEF_MESSAGES；MIUI 按 AAR 兜底以 MAIN 启动时只带 EXTRA_TAG
        if (intent != null &&
            (readNdefMessagesExtra(intent) != null || readTagExtra(intent) != null)
        ) {
            handleNdefIntent(intent)
        }
        when (intent?.action) {
            IntentActions.ACTION_SCAN -> {
                AppRequests.scan.request()
            }

            IntentActions.ACTION_START_DEVICE -> {
                intent.getStringExtra(IntentActions.EXTRA_DEVICE_ID)?.let { AppRequests.startDevice.request(it) }
            }

            IntentActions.ACTION_RUN_TASKS -> {
                AppRequests.runTasks.request()
            }

            IntentActions.ACTION_SHOW_UPDATE -> {
                AppRequests.showUpdate.request()
            }

            IntentActions.ACTION_OPEN_HOME -> {
                AppRequests.openHome.request()
            }

            IntentActions.ACTION_STOP_DEVICE -> {
                intent.getStringExtra(IntentActions.EXTRA_DEVICE_ID)?.let { AppRequests.stopDevice.request(it) }
            }

            IntentActions.ACTION_OPEN_TASKS -> {
                AppRequests.openTasks.request()
            }

            IntentActions.ACTION_STOP_TASKS -> {
                AppRequests.stopTasks.request()
            }
        }
    }

    // 提取标签携带的设备链接：优先 intent data（直接 NDEF 分发），其次遍历 NDEF 消息记录
    private fun handleNdefIntent(intent: Intent) {
        // AAR 兜底时 dataString 是占位 URI（vnd.android.nfc://ext/...），不是真实设备链接
        val data = intent.dataString?.takeUnless { it.startsWith("vnd.android.nfc://") }
        val uri = data ?: readNdefUri(intent)
        uri?.takeIf { it.isNotBlank() }?.let { AppRequests.tagContent.request(it) }
    }

    // 遍历 NDEF 消息提取设备链接：优先标准 URI 记录，其次 URI/MIME/text 记录 payload 中的文本
    private fun readNdefUri(intent: Intent): String? {
        val messages = resolveNdefMessages(intent)
        if (messages.isEmpty()) return null
        for (ndef in messages) {
            for (record in ndef.records) {
                val text =
                    runCatching { String(record.payload, Charsets.UTF_8).trim() }
                        .getOrDefault("")
                // 标准 URI 记录（TNF_ABSOLUTE_URI / WELL_KNOWN URI）；AAR 的 vnd 占位 URI 跳过
                val uriStr = record.toUri()?.toString()
                if (uriStr != null && !uriStr.startsWith("vnd.android.nfc://")) return uriStr
                // 链接嵌在 payload 文本里（MIME 记录为纯文本；text 记录带语言码前缀，故从模式处截取）
                embeddedLink.find(text)?.let { return it.value }
            }
        }
        return null
    }

    // 解析 NDEF 消息：优先 EXTRA_NDEF_MESSAGES；MIUI AAR 兜底只带 EXTRA_TAG，从 Tag 的 NDEF 缓存读取
    private fun resolveNdefMessages(intent: Intent): List<NdefMessage> {
        readNdefMessagesExtra(intent)
            ?.let { arr -> return arr.filterIsInstance<NdefMessage>() }
        val tag = readTagExtra(intent) ?: return emptyList()
        val ndef = Ndef.get(tag) ?: return emptyList()
        // 系统分发时已读过标签，Tag 对象通常携带 NDEF 缓存
        ndef.cachedNdefMessage?.let { return listOf(it) }
        return emptyList()
    }

    // Parcelable 读取兼容：API 33 起非弃用重载为按类型读取
    @Suppress("DEPRECATION")
    private fun readNdefMessagesExtra(intent: Intent): Array<out Parcelable?>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, Parcelable::class.java)
        } else {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }

    @Suppress("DEPRECATION")
    private fun readTagExtra(intent: Intent): Tag? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

    override fun onDestroy() {
        if (ActivityHolder.current === this) {
            ActivityHolder.current = null
        }
        super.onDestroy()
    }
}
