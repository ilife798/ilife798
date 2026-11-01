package com.github.ilife798.data.api

import com.github.ilife798.util.currentTimeMillis
import com.github.ilife798.util.md5

object Signer {
    fun login(
        phone: String,
        code: String,
    ): String {
        require(ApiConfig.appSignSalt.isNotBlank()) { "APP_SIGN_SALT 未配置" }
        return md5("authCode=$code&un=$phone${ApiConfig.appSignSalt}")
    }

    fun task(
        adId: String,
        token: String,
        uid: String,
        isApp: Boolean,
        now: Long,
    ): String {
        val salt = if (isApp) ApiConfig.appSignSalt else ApiConfig.miniSignSalt
        require(salt.isNotBlank()) { if (isApp) "APP_SIGN_SALT 未配置" else "MINI_SIGN_SALT 未配置" }
        val bucket = 30 * (now / 30000)
        return md5("$adId$bucket${token.takeLast(8)}${uid.takeLast(8)}$salt")
    }

    fun sign(
        adId: String,
        token: String,
        uid: String,
    ): String = signAt(adId, token, uid, currentTimeMillis())

    internal fun signAt(
        adId: String,
        token: String,
        uid: String,
        timeMillis: Long,
    ): String {
        val timeBucket = 30 * (timeMillis / 30000)
        val tokenTail = if (token.length >= 8) token.substring(token.length - 8) else token
        val uidTail = if (uid.length >= 8) uid.substring(uid.length - 8) else uid
        val raw = "$adId${timeBucket}${tokenTail}${uidTail}${ApiConfig.signSalt}"
        return md5(raw)
    }
}
