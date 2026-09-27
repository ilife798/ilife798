package com.github.ilife798.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val phone: String = "",
    val isLoggedIn: Boolean = false,
    val pointsLoginDone: Boolean = false,
    val uid: String = "",
    val token: String = "",
    val appToken: String = "",
) {
    // 设备控制优先使用 appToken，积分与钱包复用同一登录态。
    val preferredToken: String get() = appToken.ifEmpty { token }

    val hasAnyToken: Boolean get() = preferredToken.isNotEmpty()
}

data class Device(
    val id: String,
    val name: String,
    val geneStatus: Int = 0,
    val deviceStatus: Int = 1,
    val dtype: Int = 0,
)

// 首页场景类型
enum class HomeDeviceType(
    val label: String,
    val deviceType: Int,
) {
    Laundry("洗烘", 10),
    HairDrying("吹风", 20),
    Drinking("饮水", 8),
    Shower("淋浴", 6),
    Other("其它", -1),
    ;

    companion object {
        fun fromDeviceType(dtype: Int): HomeDeviceType? = entries.firstOrNull { it.deviceType == dtype } ?: if (dtype > 0) Other else null
    }
}

// 设备可选项（来自 ui/app/dev/home/1 的 bm.parts）
data class DeviceOption(
    val mode: Int = -1,
    val name: String = "",
    val rate: Double = 0.0,
    val maxT: Int = 0,
)

// 货道（售货机/加液），对应 gs.items
data class DeviceGoods(
    val pos: Int = 0,
    val out: Int = 0,
)

// 设备通道状态（ui/app/dev/home/1 的 device.subs）
data class DeviceSubState(
    val status: Int? = null,
    val err: Int? = null,
    val isSelect: Boolean = false,
) {
    // err 非空且为 0、status==99 才可选
    val available: Boolean get() = err == 0 && status == 99
}

// 设备详情（ui/app/dev/home/1?apply=6 全量解析）
data class DeviceDetailInfo(
    val id: String = "",
    val name: String = "",
    val dtype: Int = 0,
    val deviceStatus: Int = 1,
    val geneStatus: Int = 0,
    // 预计结束时间（毫秒）
    val geneEndTime: Long = 0L,
    val enterpriseName: String = "",
    val enterpriseAbbr: String = "",
    val contactPhone: String = "",
    val parts: List<DeviceOption> = emptyList(),
    val subs: List<DeviceSubState> = emptyList(),
    val goods: List<DeviceGoods> = emptyList(),
    val sensors: List<Int> = emptyList(),
    // 固件版本（device.fmv）
    val fmv: String = "",
    // 网关类型（device.gtype，6=海尔网关）
    val gtype: Int = 0,
)

data class PointsInfo(
    val available: Int? = null,
    val total: Int? = null,
)

data class MissionInfo(
    val adId: String = "",
    val name: String = "",
    val score: Int = 0,
    val limit: Int = 1,
    val dailyCompleted: Int = 0,
    val isDailySignin: Boolean = false,
    val sourceToken: String = "",
)

data class ScoreRecord(
    val score: Int = 0,
    val name: String = "",
    val time: String = "",
)

enum class ScoreFilter(
    val src: Int?,
) {
    All(null),
    Income(101),
    Expense(105),
}

data class WalletAccount(
    val id: String = "",
    val eid: String = "",
    val ownerId: String = "",
    val name: String = "",
    val olCash: Double = 0.0,
    val olGift: Double = 0.0,
    val ofCash: Double = 0.0,
    val ofGift: Double = 0.0,
    val total: Double = 0.0,
    val auth: Boolean = false,
    val chargeEnabled: Boolean = true,
    val refundEnabled: Boolean = true,
) {
    val refundable: Double get() = if (auth) olCash + ofCash else olCash
}

data class RechargeProduct(
    val id: String = "",
    val name: String = "",
    val price: Double = 0.0,
    val originalPrice: Double = 0.0,
)

data class RefundProgress(
    val ctime: Long = -1L,
    val count: Int = 0,
    val total: Double = 0.0,
    val fail: Int? = null,
) {
    val active: Boolean get() = ctime != -1L
}

data class SpendingStats(
    val today: Double = 0.0,
    val yesterday: Double = 0.0,
    val monthAverage: Double = 0.0,
)

data class BillRecord(
    val id: String = "",
    val cata: Int = 0,
    val type: Int = 0,
    val msg: String = "",
    val status: Int = 0,
    val dir: Int = 1,
    val payment: Double = 0.0,
    val time: Long = 0L,
)

// 账单详情（bill/view-full 的 data.bill + data.cnt）
data class BillDetailInfo(
    val id: String = "",
    val cata: Int = 0,
    val type: Int = 0,
    val msg: String = "",
    val status: Int = 0,
    val dir: Int = 1,
    val payment: Double = 0.0,
    val discount: Double = 0.0,
    val ctime: Long = 0L,
    val utime: Long = 0L,
    val enterpriseName: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val deviceDtype: Int = 0,
    val couponCount: Int = 0,
    val promoName: String = "",
)

enum class ThemeMode { Light, Dark, System }

enum class PaletteStyle(
    val displayName: String,
) {
    TonalSpot("TonalSpot"),
    Neutral("Neutral"),
    Vibrant("Vibrant"),
    Expressive("Expressive"),
}

const val DEFAULT_SEED_COLOR: Int = 0xFF6750A4.toInt()

@Serializable
data class AccountInfo(
    val img: String = "",
    val name: String = "",
    val pn: String = "",
)

// 账号列表条目：凭据 + 资料缓存
@Serializable
data class AccountEntry(
    val account: Account = Account(),
    val info: AccountInfo = AccountInfo(),
)

data class AppState(
    val account: Account = Account(),
    val accountList: List<AccountEntry> = emptyList(),
    val currentAccountIndex: Int = -1,
    val devices: List<Device> = emptyList(),
    val points: PointsInfo = PointsInfo(),
    val wallets: List<WalletAccount> = emptyList(),
    val activeWalletId: String = "",
    val billRecords: List<BillRecord> = emptyList(),
    val taskCompleted: Boolean = false,
    val taskLogs: List<String> = emptyList(),
    val dynamicColor: Boolean = true,
    val customColor: Boolean = true,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val seedColor: Int = DEFAULT_SEED_COLOR,
    val floatingNav: Boolean = false,
    val appBlur: Boolean = true,
    val predictiveBackEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    val accountInfo: AccountInfo = AccountInfo(),
    val weekMask: Int = 0,
    val disclaimerAcknowledged: Boolean = false,
)
