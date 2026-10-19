package com.github.ilife798.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val MISSION_EXEC_TYPE = 101
private const val REFUND_TYPE = 23
private const val EXCHANGE_TYPE = 1
private const val RECHARGE_CATA = 1
private const val RECHARGE_NOTE = "钱包充值"

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

@Serializable
data class MissionExecRequest(
    val adId: String,
    val type: Int = MISSION_EXEC_TYPE,
)

@Serializable
data class SignInRequest(
    @SerialName("weekday") val weekDay: Int,
    val adId: String,
)

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
