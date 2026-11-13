package com.github.ilife798.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.github.ilife798.ApplicationContext
import com.github.ilife798.data.model.KeepAliveVendor
import com.github.ilife798.logDebug

// 按 Build 里的厂商 / 品牌字段识别系统。同一家厂商的 ROM 名称在不同机型上不一致，
// 所以品牌和厂商两边都看，识别不出来就归到类原生那一档。
actual fun keepAliveVendor(): KeepAliveVendor {
    val brand = Build.BRAND.orEmpty().lowercase()
    val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
    return when {
        brand.contains("redmi") ||
            brand.contains("poco") ||
            manufacturer.contains("xiaomi") -> KeepAliveVendor.XIAOMI

        brand.contains("oneplus") ||
            brand.contains("realme") ||
            brand.contains("oppo") ||
            manufacturer.contains("oppo") ||
            manufacturer.contains("oneplus") ||
            manufacturer.contains("realme") -> KeepAliveVendor.OPPO

        brand.contains("iqoo") ||
            brand.contains("vivo") ||
            manufacturer.contains("vivo") -> KeepAliveVendor.VIVO

        brand.contains("honor") ||
            manufacturer.contains("honor") -> KeepAliveVendor.HUAWEI

        brand.contains("huawei") ||
            manufacturer.contains("huawei") -> KeepAliveVendor.HUAWEI

        manufacturer.contains("samsung") -> KeepAliveVendor.SAMSUNG

        manufacturer.contains("meizu") -> KeepAliveVendor.MEIZU

        manufacturer.contains("asus") -> KeepAliveVendor.ASUS

        else -> KeepAliveVendor.AOSP
    }
}

/**
 * 跳到厂商的自启动 / 后台运行设置页。
 *
 * 这些页面都是各家私有的 Activity，没有公开 API；名字会随系统版本变，所以按「最可能到
 * 最不可能」的顺序逐个试，全都失败时退到应用详情页——那里至少能改电池策略。
 */
actual fun openKeepAliveSettings() {
    val context = runCatching { ApplicationContext.instance }.getOrNull() ?: return
    val candidates =
        when (keepAliveVendor()) {
            KeepAliveVendor.XIAOMI -> {
                listOf(
                    "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
                    "com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                )
            }

            KeepAliveVendor.OPPO -> {
                listOf(
                    "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
                    "com.oplus.safecenter/com.oplus.safecenter.startupapp.StartupAppListActivity",
                    "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
                )
            }

            KeepAliveVendor.VIVO -> {
                listOf(
                    "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                    "com.vivo.abe/com.vivo.abe.ui.BgStartUpManagerActivity",
                )
            }

            KeepAliveVendor.HUAWEI -> {
                listOf(
                    "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity",
                )
            }

            KeepAliveVendor.SAMSUNG -> {
                listOf(
                    "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
                    "com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity",
                )
            }

            KeepAliveVendor.MEIZU -> {
                listOf(
                    "com.meizu.safe/com.meizu.safe.permission.PermissionMainActivity",
                    "com.meizu.safe/com.meizu.safe.security.SHOW_APPSEC",
                )
            }

            KeepAliveVendor.ASUS,
            KeepAliveVendor.AOSP,
            -> {
                emptyList()
            }
        }
    for (candidate in candidates) {
        if (launchComponent(context, candidate)) return
    }
    // 没有可用入口就退到应用详情页：电池策略在这里一定改得到。
    val packageUri = "package:${context.packageName}".toUri()
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    if (startSafely(context, fallback)) return
    logDebug("ILife798", "openKeepAliveSettings: 无可用入口")
}

/** 打开最近任务界面，引导用户给应用加锁。 */
actual fun openRecentTasks() {
    val context = runCatching { ApplicationContext.instance }.getOrNull() ?: return
    val intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_APP_MESSAGING)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (startSafely(context, intent)) return
    // 部分系统没有可用的最近任务入口，退到设置首页不至于什么都不发生。
    val settings = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (startSafely(context, settings)) return
    logDebug("ILife798", "openRecentTasks: 无法打开最近任务")
}

private fun launchComponent(
    context: Context,
    component: String,
): Boolean {
    val parts = component.split('/')
    if (parts.size != 2) return false
    val intent =
        Intent()
            .setComponent(ComponentName(parts[0], parts[1]))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrElse {
        // 这些页面在不同版本里常被改名或移除，试不到就静默继续下一个
        false
    }
}

private fun startSafely(
    context: Context,
    intent: Intent,
): Boolean =
    runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrElse { e ->
        logDebug("ILife798", "start keep-alive settings failed: ${intent.action} ${e.message}")
        false
    }
