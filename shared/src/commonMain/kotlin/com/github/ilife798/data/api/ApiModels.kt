package com.github.ilife798.data.api

import com.github.ilife798.data.model.BillRecord
import com.github.ilife798.data.model.RefundProgress
import com.github.ilife798.data.model.WalletAccount

data class LoginResult(
    val success: Boolean,
    val token: String = "",
    val uid: String = "",
    val error: String = "",
)

data class AccountInfoDto(
    val id: String = "",
    val img: String = "",
    val name: String = "",
    val pn: String = "",
)

// 依据任务列表 stype 交集判定凭据所属渠道
enum class TokenPlatform {
    APP, // 设备控制（stype 交集只剩 1）
    MAIN, // 积分任务（stype 交集只剩 5）
    UNKNOWN,
}

data class TokenProbe(
    val appValid: Boolean,
    val mainValid: Boolean,
    val uid: String = "",
    val platform: TokenPlatform = TokenPlatform.UNKNOWN,
)

data class DeviceDto(
    val id: String,
    val name: String,
    val geneStatus: Int = 0,
    val dtype: Int = 0,
)

data class MasterResult(
    val accountId: String = "",
    val devices: List<DeviceDto> = emptyList(),
)

data class DevStatusResult(
    val deviceStatus: Int,
    val geneStatus: Int,
    // gene.time：会话预计结束时间（毫秒），随状态轮询刷新用于“剩余X分钟”
    val geneEndTime: Long = 0L,
)

data class DevResult(
    val success: Boolean,
    val code: Int,
    val message: String,
)

data class QrUseResult(
    val success: Boolean,
    val type: Int?,
    val deviceId: String,
)

data class MissionDto(
    val adId: String,
    val name: String,
    val score: Int,
    val limit: Int,
    val dailyCompleted: Int = 0,
    val isDailySignin: Boolean = false,
)

data class MissionListResult(
    val missions: List<MissionDto>,
    val validScore: Int? = null,
    val weekMask: Int = 0,
    val dailyAdId: String = "",
    val dailyScore: Int = 5,
    val totalScore: Int? = null,
)

data class ScoreDto(
    val score: Int,
    val name: String,
    val time: String,
    val adId: String = "",
)

data class ScoreListResult(
    val records: List<ScoreDto>,
    val total: Int,
)

data class WalletOwnerResult(
    val active: WalletAccount? = null,
    val wallets: List<WalletAccount> = emptyList(),
    val refundProgress: RefundProgress? = null,
)

data class BillListResult(
    val records: List<BillRecord>,
    val total: Int,
)
