package com.github.ilife798.data.api

import com.github.ilife798.util.currentTimeMillis
import com.github.ilife798.util.md5

object Signer {
    /*
     * 登录签名：md5("authCode={code}&un={phone}{盐}")，盐用 App 通道的 SIGN_SALT。
     *
     * ⚠ 实测结论（2026-11-05，直连 i.ilife798.com 验证）：
     *   acc/login **不校验 sign**。同一手机号+错误短信码，带不带 sign、
     *   带假 sign、VersionCode 3.1.9 还是 3.1.10，四种情况返回完全一致的
     *   业务错误 {"code":-1,"msg":"短信验证码错误"}。
     *   服务端只要认得请求就会进入业务校验，验签层并不拦截登录。
     *
     * 因此本函数目前不是登录的必需品，保留为与官方 App 保持一致的实现
     * （官方确实会带这个 sign，只是服务端不校验）。
     */
    fun login(
        phone: String,
        code: String,
    ): String = md5("authCode=$code&un=$phone${ApiConfig.salt}")

    /*
     * 任务签名：md5(adId + 时间桶 + token 尾 8 位 + uid 尾 8 位 + 盐)，盐按通道取。
     * 时间桶 = 30 * (nowMs / 30000)，即 30 秒粒度。
     *
     * 这是唯一被服务端强制校验的签名（acc/score/score-send）。
     * now 参数允许调用方传入已校准的时间（见 IlifeApi.appTimeOffsetMillis）。
     */
    fun task(
        adId: String,
        token: String,
        uid: String,
        isApp: Boolean,
        now: Long = currentTimeMillis(),
    ): String = signAt(adId, token, uid, now, if (isApp) ApiConfig.salt else ApiConfig.miniSalt)

    fun sign(
        adId: String,
        token: String,
        uid: String,
    ): String = signAt(adId, token, uid, currentTimeMillis())

    // 按通道签名：App 通道用 SIGN_SALT，小程序通道用 MINI_SIGN_SALT。
    // 两者不可互换，混用会被服务端判「验签失败」。
    fun signForChannel(
        adId: String,
        token: String,
        uid: String,
        isApp: Boolean,
    ): String = task(adId, token, uid, isApp, currentTimeMillis())

    internal fun signAt(
        adId: String,
        token: String,
        uid: String,
        timeMillis: Long,
        salt: String = ApiConfig.salt,
    ): String {
        val timeBucket = 30 * (timeMillis / 30000)
        val tokenTail = if (token.length >= 8) token.substring(token.length - 8) else token
        val uidTail = if (uid.length >= 8) uid.substring(uid.length - 8) else uid
        val raw = "$adId${timeBucket}${tokenTail}${uidTail}$salt"
        return md5(raw)
    }

    /*
     * 参数表签名：按 key 升序拼成 "k1=v1&k2=v2"，末尾追加盐后取 md5。
     *
     * ⚠ 实测结论（2026-11-05，直连 i.ilife798.com 验证）：
     *   acc/login **不校验 sign**。同一手机号+错误短信码，以下四种情况
     *   返回完全一致的业务错误 {"code":-1,"msg":"短信验证码错误"}：
     *     ① 不带 sign @VersionCode 3.1.9
     *     ② 假 sign（deadbeef…）@3.1.9
     *     ③ 不带 sign @3.1.10
     *     ④ 正确 sign @3.1.9
     *   服务端只要认得请求就会进入业务校验，验签层并不拦截登录。
     *
     * 因此本函数目前不是登录的必需品，保留为通用工具（若后续接口需要
     * 参数表签名可直接复用）。真正强制验签的是 acc/score/score-send，
     * 那里用的是 signAt() 的 adId+时间桶+token尾8+uid尾8+盐 公式，
     * 不是参数表格式。
     */
    fun signQuery(params: Map<String, String>): String {
        val query =
            params.entries
                .sortedBy { it.key }
                .joinToString("&") { "${it.key}=${it.value}" }
        return md5(query + ApiConfig.salt)
    }
}
