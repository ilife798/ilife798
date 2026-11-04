package com.github.ilife798.data.api

import kotlinx.serialization.Serializable

private const val MISSION_EXEC_TYPE = 101
private const val REFUND_TYPE = 23
private const val EXCHANGE_TYPE = 1
private const val RECHARGE_CATA = 1
private const val RECHARGE_NOTE = "钱包充值"

/*
 * score-send 的 addScoreType：服务端用它判断这笔分是怎么来的。
 *   1 = 每日签到
 *   2 = 视频任务（adId 1705776998）
 *   4 = 其余广告/资讯类任务
 * 取值来自官方 App 抓包实测；缺这个字段服务端会拒收。
 */
private const val ADD_SCORE_TYPE_SIGN_IN = 1
private const val ADD_SCORE_TYPE_VIDEO = 2
private const val ADD_SCORE_TYPE_MISSION = 4
private const val VIDEO_TASK_AD_ID = "1705776998"

@Serializable
data class SmsCodeRequest(
    val un: String,
    val authCode: String,
    val s: String,
)

@Serializable
data class LoginRequest(
    val openCode: String = "",
    val authCode: String,
    val un: String,
    val cid: String,
)

@Serializable
data class SetUseScoreRequest(
    val useScore: Int,
)

/*
 * acc/upt 的其余分支：与 setUseScore 共用端点，靠 type 区分操作类型。
 * 昵称 type 1、校验原密码 type 2、设置密码 type 3（取值来自官方 PasswordSettingActivity）。
 * 服务端靠 type 分流，其余字段显式传空字符串而非 null，避免被判为空值校验失败。
 */
@Serializable
data class ModifyUserInfoRequest(
    val name: String? = null,
    val newPayPwd: String? = null,
    val oriPayPwd: String? = null,
    val type: Int,
)

object ModifyUserInfoType {
    const val NAME = 1
    const val ORI_PAY_PWD = 2
    const val PAY_PWD = 3
}

/*
 * score-send 请求体。App 通道（1,1）额外要求 addScore / addScoreType，
 * 小程序通道（1,5）只发 adId（加 type 或 weekday），因此这两项可空，
 * 由调用方按通道决定是否填充——用 null 时序列化会省略该字段。
 */
@Serializable
data class MissionExecRequest(
    val adId: String,
    val type: Int = MISSION_EXEC_TYPE,
    val addScore: Int? = null,
    val addScoreType: Int? = null,
) {
    companion object {
        fun forChannel(
            adId: String,
            score: Int,
            isApp: Boolean,
        ): MissionExecRequest =
            if (isApp) {
                MissionExecRequest(
                    adId = adId,
                    addScore = score,
                    addScoreType = if (adId == VIDEO_TASK_AD_ID) ADD_SCORE_TYPE_VIDEO else ADD_SCORE_TYPE_MISSION,
                )
            } else {
                MissionExecRequest(adId = adId)
            }
    }
}

/*
 * 签到请求体。
 *
 * ★ 字段名按通道变化（下游 dong-dong6/ilife798 v1.3.15 实测）：
 *   App 通道（1,1）用 "weekday"（全小写）
 *   小程序通道（1,5）用 "weekDay"（驼峰）
 * 写错字段名服务端会静默忽略，表现为「请求成功但不生效」，极难排查。
 *
 * 因此不能用 @SerialName 固定一个名字，改用手动拼 JSON（见 forChannel）。
 * addScore / addScoreType 只有 App 通道才发，小程序通道只发 weekday/adId。
 */
data class SignInRequest(
    val weekDay: Int,
    val adId: String,
    val addScore: Int? = null,
    val addScoreType: Int? = null,
    val isApp: Boolean = true,
) {
    companion object {
        fun forChannel(
            weekDay: Int,
            adId: String,
            score: Int,
            isApp: Boolean,
        ): SignInRequest =
            if (isApp) {
                SignInRequest(weekDay = weekDay, adId = adId, addScore = score, addScoreType = ADD_SCORE_TYPE_SIGN_IN, isApp = true)
            } else {
                SignInRequest(weekDay = weekDay, adId = adId, isApp = false)
            }
    }

    // 手动拼 JSON，保证字段名严格按通道取值
    fun toJson(): String {
        val weekKey = if (isApp) "weekday" else "weekDay"
        val sb = StringBuilder("{")
        sb.append("\"$weekKey\":").append(weekDay)
        sb.append(",\"adId\":\"").append(adId).append('"')
        if (isApp) {
            addScore?.let { sb.append(",\"addScore\":").append(it) }
            addScoreType?.let { sb.append(",\"addScoreType\":").append(it) }
        }
        sb.append('}')
        return sb.toString()
    }
}

@Serializable
data class RefundWalletRequest(
    val eid: String,
    val type: Int = REFUND_TYPE,
)

@Serializable
data class ExchangeScoreRequest(
    val ep: EndpointRef,
    val score: Int,
    val type: Int = EXCHANGE_TYPE,
)

@Serializable
data class EndpointRef(
    val id: String,
)

@Serializable
data class ProductRef(
    val id: String,
    val count: Int,
)

@Serializable
data class CreateRechargeOrderRequest(
    val cata: Int = RECHARGE_CATA,
    val contact: EndpointRef,
    val ep: EndpointRef,
    val note: String = RECHARGE_NOTE,
    val owner: EndpointRef,
    val prds: List<ProductRef>,
)

// 云卡绑定/修改请求体，对应官方 CardBindParm
@Serializable
data class CardBindRequest(
    val id: String,
    val name: String = "",
    val token: String = "",
    val limit: Double = 0.0,
    val otherEp: Int = 0,
)

// 云卡解绑/激活请求体，官方为单键 Map：{"id": 卡ID}
@Serializable
data class CardRefRequest(
    val id: String,
)
