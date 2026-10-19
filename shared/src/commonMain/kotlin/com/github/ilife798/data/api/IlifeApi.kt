package com.github.ilife798.data.api

import com.github.ilife798.data.model.BillDetailInfo
import com.github.ilife798.data.model.BillRecord
import com.github.ilife798.data.model.CloudCard
import com.github.ilife798.data.model.DeviceDetailInfo
import com.github.ilife798.data.model.DeviceGoods
import com.github.ilife798.data.model.DeviceOption
import com.github.ilife798.data.model.DeviceSubState
import com.github.ilife798.data.model.PayChannel
import com.github.ilife798.data.model.PayChannelType
import com.github.ilife798.data.model.RechargeProduct
import com.github.ilife798.data.model.RefundProgress
import com.github.ilife798.data.model.WalletAccount
import com.github.ilife798.util.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 时间戳毫秒/秒归一阈值（约 1973-03，秒级远小于此值、毫秒级远大于此值）
private const val MILLIS_THRESHOLD = 100_000_000_000L

// 响应体缺少 code 字段时的哨兵值，与任何真实业务码都不相等
internal const val CODE_UNKNOWN = Int.MIN_VALUE

// 绑卡成功
internal const val CODE_OK = 0

// ic/bind 专用：该卡需要「绑卡密钥」，客户端应提示补填后重提
internal const val CODE_CARD_KEY_REQUIRED = -15

// 请求携带的 token，用于会话失效回调归属判断
private val requestAuthTokenKey = AttributeKey<String>("ilife798.request.auth.token")

class IlifeApi(
    private val client: HttpClient = createHttpClient(),
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    // 请求体序列化：保留默认值字段，保证与服务端约定一致
    private val requestJson = Json { encodeDefaults = true }

    // 统一拼接 baseUrl 的请求入口；两侧斜杠归一化，兼容 baseUrl/path 带不带 "/"
    private fun apiUrl(path: String): String = ApiConfig.baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private suspend fun HttpClient.apiGet(
        path: String,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse = get(apiUrl(path), block)

    private suspend fun HttpClient.apiPost(
        path: String,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse = post(apiUrl(path), block)

    // 鉴权与平台标识请求头
    private fun HttpRequestBuilder.apiHeaders(
        token: String,
        appType: String,
    ) {
        header("Authorization", token)
        header("ApplicationType", appType)
        attributes.put(requestAuthTokenKey, token)
    }

    // 服务端在登录态失效时对所有需鉴权接口返回 code = -99；回调携带发起请求的 token，
    // 供上层区分是当前账号过期还是切换后旧请求的残留信号
    var onSessionExpired: ((String) -> Unit)? = null

    // 接口返回非 0 业务码时回调服务端错误信息，供上层直接以 Toast 展示
    var onApiError: ((String) -> Unit)? = null

    private suspend fun bodyJson(
        response: HttpResponse,
        notifyExpiry: Boolean = true,
        reportError: Boolean = true,
    ): JsonObject {
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        if (notifyExpiry) {
            checkSessionExpired(
                body,
                response.call.request.attributes
                    .getOrNull(requestAuthTokenKey),
            )
        }
        if (reportError) reportIfError(body)
        return body
    }

    private fun checkSessionExpired(
        body: JsonObject,
        token: String?,
    ) {
        if (body.intOrNull("code") == -99) onSessionExpired?.invoke(token ?: "")
    }

    private fun reportIfError(body: JsonObject) {
        val code = body.int("code")
        if (code == 0 || code == -99) return
        emitApiError(body.strOrNull("msg"))
    }

    private fun emitApiError(msg: String?) {
        val text = msg?.trim().orEmpty()
        if (text.isNotEmpty()) onApiError?.invoke(text)
    }

    // 图形验证码
    suspend fun getCaptcha(key: String): ByteArray {
        val timestamp = currentTimeMillis()
        val response =
            client.apiGet("captcha/") {
                parameter("s", key)
                parameter("r", timestamp)
            }
        return response.bodyAsBytes()
    }

    fun newCaptchaKey(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..10).map { chars.random() }.joinToString("")
    }

    // 登录
    suspend fun sendSmsCode(
        phone: String,
        graphCode: String,
        captchaKey: String,
    ): String =
        try {
            val response =
                client.apiPost("acc/login/code") {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson.encodeToString(SmsCodeRequest(phone, graphCode, captchaKey)))
                }
            response.bodyAsText()
        } catch (e: Exception) {
            """{"code":-1,"msg":"${e.message}"}"""
        }

    suspend fun login(
        phone: String,
        smsCode: String,
        isAlipay: Boolean,
    ): LoginResult {
        val appType = if (isAlipay) APP_TYPE_POINTS else APP_TYPE_DEVICE
        val response =
            client.apiPost("acc/login") {
                contentType(ContentType.Application.Json)
                setBody(requestJson.encodeToString(LoginRequest(authCode = smsCode, un = phone, cid = ApiConfig.cid)))
                header("ApplicationType", appType)
            }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val code = body.int("code", -1)
        if (code != 0) return LoginResult(false, error = body.str("msg", "登录失败"))
        val al = body.obj("data")?.obj("al")
        val token = al?.str("token") ?: ""
        val uid = al?.str("uid") ?: ""
        return LoginResult(true, token = token, uid = uid)
    }

    // 账号信息
    suspend fun getAccountInfo(
        token: String,
        notifyExpiry: Boolean = true,
        appType: String = APP_TYPE_DEVICE,
    ): AccountInfoDto? {
        val response =
            client.apiGet("ui/app/master") {
                apiHeaders(token, appType)
            }
        val body = bodyJson(response, notifyExpiry, reportError = false)
        val account = body.obj("data")?.obj("account") ?: return null
        return AccountInfoDto(
            id = account.str("id"),
            img = account.str("img"),
            name = account.str("name"),
            pn = account.str("pn"),
        )
    }

    // 探测一条登录信息的有效性，用于手动导入校验；不触发会话失效登出
    suspend fun probeToken(token: String): TokenProbe {
        var platform = TokenPlatform.UNKNOWN
        val appInfo =
            try {
                getAccountInfo(token, notifyExpiry = false)
            } catch (_: Exception) {
                null
            }
                // 积分 token 在设备渠道可能取不到账号信息，换积分渠道再试，保证能拿到 uid 做同账号校验
                ?: try {
                    getAccountInfo(token, notifyExpiry = false, appType = APP_TYPE_POINTS)
                } catch (_: Exception) {
                    null
                }
        val mainValid =
            try {
                val response =
                    client.apiGet("acc/score/mission-lst") {
                        apiHeaders(token, APP_TYPE_POINTS)
                    }
                val body = bodyJson(response, notifyExpiry = false, reportError = false)
                val ok = body.intOrNull("code") == 0
                if (ok) platform = missionPlatform(body)
                ok
            } catch (_: Exception) {
                false
            }
        return TokenProbe(appValid = appInfo != null, mainValid = mainValid, uid = appInfo?.id ?: "", platform = platform)
    }

    // 渠道判定：取任务列表 stype 的交集，只剩 {1} 为设备控制、只剩 {5} 为积分任务，
    // 交集冲突/字段缺失/接口失败均判为无法确定
    private fun missionPlatform(body: JsonObject): TokenPlatform {
        val missions = body.obj("data")?.arr("missions")
        if (missions.isNullOrEmpty()) return TokenPlatform.UNKNOWN
        var applicable = setOf(1, 5)
        for (item in missions) {
            val obj = item as? JsonObject ?: return TokenPlatform.UNKNOWN
            val stype = obj.arr("stype") ?: return TokenPlatform.UNKNOWN
            val types =
                stype
                    .mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                    .toSet()
            if (types.isEmpty() || types.any { it != 1 && it != 5 }) return TokenPlatform.UNKNOWN
            applicable = applicable.intersect(types)
            if (applicable.isEmpty()) return TokenPlatform.UNKNOWN
        }
        return when (applicable.singleOrNull()) {
            1 -> TokenPlatform.APP
            5 -> TokenPlatform.MAIN
            else -> TokenPlatform.UNKNOWN
        }
    }

    // 探测一条登录信息是否已失效
    // true = 明确失效（code = -99，或响应成功但不再返回账号数据），false = 有效（或非失效类业务码），null = 网络失败无法判定
    suspend fun isTokenExpired(token: String): Boolean? =
        try {
            val response =
                client.apiGet("ui/app/master") {
                    apiHeaders(token, APP_TYPE_DEVICE)
                }
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val code = body.intOrNull("code")
            val hasAccount =
                body
                    .obj("data")
                    ?.obj("account")
                    ?.str("id")
                    ?.isNotEmpty() == true
            when (code) {
                -99 -> true
                0 if !hasAccount -> true
                else -> false
            }
        } catch (_: Exception) {
            null
        }

    // 账号设置
    suspend fun setUseScore(
        token: String,
        value: Int = 1,
        appType: String = APP_TYPE_DEVICE,
    ): DevResult {
        val response =
            client.apiPost("acc/upt") {
                contentType(ContentType.Application.Json)
                setBody(requestJson.encodeToString(SetUseScoreRequest(value)))
                apiHeaders(token, appType)
            }
        return parseDevResult(response.bodyAsText(), reportError = false, token = token)
    }

    // 修改昵称：acc/upt 的 name 分支，type=1
    suspend fun modifyName(
        token: String,
        name: String,
        appType: String = APP_TYPE_DEVICE,
    ): DevResult =
        submitUserInfo(
            token = token,
            body =
                ModifyUserInfoRequest(
                    name = name,
                    newPayPwd = "",
                    oriPayPwd = "",
                    type = ModifyUserInfoType.NAME,
                ),
            appType = appType,
        )

    // 交易密码第一步：校验原密码是否通过，type=2
    suspend fun verifyPayPwd(
        token: String,
        oriPayPwd: String,
        appType: String = APP_TYPE_DEVICE,
    ): DevResult =
        submitUserInfo(
            token = token,
            body =
                ModifyUserInfoRequest(
                    name = "",
                    newPayPwd = oriPayPwd,
                    oriPayPwd = oriPayPwd,
                    type = ModifyUserInfoType.ORI_PAY_PWD,
                ),
            appType = appType,
        )

    // 交易密码第二步：校验通过后写新密码，type=3
    suspend fun changePayPwd(
        token: String,
        oriPayPwd: String,
        newPayPwd: String,
        appType: String = APP_TYPE_DEVICE,
    ): DevResult =
        submitUserInfo(
            token = token,
            body =
                ModifyUserInfoRequest(
                    name = "",
                    newPayPwd = newPayPwd,
                    oriPayPwd = oriPayPwd,
                    type = ModifyUserInfoType.PAY_PWD,
                ),
            appType = appType,
        )

    private suspend fun submitUserInfo(
        token: String,
        body: ModifyUserInfoRequest,
        appType: String,
    ): DevResult {
        val response =
            client.apiPost("acc/upt") {
                contentType(ContentType.Application.Json)
                setBody(requestJson.encodeToString(body))
                apiHeaders(token, appType)
            }
        return parseDevResult(response.bodyAsText(), reportError = false, token = token)
    }

    // 设备
    suspend fun getMasterDevices(token: String): MasterResult {
        val response =
            client.apiGet("ui/app/master") {
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = bodyJson(response, reportError = false)
        val data = body.obj("data") ?: return MasterResult()
        val accountId = data.obj("account")?.str("id") ?: ""
        val favos = data.arr("favos") ?: return MasterResult(accountId)
        val devices =
            favos.map { item ->
                val obj = item.jsonObject
                val gene = obj.obj("gene")
                val geneStatus = gene?.intOrNull("status") ?: 0
                val dtype = obj.obj("bm")?.intOrNull("dtype") ?: 0
                DeviceDto(
                    id = obj.str("id"),
                    name = obj.str("name"),
                    geneStatus = geneStatus,
                    dtype = dtype,
                )
            }
        return MasterResult(accountId, devices)
    }

    suspend fun devStart(
        token: String,
        did: String,
        appType: String = APP_TYPE_DEVICE,
        ptype: Int = 91,
        args: String = "",
        reportError: Boolean = true,
    ): DevResult {
        val response =
            client.apiGet("dev/start") {
                parameter("did", did)
                parameter("upgrade", "true")
                parameter("ptype", ptype.toString())
                parameter("args", args)
                parameter("rcp", "false")
                parameter("cnt", "1")
                apiHeaders(token, appType)
            }
        return parseDevResult(response.bodyAsText(), reportError, token = token)
    }

    suspend fun devEnd(
        token: String,
        did: String,
        appType: String = APP_TYPE_DEVICE,
        reportError: Boolean = true,
    ): DevResult {
        val response =
            client.apiGet("dev/end") {
                parameter("did", did)
                parameter("rcp", "false")
                apiHeaders(token, appType)
            }
        return parseDevResult(response.bodyAsText(), reportError = reportError, token = token)
    }

    suspend fun devFavo(
        token: String,
        did: String,
        remove: Boolean,
    ): DevResult {
        val response =
            client.apiGet("dev/favo") {
                parameter("did", did)
                parameter("remove", if (remove) "1" else "0")
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        return parseDevResult(response.bodyAsText(), token = token)
    }

    // 扫码：用二维码中解析出的 id 换取设备信息
    suspend fun qrUse(
        token: String,
        id: String,
    ): QrUseResult {
        val response =
            client.apiGet("qr/use") {
                parameter("id", id)
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = bodyJson(response)
        val code = body.int("code", -1)
        val data = body.obj("data")
        val type = data?.obj("qr")?.intOrNull("type")
        val deviceId = data?.obj("dev")?.str("id") ?: ""
        return QrUseResult(success = code == 0, type = type, deviceId = deviceId)
    }

    suspend fun getDevStatus(
        token: String,
        did: String,
        appType: String = APP_TYPE_DEVICE,
    ): DevStatusResult? {
        val response =
            client.apiGet("ui/app/dev/status") {
                parameter("did", did)
                parameter("more", "false")
                apiHeaders(token, appType)
            }
        val body = bodyJson(response, reportError = false)
        if (body.intOrNull("code") != 0) return null
        val device = body.obj("data")?.obj("device") ?: return null
        val gene = device.obj("gene")
        return DevStatusResult(
            deviceStatus = device.int("status"),
            geneStatus = gene?.intOrNull("status") ?: 0,
            geneEndTime = timeToMillis(gene?.long("time") ?: 0L),
        )
    }

    // 设备详情页数据：官方详情页初始化走 home/1?apply=6（BaseDeviceDetailActivity.d(id, 6)），
    // dev/status?more=true 是状态轮询、不含 ep/payItems 等展示字段
    suspend fun getDeviceDetail(
        token: String,
        did: String,
        appType: String = APP_TYPE_DEVICE,
    ): DeviceDetailInfo? {
        val response =
            client.apiGet("ui/app/dev/home/1") {
                parameter("did", did)
                parameter("apply", 6)
                apiHeaders(token, appType)
            }
        val body = bodyJson(response, reportError = false)
        if (body.intOrNull("code") != 0) return null
        val data = body.obj("data") ?: return null
        val device = data.obj("device") ?: return null
        val gene = device.obj("gene")
        val bm = device.obj("bm")
        val ep = device.obj("ep")
        val parts =
            bm?.arr("parts")?.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                DeviceOption(
                    mode = obj.int("mode", -1),
                    name = obj.str("name"),
                    rate = obj.double("rate"),
                    maxT = obj.int("maxT"),
                )
            } ?: emptyList()
        val subs =
            device.arr("subs")?.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val selected = obj.str("isSelect")
                DeviceSubState(
                    status = obj.intOrNull("status"),
                    err = obj.intOrNull("err"),
                    isSelect = selected == "1" || selected == "true",
                )
            } ?: emptyList()
        val goods =
            device.obj("gs")?.arr("items")?.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                DeviceGoods(pos = obj.int("pos"), out = obj.int("out"))
            } ?: emptyList()
        val sensors =
            bm?.arr("sensors")?.mapNotNull { item ->
                (item as? JsonPrimitive)?.content?.toIntOrNull()
            } ?: emptyList()
        return DeviceDetailInfo(
            id = device.str("id", did),
            name = device.str("name"),
            dtype = bm?.int("dtype") ?: 0,
            deviceStatus = device.int("status", 1),
            geneStatus = gene?.int("status") ?: 0,
            geneEndTime = timeToMillis(gene?.long("time") ?: 0L),
            enterpriseName = ep?.str("name") ?: "",
            enterpriseAbbr = ep?.str("abbr") ?: "",
            contactPhone = ep?.obj("contact")?.str("pn") ?: "",
            parts = parts,
            subs = subs,
            goods = goods,
            sensors = sensors,
            fmv = device.str("fmv", ""),
            gtype = device.int("gtype", 0),
        )
    }

    // 服务端部分时间戳是秒级，统一归一为毫秒后再参与比较或格式化。
    // 用于 gene.time 与云卡的 atime/validTime 等字段。
    private fun timeToMillis(value: Long): Long =
        when {
            value <= 0L -> 0L
            value < MILLIS_THRESHOLD -> value * 1000
            else -> value
        }

    private fun parseDevResult(
        raw: String,
        reportError: Boolean = true,
        token: String?,
    ): DevResult {
        val body = json.parseToJsonElement(raw).jsonObject
        checkSessionExpired(body, token)
        val code = body.int("code", -1)
        val msg = body.str("msg")
        if (reportError && code != 0 && code != -99) emitApiError(msg)
        return DevResult(code == 0, code, msg)
    }

    // 积分与任务
    suspend fun getMissionList(token: String): MissionListResult {
        val response =
            client.apiGet("acc/score/mission-lst") {
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = bodyJson(response)
        val data = body.obj("data") ?: return MissionListResult(emptyList())
        val accScore = data.obj("accScoreRsp")
        val validScore = accScore?.intOrNull("validScore")
        val totalScore = accScore?.intOrNull("totalScore")
        val weekMask = accScore?.obj("daily")?.intOrNull("week") ?: 0
        val dailyRsp = data.obj("dailyRSP")
        val dailyAdId = dailyRsp?.str("adId") ?: ""
        val dailyScore = dailyRsp?.intOrNull("score") ?: 5

        // 官方 n0.b 语义：limits 中值为 -1 表示今日已达上限，按任务上限计入；负数一律视为已完成
        val limitsArray = accScore?.arr("limits") ?: emptyList()
        val dailyDoneMap = mutableMapOf<String, Int>()
        for (item in limitsArray) {
            val obj = item.jsonObject
            val refId = obj.str("refId")
            val limit = obj.int("limit")
            if (refId.isNotEmpty()) dailyDoneMap[refId] = limit
        }

        val missions =
            data.arr("missions") ?: return MissionListResult(
                emptyList(),
                validScore,
                weekMask,
                dailyAdId,
                dailyScore,
                totalScore,
            )
        val seen = mutableSetOf<String>()
        val list =
            missions.mapNotNull { item ->
                val obj = item.jsonObject
                val refId = obj.str("refId")
                if (refId.isBlank() || !seen.add(refId)) return@mapNotNull null
                val score = obj.int("score")
                val limit = obj.int("limit")
                if (limit <= 0) return@mapNotNull null
                val rawDone = dailyDoneMap[refId]
                MissionDto(
                    adId = refId,
                    name = obj.strOrNull("name") ?: obj.strOrNull("title") ?: "任务",
                    score = score,
                    limit = limit,
                    dailyCompleted =
                        when {
                            rawDone == null -> 0
                            rawDone < 0 -> limit
                            else -> rawDone
                        },
                )
            }

        val allMissions =
            if (dailyAdId.isNotBlank()) {
                listOf(MissionDto(adId = dailyAdId, name = "每日签到", score = dailyScore, limit = 1, isDailySignin = true)) + list
            } else {
                list
            }

        return MissionListResult(allMissions, validScore, weekMask, dailyAdId, dailyScore, totalScore)
    }

    suspend fun executeMission(
        token: String,
        uid: String,
        adId: String,
    ): DevResult = postScoreSend(token, uid, adId, requestJson.encodeToString(MissionExecRequest(adId)))

    suspend fun signIn(
        token: String,
        uid: String,
        weekDay: Int,
        adId: String,
    ): DevResult = postScoreSend(token, uid, adId, requestJson.encodeToString(SignInRequest(weekDay, adId)))

    // 执行任务与签到共用的 score-send 请求
    private suspend fun postScoreSend(
        token: String,
        uid: String,
        adId: String,
        body: String,
    ): DevResult {
        val sign = Signer.sign(adId, token, uid)
        val response =
            client.apiPost("acc/score/score-send?sign=$sign&s=1") {
                contentType(ContentType.Application.Json)
                setBody(body)
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        return parseDevResult(response.bodyAsText(), reportError = false, token = token)
    }

    suspend fun getScoreList(
        token: String,
        page: Int = 0,
        size: Int,
        src: Int? = null,
    ): ScoreListResult {
        val response =
            client.apiGet("acc/score/score-lst") {
                parameter("page", page.toString())
                parameter("size", size.toString())
                parameter("hasCount", "1")
                if (src != null) parameter("src", src.toString())
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = bodyJson(response)
        val data = body.arr("data") ?: return ScoreListResult(emptyList(), 0)
        val total = body.intOrNull("size") ?: data.size
        val records =
            data.map { item ->
                val obj = item.jsonObject
                val nested = obj.obj("data")
                val score = readScoreInt(obj, nested)
                val adId = nested?.str("adId") ?: ""
                val isExpense = obj.intOrNull("type") == 107 || nested?.containsKey("spend") == true
                ScoreDto(
                    score = if (isExpense) -score else score,
                    name =
                        nested?.strOrNull("adName")
                            ?: obj.strOrNull("msg")
                            ?: obj.strOrNull("typeName")
                            ?: "积分变动",
                    time = obj.str("ctime"),
                    adId = adId,
                )
            }
        return ScoreListResult(records, total)
    }

    // 钱包与账单
    suspend fun getWalletOwner(token: String): WalletOwnerResult {
        val response =
            client.apiGet("acc/wallet/owner") {
                parameter("all", "true")
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = bodyJson(response)
        if (body.intOrNull("code") != 0) return WalletOwnerResult()
        val data = body.obj("data") ?: return WalletOwnerResult()
        val active = data.obj("aw")?.let { parseWallet(it) }
        val eps = data.arr("eps")?.filterIsInstance<JsonObject>()?.map { parseWallet(it) } ?: emptyList()
        val refundProgress =
            data.obj("rfdProg")?.let {
                RefundProgress(
                    ctime = it.long("ctime", -1L),
                    count = it.int("count"),
                    total = it.double("total"),
                    fail = it.intOrNull("fail"),
                )
            }
        return WalletOwnerResult(active, eps, refundProgress)
    }

    suspend fun getWalletDetail(
        token: String,
        id: String,
        eid: String,
    ): WalletAccount? {
        if (id.isEmpty()) return null
        val response =
            client.apiGet("acc/wallet/detail") {
                parameter("id", id)
                if (eid.isNotEmpty()) parameter("eid", eid)
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = bodyJson(response)
        if (body.intOrNull("code") != 0) return null
        val data = body.obj("data") ?: return null
        return parseWallet(data)
    }

    private fun parseWallet(obj: JsonObject): WalletAccount {
        val ep = obj.obj("ep")
        val setting = ep?.obj("setting")
        val owner = obj.obj("owner")
        return WalletAccount(
            id = obj.str("id"),
            eid = ep?.str("id") ?: "",
            ownerId = owner?.str("id") ?: "",
            name = ep?.strOrNull("name") ?: obj.strOrNull("name") ?: "",
            olCash = obj.double("olCash"),
            olGift = obj.double("olGift"),
            ofCash = obj.double("ofCash"),
            ofGift = obj.double("ofGift"),
            total = obj.double("total"),
            auth = obj.bool("auth"),
            chargeEnabled = (setting?.intOrNull("olcharge") ?: 1) == 1,
            refundEnabled = (setting?.intOrNull("olrefund") ?: 1) == 1,
        )
    }

    suspend fun getBillList(
        token: String,
        page: Int = 0,
        size: Int,
        status: Int,
    ): BillListResult {
        val response =
            client.apiGet("bill/lst-owner") {
                parameter("page", page.toString())
                parameter("size", size.toString())
                parameter("hasCount", (page == 0).toString())
                parameter("status", status.toString())
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = bodyJson(response)
        val data = body.arr("data") ?: return BillListResult(emptyList(), 0)
        val total = body.intOrNull("size") ?: data.size
        val records =
            data.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                BillRecord(
                    id = obj.str("id"),
                    cata = obj.int("cata"),
                    type = obj.int("type"),
                    msg = obj.str("msg"),
                    status = obj.int("status"),
                    dir = obj.int("dir", 1),
                    payment = obj.double("payment"),
                    time = obj.longOrNull("utime") ?: obj.longOrNull("ctime") ?: 0L,
                )
            }
        return BillListResult(records, total)
    }

    suspend fun getRechargeProducts(
        token: String,
        eid: String,
    ): List<RechargeProduct> {
        if (eid.isEmpty()) return emptyList()
        val response =
            client.apiGet("prd/lst") {
                parameter("eid", eid)
                parameter("type", "1")
                parameter("status", "1")
                parameter("all", "false")
                parameter("did", "")
                parameter("page", "0")
                parameter("size", "100")
                parameter("hasCount", "false")
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = bodyJson(response)
        val data = body.arr("data") ?: return emptyList()
        return data
            .mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = obj.str("id")
                if (id.isEmpty()) return@mapNotNull null
                RechargeProduct(
                    id = id,
                    name = obj.str("name", "充值"),
                    price = obj.double("curPrice"),
                    originalPrice = obj.double("ogiPrice"),
                )
            }.distinctBy { "${it.name}|${it.price}|${it.originalPrice}" }
    }

    suspend fun createRechargeOrder(
        token: String,
        eid: String,
        ownerId: String,
        productId: String,
    ): String? {
        if (eid.isEmpty() || ownerId.isEmpty() || productId.isEmpty()) return null
        val order =
            CreateRechargeOrderRequest(
                contact = EndpointRef(ownerId),
                ep = EndpointRef(eid),
                owner = EndpointRef(ownerId),
                prds = listOf(ProductRef(productId, count = 1)),
            )
        val response =
            client.apiPost("bill/save") {
                contentType(ContentType.Application.Json)
                setBody(requestJson.encodeToString(order))
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        checkSessionExpired(body, token)
        if (body.intOrNull("code") != 0) {
            emitApiError(body.strOrNull("msg"))
            return null
        }
        return body.strOrNull("data")?.takeIf { it.isNotEmpty() && it != "null" }
    }

    /**
     * 查询某个账单可用的支付渠道（`GET /api/v1/bill/pay/channels?id={billId}`）。
     *
     * 官方在此之后还会用 6 项硬编码白名单过滤一遍，过滤后为空就提示「没有可用的支付方式」。
     * 本端沿用同样的过滤，且只返回本端实现过的渠道（见 `PayChannelType.SUPPORTED`），
     * 云闪付/农行这类没有 SDK 的取值直接丢弃，不在界面上留下点不动的条目。
     *
     * 返回 null 表示接口调用失败（网络/服务端错误），返回空列表表示服务端明确没给渠道。
     * 两者触发的原因不同，上层提示文案也不同。
     */
    suspend fun getPayChannels(
        token: String,
        orderId: String,
    ): List<PayChannel>? {
        if (orderId.isEmpty()) return null
        val response =
            client.apiGet("bill/pay/channels") {
                parameter("id", orderId)
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = bodyJson(response)
        if (body.intOrNull("code") != 0) return null
        val channels = body.obj("data")?.arr("channels") ?: return emptyList()
        return channels
            .mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val type = obj.intOrNull("type") ?: return@mapNotNull null
                PayChannel(
                    type = type,
                    name =
                        obj.strOrNull("name")?.takeIf { it.isNotEmpty() }
                            ?: PayChannelType.fallbackLabel(type),
                )
            }
            // 只保留官方白名单内的取值；白名单外的一律不展示
            .filter { it.type in PayChannelType.OFFICIAL_WHITELIST }
            // 本端没有实现、也不打算实现的渠道（云闪付/农行）直接剔除，界面上不留灰条。
            // 微信这类「实现过但第三方资质受限」的渠道不在此列，由界面按运行时状态灰显。
            .filter { PayChannelType.isSupported(it.type) }
    }

    /**
     * 拉取预支付串：`GET /api/v1/trans/prepay/{type}?id={账单ID}`。
     *
     * 返回的 `data` 类型随渠道而异：支付宝（21）/ 一卡通（93）/ 云闪付（41）/ 农行（52）
     * 返回字符串（orderInfo / sign_params / 银联 tn），微信（11）返回 JSON 对象 `PayInfo`。
     * 因此这里原样交回 [JsonElement]，由调用方按渠道解析。
     */
    suspend fun prepay(
        token: String,
        orderId: String,
        payType: Int = PayChannelType.ALIPAY_APP,
    ): JsonElement? {
        if (orderId.isEmpty() || payType <= 0) return null
        val response =
            client.apiGet("trans/prepay/$payType") {
                parameter("id", orderId)
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = bodyJson(response)
        if (body.intOrNull("code") != 0) return null
        return body["data"]?.takeUnless { it is JsonNull }
    }

    /**
     * 微信支付可用性的服务端探测（`GET /trans/prepay/11?id={billId}`）。
     *
     * 当前**未被调用**：本端固定认为微信不可用（包名/签名未登记），直接走灰条。
     * 等微信开放平台资质补齐、`isWeChatPayAvailable()` 返回 true 后，由渠道预检接上本方法。
     *
     * 判定依据：服务端下发的 `data` 是不是带 `prepayid` / `partnerid` 的 JSON 对象。
     *
     * - 返回 `true`：服务端给了完整的微信下单信息 → 视为可用；
     * - 返回 `false`：接口失败、字段缺失或形态不对 → 视为不可用。
     *
     * 探测只发 GET、不改变服务端状态（未支付订单由服务端超时回收），失败按不可用处理。
     */
    suspend fun probeWeChatPay(
        token: String,
        orderId: String,
    ): Boolean {
        if (orderId.isEmpty()) return false
        val data =
            try {
                prepay(token, orderId, PayChannelType.WECHAT_APP)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emitApiError("微信支付探测失败：${e.message}")
                null
            } ?: return false
        return looksLikeWeChatPayInfo(data)
    }

    /** 微信下单信息是 JSON 对象且带 `prepayid` / `partnerid`；字符串等其他形态一律视为不可用。 */
    internal fun looksLikeWeChatPayInfo(data: JsonElement): Boolean {
        val obj = data as? JsonObject ?: return false

        fun field(key: String): String = runCatching { obj[key]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        return field("prepayid").isNotEmpty() && field("partnerid").isNotEmpty()
    }

    // 钱包退款：POST /acc/wallet/refund，type=23（支付宝小程序，与官方 RefundActivity 一致）
    suspend fun refundWallet(
        token: String,
        eid: String,
    ): DevResult {
        if (eid.isEmpty()) return DevResult(false, -1, "钱包信息缺失")
        val response =
            client.apiPost("acc/wallet/refund") {
                contentType(ContentType.Application.Json)
                setBody(requestJson.encodeToString(RefundWalletRequest(eid)))
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        return parseDevResult(response.bodyAsText(), token = token)
    }

    // 积分兑换：将积分兑换为指定钱包余额，成功返回账单号用于核验结果
    suspend fun exchangeScore(
        token: String,
        endpointId: String,
        score: Int,
    ): String? {
        if (endpointId.isEmpty() || score <= 0) return null
        val response =
            client.apiPost("acc/score/score-use") {
                contentType(ContentType.Application.Json)
                setBody(requestJson.encodeToString(ExchangeScoreRequest(EndpointRef(endpointId), score)))
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        checkSessionExpired(body, token)
        if (body.intOrNull("code") != 0) {
            emitApiError(body.strOrNull("msg"))
            return null
        }
        return body.obj("data")?.strOrNull("sn")?.takeIf { it.isNotEmpty() && it != "null" }
    }

    // 核验兑换账单是否已完成（status=3），复用账单详情解析
    suspend fun isExchangeBillCompleted(
        token: String,
        billId: String,
    ): Boolean {
        val bill = getBillDetail(token, billId) ?: return false
        return bill.id == billId && bill.status == 3
    }

    // 账单详情（bill/view-full：data.bill + data.cnt）
    suspend fun getBillDetail(
        token: String,
        billId: String,
    ): BillDetailInfo? {
        if (billId.isEmpty()) return null
        val response =
            client.apiGet("bill/view-full") {
                parameter("id", billId)
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = bodyJson(response, reportError = false)
        if (body.intOrNull("code") != 0) return null
        val data = body.obj("data") ?: return null
        val bill = data.obj("bill") ?: return null
        val ep = bill.obj("ep")
        val dev = bill.obj("dev")
        return BillDetailInfo(
            id = bill.str("id", billId),
            cata = bill.int("cata"),
            type = bill.int("type"),
            msg = bill.str("msg"),
            status = bill.int("status"),
            dir = bill.int("dir", 1),
            payment = bill.double("payment"),
            discount = bill.double("discount"),
            ctime = bill.long("ctime"),
            utime = bill.long("utime"),
            enterpriseName = ep?.str("name") ?: "",
            deviceId = dev?.str("id") ?: "",
            deviceName = dev?.str("name") ?: "",
            deviceDtype = dev?.obj("bm")?.int("dtype") ?: 0,
            couponCount = data.strOrNull("cnt")?.toIntOrNull() ?: 0,
            promoName = bill.obj("promo")?.str("name") ?: "",
        )
    }

    private fun readScoreInt(
        obj: JsonObject,
        nested: JsonObject?,
    ): Int {
        val keys = listOf("score", "spend", "changeScore", "change_score", "amount", "value", "num", "points")
        for (key in keys) {
            obj.intOrNull(key)?.let { return it }
            nested?.intOrNull(key)?.let { return it }
        }
        return 0
    }

    // 云卡列表（ic/lst-owner：query size）
    suspend fun getCardList(
        token: String,
        size: Int = 20,
    ): List<CloudCard> {
        val response =
            client.apiGet("ic/lst-owner") {
                parameter("size", size.toString())
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = bodyJson(response)
        if (body.intOrNull("code") != 0) return emptyList()
        // data 为数组；部分接口会包一层 lst
        val arr = body.arr("data") ?: body.obj("data")?.arr("lst") ?: return emptyList()
        return arr.filterIsInstance<JsonObject>().map { parseCloudCard(it) }
    }

    // 单张云卡详情（ic/owner-view：query id）
    suspend fun getCardDetail(
        token: String,
        cardId: String,
    ): CloudCard? {
        if (cardId.isEmpty()) return null
        val response =
            client.apiGet("ic/owner-view") {
                parameter("id", cardId)
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        val body = bodyJson(response)
        if (body.intOrNull("code") != 0) return null
        val data = body.obj("data") ?: return null
        return parseCloudCard(data)
    }

    // 绑定云卡（ic/bind：body CardBindParm）。
    // 返回服务端 code：0 = 成功，-15 = 该卡需要「绑卡密钥」。
    // 官方 ModifyCardActivity 即按两段式流程：首提被 -15 驳回后显示密钥输入框，用户补填再提一次。
    suspend fun bindCard(
        token: String,
        cardId: String,
        cardToken: String,
        name: String,
    ): Int {
        val response =
            client.apiPost("ic/bind") {
                contentType(ContentType.Application.Json)
                setBody(
                    requestJson.encodeToString(
                        CardBindRequest(id = cardId, name = name, token = cardToken),
                    ),
                )
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        return bodyJson(response).intOrNull("code") ?: CODE_UNKNOWN
    }

    // 修改云卡（ic/upt：body CardBindParm）
    suspend fun updateCard(
        token: String,
        cardId: String,
        cardToken: String,
        name: String,
        limit: Double,
        otherEp: Int,
    ): Boolean {
        val response =
            client.apiPost("ic/upt") {
                contentType(ContentType.Application.Json)
                setBody(
                    requestJson.encodeToString(
                        CardBindRequest(
                            id = cardId,
                            name = name,
                            token = cardToken,
                            limit = limit,
                            otherEp = otherEp,
                        ),
                    ),
                )
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        return bodyJson(response).intOrNull("code") == 0
    }

    // 解绑/挂失云卡（ic/unbind：body {"id": 卡ID}）
    suspend fun unbindCard(
        token: String,
        cardId: String,
    ): Boolean {
        if (cardId.isEmpty()) return false
        val response =
            client.apiPost("ic/unbind") {
                contentType(ContentType.Application.Json)
                setBody(requestJson.encodeToString(CardRefRequest(id = cardId)))
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        return bodyJson(response).intOrNull("code") == 0
    }

    // 激活云卡（ic/valid：body {"id": 卡ID}），云卡需每 12 小时激活一次
    suspend fun validCard(
        token: String,
        cardId: String,
    ): Boolean {
        if (cardId.isEmpty()) return false
        val response =
            client.apiPost("ic/valid") {
                contentType(ContentType.Application.Json)
                setBody(requestJson.encodeToString(CardRefRequest(id = cardId)))
                apiHeaders(token, APP_TYPE_DEVICE)
            }
        return bodyJson(response).intOrNull("code") == 0
    }

    private fun parseCloudCard(obj: JsonObject): CloudCard {
        val holder = obj.obj("holder")
        val ep = obj.obj("ep")
        return CloudCard(
            id = obj.str("id"),
            name = obj.str("name"),
            holderName = holder?.str("name") ?: "",
            enterpriseName = ep?.str("name") ?: "",
            enterpriseAbbr = ep?.str("abbr") ?: "",
            limit = obj.double("limit"),
            // atime/validTime 参与「是否需再激活」的时间比较，必须统一到毫秒，
            // 否则秒级时间戳会被当成 1970 年的毫秒值与当前时间比较，导致恒判定为过期。
            activatedAt = timeToMillis(obj.long("atime")),
            validTime = timeToMillis(obj.long("validTime")),
            otherEp = obj.int("otherEp"),
            token = obj.str("token"),
        )
    }
}
