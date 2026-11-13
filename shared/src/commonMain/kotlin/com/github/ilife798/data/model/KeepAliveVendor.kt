package com.github.ilife798.data.model

/**
 * 各家定制系统对「后台保活」的叫法与入口都不一样，这里把它们统一成一份可展示的清单。
 *
 * 为什么需要这张表：Android 官方那套（电池优化白名单 + 前台服务）在类原生系统上够用，
 * 但在 MIUI / ColorOS / OriginOS / EMUI 这些系统里，还有一层厂商自己的「自启动 / 后台运行」
 * 开关，不打开的话进程照样会被冻结或杀掉，前台的保活服务也保不住。
 *
 * 这些开关没有统一 API 可以查询或修改，只能引导用户自己去点，所以这里只放「路径说明」，
 * 配合 [com.github.ilife798.util.openKeepAliveSettings] 尽量直接跳到对应页面。
 */
enum class KeepAliveVendor(
    /** 展示用的厂商名，例如「小米 / Redmi（MIUI、HyperOS）」。 */
    val title: String,
    /** 系统名称，用于说明文案，例如「MIUI / HyperOS」。 */
    val systemName: String,
    /** 需要用户依次打开的关键开关，按重要性排序。 */
    val steps: List<String>,
) {
    XIAOMI(
        title = "小米 / Redmi / POCO",
        systemName = "MIUI / HyperOS",
        steps =
            listOf(
                "设置 → 应用设置 → 应用管理 → 找到本应用 → 权限管理 → 自启动：打开",
                "设置 → 应用设置 → 应用管理 → 找到本应用 → 省电策略：改为「无限制」",
                "任务切换界面：下拉本应用卡片 → 点锁图标加锁，避免一键清理时被清掉",
            ),
    ),
    OPPO(
        title = "OPPO / 一加 / realme",
        systemName = "ColorOS / realme UI",
        steps =
            listOf(
                "设置 → 电池 → 应用耗电管理 → 找到本应用 → 允许「允许后台运行」",
                "设置 → 应用 → 自启动管理 → 找到本应用：打开",
                "设置 → 电池 → 更多设置 → 睡眠待机优化：关闭（否则息屏后会被冻结）",
            ),
    ),
    VIVO(
        title = "vivo / iQOO",
        systemName = "OriginOS / Funtouch OS",
        steps =
            listOf(
                "设置 → 电池 → 后台耗电管理 → 找到本应用 → 允许后台高耗电",
                "i管家 → 应用管理 → 自启动管理 → 找到本应用：打开",
                "设置 → 应用与权限 → 权限管理 → 自启动：允许",
            ),
    ),
    HUAWEI(
        title = "华为 / 荣耀",
        systemName = "EMUI / HarmonyOS / MagicOS",
        steps =
            listOf(
                "设置 → 应用 → 应用启动管理 → 找到本应用 → 关闭「自动管理」",
                "在弹出的手动管理里：允许自启动、允许关联启动、允许后台活动",
                "设置 → 电池 → 更多电池设置 → 休眠时始终保持网络连接：打开",
            ),
    ),
    SAMSUNG(
        title = "三星",
        systemName = "One UI",
        steps =
            listOf(
                "设置 → 电池 → 后台使用限制 → 从「休眠应用」列表移除本应用",
                "设置 → 应用 → 本应用 → 电池 → 改为「不受限制」",
                "设备维护 → 电池 → 应用电源管理：本应用设为「不限制」",
            ),
    ),
    MEIZU(
        title = "魅族",
        systemName = "Flyme",
        steps =
            listOf(
                "手机管家 → 权限管理 → 后台管理 → 本应用改为「允许后台运行」",
                "设置 → 应用管理 → 本应用 → 权限 → 自启动：打开",
            ),
    ),
    ASUS(
        title = "华硕 / 其他定制系统",
        systemName = "ZenUI 等",
        steps =
            listOf(
                "设置 → 电池 → 省电模式 → 本应用加入白名单 / 允许后台活动",
                "手机管家类应用里关闭对本应用的「自动清理」「深度清理」",
            ),
    ),
    AOSP(
        title = "原生 Android / Pixel / 类原生",
        systemName = "AOSP",
        steps =
            listOf(
                "设置 → 应用 → 本应用 → 电池 → 改为「不受限制」",
                "设置 → 应用 → 特殊应用权限 → 闹钟和提醒 → 允许本应用",
                "任务切换界面：给本应用卡片加锁，避免被一键清理",
            ),
    ),
    ;

    companion object {
        /** 按展示顺序排列，用于引导清单。 */
        val all: List<KeepAliveVendor> = entries
    }
}
