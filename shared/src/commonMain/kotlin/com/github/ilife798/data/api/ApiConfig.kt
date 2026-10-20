package com.github.ilife798.data.api

import com.github.ilife798.isIOS

// ApplicationType
const val APP_TYPE_DEVICE = "1,1" // 普通请求的固定值
const val APP_TYPE_POINTS = "1,5" // 积分登录（acc/login）签发积分 token，普通请求不携带

object ApiConfig {
    var baseUrl: String = ""
    var signSalt: String = ""
    var cid: String = ""
    const val VERSION_CODE: String = "3.1.9"
    val USER_AGENT: String = "${if (isIOS) "iOS" else "Android"}_ilife798_$VERSION_CODE"

    // 网络超时：连接 10s，单次请求 30s，读空闲 30s。
    // 服务端接口均为轻量 JSON，30s 足够；弱网下比引擎默认值更早失败，配合 withNetworkRetry 重试。
    const val CONNECT_TIMEOUT_MS: Long = 10_000
    const val REQUEST_TIMEOUT_MS: Long = 30_000
    const val SOCKET_TIMEOUT_MS: Long = 30_000

    fun init(
        gateway: String,
        salt: String,
        clientId: String,
    ) {
        baseUrl = gateway
        signSalt = salt
        cid = clientId
    }
}
