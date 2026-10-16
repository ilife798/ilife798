package com.github.ilife798.data.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.ilife798.AppLifecycle
import com.github.ilife798.AppStorage
import com.github.ilife798.StorageKeys
import com.github.ilife798.data.api.DevResult
import com.github.ilife798.data.api.IlifeApi
import com.github.ilife798.data.api.ScoreDto
import com.github.ilife798.data.api.TokenPlatform
import com.github.ilife798.data.api.TokenProbe
import com.github.ilife798.data.api.int
import com.github.ilife798.data.api.str
import com.github.ilife798.data.model.Account
import com.github.ilife798.data.model.AccountEntry
import com.github.ilife798.data.model.AccountInfo
import com.github.ilife798.data.model.AppState
import com.github.ilife798.data.model.BackupDocument
import com.github.ilife798.data.model.BackupGroup
import com.github.ilife798.data.model.BackupOption
import com.github.ilife798.data.model.BillDetailInfo
import com.github.ilife798.data.model.DEFAULT_SEED_COLOR
import com.github.ilife798.data.model.DeviceAliases
import com.github.ilife798.data.model.DeviceDetailInfo
import com.github.ilife798.data.model.DeviceNotes
import com.github.ilife798.data.model.HomeDeviceType
import com.github.ilife798.data.model.ImportApplyOutcome
import com.github.ilife798.data.model.ImportMergeResult
import com.github.ilife798.data.model.ImportPreview
import com.github.ilife798.data.model.MissionInfo
import com.github.ilife798.data.model.PaletteStyle
import com.github.ilife798.data.model.PointsInfo
import com.github.ilife798.data.model.RechargeProduct
import com.github.ilife798.data.model.RefundProgress
import com.github.ilife798.data.model.ScoreFilter
import com.github.ilife798.data.model.ScoreRecord
import com.github.ilife798.data.model.SpendingStats
import com.github.ilife798.data.model.ThemeMode
import com.github.ilife798.data.model.accountForImportKey
import com.github.ilife798.data.model.buildImportPreview
import com.github.ilife798.data.model.decodeBackup
import com.github.ilife798.data.model.displayName
import com.github.ilife798.data.model.displaySummary
import com.github.ilife798.data.model.toBackupAccount
import com.github.ilife798.dismissToast
import com.github.ilife798.isIOS
import com.github.ilife798.logDebug
import com.github.ilife798.pay.AlipayPayResult
import com.github.ilife798.showToast
import com.github.ilife798.supportsDynamicColor
import com.github.ilife798.supportsPredictiveBack
import com.github.ilife798.toImageBitmap
import com.github.ilife798.update.UpdateController
import com.github.ilife798.update.UpdateDialogState
import com.github.ilife798.util.SPONSOR_URL
import com.github.ilife798.util.currentTimeMillis
import com.github.ilife798.util.openSponsorPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import kotlin.time.Duration.Companion.milliseconds

private const val SCORE_PAGE_SIZE = 200

// 账号列表持久化
private val accountListJson = Json { ignoreUnknownKeys = true }

// 同一份数据的重复拉取冷却时间（DeviceController 共用）
internal const val LOAD_COOLDOWN_MS = 5000L

// 赞助提示触发的启动次数：第 21/51/101 次启动时弹出，101 次后不再弹。
private val SPONSOR_PROMPT_AT = setOf(21, 51, 101)

// 达到该启动次数后不再计数与提示。
private const val MAX_LAUNCH_COUNT = 101

// 判断“确实跳到外部应用并返回”的最短离开时长。
private const val SPONSOR_RETURN_THRESHOLD_MS = 1500L

class AppViewModel : ViewModel() {
    // HttpClient 构建较重，延迟到首次网络调用时再创建，避免阻塞首帧渲染
    private val api by lazy {
        IlifeApi().apply {
            onSessionExpired = { token -> handleSessionExpired(token) }
            onApiError = { showToast(it) }
        }
    }

    private fun logError(
        scope: String,
        e: Exception,
    ) {
        logDebug("ILife798", "$scope: ${e.stackTraceToString()}")
    }

    private var lastScoreLoadTime = 0L
    private var lastAccountLoadTime = 0L

    var state by mutableStateOf(AppState())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    // 赞助提示：>0 表示弹窗展示中，值为展示用的已启动次数
    var sponsorPromptCount by mutableIntStateOf(0)
        private set

    private var sponsorPending = false
    private var sponsorClickedAt = 0L

    // 检查更新（状态与流程见 UpdateController）
    private val update =
        UpdateController(
            scope = viewModelScope,
            onToast = ::showToast,
            onDismissToast = ::dismissToast,
            onLogError = ::logError,
        )

    // 设备列表与启停（见 DeviceController）
    private val device =
        DeviceController(
            scope = viewModelScope,
            apiProvider = { api },
            getState = { state },
            setState = { transform -> state = transform(state) },
            onToast = ::showToast,
            onLogError = ::logError,
            onSessionExpired = { handleSessionExpired() },
        )

    // 钱包与账单（见 WalletBillController）
    private val wallet =
        WalletBillController(
            scope = viewModelScope,
            apiProvider = { api },
            getState = { state },
            setState = { transform -> state = transform(state) },
            onLogError = ::logError,
        )

    // 积分任务（见 TaskController）
    private val task =
        TaskController(
            scope = viewModelScope,
            apiProvider = { api },
            getState = { state },
            setState = { transform -> state = transform(state) },
            getLoading = { isLoading },
            onLoadingChange = { isLoading = it },
            onError = { errorMessage = it },
            onToast = ::showToast,
            onLogError = ::logError,
            onResetScoreCooldown = { lastScoreLoadTime = 0L },
            onReloadScores = { loadScoreInfo() },
        )

    init {
        loadSettings()
        device.loadHomeDeviceTypePref()
        device.loadDeviceNotesPref()
        device.loadDeviceAliasesPref()
        loadSavedAccount()
        update.loadSettings()
        registerLaunch()
        AppLifecycle.onResumed = {
            update.onAppResumed()
            handleSponsorReturn()
        }
        viewModelScope.launch {
            delay(1500.milliseconds)
            if (update.checkUpdateOnStart) update.checkForUpdate(silent = true)
        }
    }

    private fun loadSettings() {
        try {
            val storage = AppStorage.instance
            state =
                state.copy(
                    // 不支持的设置项隐藏并强制关闭，升级变支持后按存储/默认值恢复
                    dynamicColor = storage.getBoolean(StorageKeys.DYNAMIC_COLOR, true) && supportsDynamicColor,
                    customColor = storage.getBoolean(StorageKeys.CUSTOM_COLOR, true),
                    paletteStyle =
                        PaletteStyle.entries.firstOrNull { it.name == storage.getString(StorageKeys.PALETTE_STYLE) }
                            ?: PaletteStyle.TonalSpot,
                    seedColor = storage.getInt(StorageKeys.SEED_COLOR, DEFAULT_SEED_COLOR),
                    floatingNav = storage.getBoolean(StorageKeys.FLOATING_NAV, false),
                    appBlur = storage.getBoolean(StorageKeys.APP_BLUR, true) && !isIOS && isRuntimeShaderSupported(),
                    predictiveBackEnabled =
                        storage.getBoolean(StorageKeys.PREDICTIVE_BACK, true) && supportsPredictiveBack,
                    themeMode =
                        ThemeMode.entries.firstOrNull { it.name == storage.getString(StorageKeys.THEME_MODE) }
                            ?: ThemeMode.System,
                    disclaimerAcknowledged = storage.getBoolean(StorageKeys.DISCLAIMER_SHOWN, false),
                )
        } catch (e: Exception) {
            logError("loadSettings", e)
        }
    }

    private fun handleSessionExpired(expiredToken: String? = null) {
        val account = state.account
        if (account.token.isEmpty() && account.appToken.isEmpty()) return
        if (!expiredToken.isNullOrEmpty() && expiredToken != account.token && expiredToken != account.appToken) return
        viewModelScope.launch {
            if (state.account.token.isEmpty() && state.account.appToken.isEmpty()) return@launch
            showToast("登录状态失效，请重新登录")
            clearLoginState()
        }
    }

    private fun loadSavedAccount() {
        try {
            val storage = AppStorage.instance
            val listJson = storage.getString(StorageKeys.ACCOUNT_LIST)
            var list =
                try {
                    if (listJson.isNullOrBlank()) emptyList() else accountListJson.decodeFromString<List<AccountEntry>>(listJson)
                } catch (e: Exception) {
                    logError("loadSavedAccount.parse", e)
                    emptyList()
                }
            var index = storage.getInt(StorageKeys.CURRENT_ACCOUNT_INDEX, -1)
            if (list.isEmpty() && storage.getBoolean(StorageKeys.IS_LOGGED_IN)) {
                // 旧版单账号数据迁移为账号列表
                list =
                    listOf(
                        AccountEntry(
                            Account(
                                phone = storage.getString(StorageKeys.PHONE) ?: "",
                                isLoggedIn = true,
                                pointsLoginDone = storage.getBoolean(StorageKeys.POINTS_LOGIN_DONE),
                                uid = storage.getString(StorageKeys.UID) ?: "",
                                token = storage.getString(StorageKeys.TOKEN) ?: "",
                                appToken = storage.getString(StorageKeys.APP_TOKEN) ?: "",
                            ),
                        ),
                    )
                index = 0
            }
            if (index !in list.indices) index = -1
            if (index >= 0) {
                val entry = list[index]
                state =
                    state.copy(
                        account = entry.account,
                        accountInfo = entry.info,
                        accountList = list,
                        currentAccountIndex = index,
                    )
                checkLoginStatus()
            } else {
                state = state.copy(accountList = list, currentAccountIndex = -1)
            }
        } catch (e: Exception) {
            logError("loadSavedAccount", e)
        }
    }

    private fun checkLoginStatus() {
        val token = currentToken()
        if (token.isEmpty()) return
        viewModelScope.launch {
            try {
                // 首次网络调用在此构建 HttpClient，放到后台线程避免阻塞首帧
                val result = withContext(Dispatchers.Default) { api.getAccountInfo(token) }
                if (result != null) {
                    device.loadDeviceInfo()
                    loadAccountInfo()
                    loadScoreInfo()
                } else {
                    handleSessionExpired()
                }
            } catch (e: Exception) {
                logError("checkLoginStatus", e)
            }
        }
    }

    private fun saveAccount() {
        try {
            // 当前账号与资料同步回列表条目
            val list = state.accountList.toMutableList()
            if (state.currentAccountIndex in list.indices) {
                list[state.currentAccountIndex] = AccountEntry(state.account, state.accountInfo)
                state = state.copy(accountList = list)
            }
            val storage = AppStorage.instance
            val account = state.account
            storage.saveBoolean(StorageKeys.IS_LOGGED_IN, account.isLoggedIn)
            storage.saveString(StorageKeys.PHONE, account.phone)
            storage.saveBoolean(StorageKeys.POINTS_LOGIN_DONE, account.pointsLoginDone)
            storage.saveString(StorageKeys.UID, account.uid)
            storage.saveString(StorageKeys.TOKEN, account.token)
            storage.saveString(StorageKeys.APP_TOKEN, account.appToken)
            storage.saveString(StorageKeys.ACCOUNT_LIST, accountListJson.encodeToString(list))
            storage.saveInt(StorageKeys.CURRENT_ACCOUNT_INDEX, state.currentAccountIndex)
        } catch (e: Exception) {
            logError("saveAccount", e)
        }
    }

    // 写入/合并账号列表并设为当前账号
    private fun selectAccount(account: Account) {
        val list = state.accountList.toMutableList()
        var target = list.indexOfFirst { sameAccount(it.account, account) }
        if (target < 0 && state.currentAccountIndex in list.indices) {
            // 身份为空或与当前账号一致时视为当前账号更新，否则是新账号入列
            val incomingKeyed = account.uid.isNotEmpty() || account.phone.isNotEmpty()
            if (!incomingKeyed || sameAccount(account, state.account)) target = state.currentAccountIndex
        }
        val old = list.getOrNull(target)
        val merged =
            Account(
                phone = account.phone.ifEmpty { old?.account?.phone ?: "" },
                isLoggedIn = account.isLoggedIn || old?.account?.isLoggedIn == true,
                pointsLoginDone = account.pointsLoginDone || old?.account?.pointsLoginDone == true,
                uid = account.uid.ifEmpty { old?.account?.uid ?: "" },
                token = account.token.ifEmpty { old?.account?.token ?: "" },
                appToken = account.appToken.ifEmpty { old?.account?.appToken ?: "" },
            )
        val info =
            state.accountInfo
                .takeIf { it.name.isNotEmpty() || it.img.isNotEmpty() || it.pn.isNotEmpty() }
                ?: old?.info
                ?: AccountInfo()
        if (target < 0) {
            list.add(AccountEntry(merged, info))
            target = list.lastIndex
        } else {
            list[target] = AccountEntry(merged, info)
        }
        state = state.copy(account = merged, accountInfo = info, accountList = list, currentAccountIndex = target)
    }

    private fun sameAccount(
        a: Account,
        b: Account,
    ): Boolean = (a.uid.isNotEmpty() && a.uid == b.uid) || (a.phone.isNotEmpty() && a.phone == b.phone)

    // 账号切换/凭据变更后重载各业务数据
    private fun reloadAccountData() {
        device.loadDeviceInfo()
        loadAccountInfo()
        loadScoreInfo()
        wallet.loadSpendingStats()
        task.loadMissions()
    }

    // 账号管理页切换账号：立即生效并重载数据
    fun switchAccount(index: Int) {
        val list = state.accountList
        if (index !in list.indices || index == state.currentAccountIndex) return
        val entry = list[index]
        clearAccountData()
        state =
            state.copy(
                account = entry.account,
                accountInfo = entry.info,
                currentAccountIndex = index,
            )
        saveAccount()
        if (entry.account.hasAnyToken) reloadAccountData()
        showToast("已切换账号")
    }

    private var validatingAccounts = false

    // 进入账号管理页时校验其它账号有效性：仅服务端明确判定失效的移除，网络失败无法判定时一律保留
    fun validateAccounts() {
        if (validatingAccounts || state.accountList.isEmpty()) return
        validatingAccounts = true
        viewModelScope.launch {
            try {
                var removed = 0
                val current = state.currentAccountIndex
                // 快照遍历并跳过当前账号（其状态由正常请求流程负责判定）
                state.accountList.forEachIndexed { index, entry ->
                    if (index == current) return@forEachIndexed
                    val token = entry.account.preferredToken
                    if (token.isEmpty()) return@forEachIndexed
                    val expired =
                        try {
                            withContext(Dispatchers.Default) { api.isTokenExpired(token) }
                        } catch (e: Exception) {
                            logError("validateAccounts", e)
                            null
                        }
                    if (expired == true && removeAccountByToken(token)) removed++
                }
                if (removed > 0) showToast("已移除 $removed 个登录过期的账号")
            } finally {
                validatingAccounts = false
            }
        }
    }

    // 按凭据从列表移除条目，并维护当前索引指向
    private fun removeAccountByToken(token: String): Boolean {
        val list = state.accountList
        val index = list.indexOfFirst { it.account.preferredToken == token }
        if (index < 0) return false
        val newList = list.toMutableList().also { it.removeAt(index) }
        val current = state.currentAccountIndex
        if (index == current) {
            clearAccountData()
            state = state.copy(account = Account(), accountList = newList, currentAccountIndex = -1)
        } else if (index < current) {
            state = state.copy(accountList = newList, currentAccountIndex = current - 1)
        } else {
            state = state.copy(accountList = newList)
        }
        saveAccount()
        return true
    }

    // 账号管理页手动填写凭据
    fun updateAccountField(
        field: String,
        value: String,
    ) {
        val input = value.trim()
        if (input.isEmpty()) return
        viewModelScope.launch {
            val probe =
                try {
                    api.probeToken(input)
                } catch (e: Exception) {
                    logError("probeToken", e)
                    null
                }
            if (probe == null || (!probe.appValid && !probe.mainValid)) {
                showToast("登录信息无效，未保存")
                return@launch
            }
            val acc = state.account
            // 按探测出的渠道纠正槽位：仅渠道明确且填错槽时换槽，目标槽已有值不覆盖；渠道判不出不纠正
            var target = field
            var notice = "登录信息已保存"
            if (probe.platform == TokenPlatform.APP && field != "appToken") {
                if (acc.appToken.isNotEmpty()) {
                    showToast("检测为设备登录信息，但设备控制已有登录信息，未保存")
                    return@launch
                }
                target = "appToken"
                notice = "检测为设备登录信息，已填入设备控制"
            } else if (probe.platform == TokenPlatform.MAIN && field != "token") {
                if (acc.token.isNotEmpty()) {
                    showToast("检测为积分登录信息，但积分任务已有登录信息，未保存")
                    return@launch
                }
                target = "token"
                notice = "检测为积分登录信息，已填入积分任务"
            }
            // 同一账户校验：与另一槽已有凭据比对 uid；任一侧 uid 无法取得时无法确认归属，拒绝保存
            val otherToken = if (target == "appToken") acc.token else acc.appToken
            if (otherToken.isNotEmpty()) {
                val otherUid =
                    try {
                        api.getAccountInfo(otherToken, notifyExpiry = false)?.id ?: ""
                    } catch (e: Exception) {
                        logError("verifyOtherToken", e)
                        ""
                    }
                if (probe.uid.isEmpty() || otherUid.isEmpty()) {
                    showToast("无法确认两条登录信息属于同一账户，未保存")
                    return@launch
                }
                if (probe.uid != otherUid) {
                    showToast("两条登录信息不属于同一账户，未保存")
                    return@launch
                }
            }
            val updated =
                when (target) {
                    "appToken" -> acc.copy(appToken = input, isLoggedIn = true, uid = probe.uid.ifEmpty { acc.uid })
                    "token" -> acc.copy(token = input, isLoggedIn = true, pointsLoginDone = true, uid = probe.uid.ifEmpty { acc.uid })
                    else -> return@launch
                }
            // 凭据变更等于切换身份，先清掉上一个身份残留的数据与冷却计时，再加载
            clearAccountData()
            selectAccount(updated)
            saveAccount()
            reloadAccountData()
            showToast(notice)
        }
    }

    // 账户凭证登录：按槽位直接填写两条登录信息，设备控制必填、积分任务可选
    // 与验证码登录的区别是跳过短信流程，改由客户端校验凭据有效性后直接入列
    fun loginWithCredentials(
        appToken: String,
        mainToken: String,
    ) {
        val deviceToken = appToken.trim()
        val pointsToken = mainToken.trim()
        if (deviceToken.isEmpty()) {
            showToast("请填写设备控制登录信息")
            return
        }
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                // 设备控制槽位：必须有效
                val deviceProbe = probeTokenOrNull(deviceToken)
                if (deviceProbe == null) {
                    showToast("设备控制登录信息无效，未保存")
                    return@launch
                }
                // 积分任务槽位：填了才校验
                val pointsProbe = if (pointsToken.isEmpty()) null else probeTokenOrNull(pointsToken)
                if (pointsToken.isNotEmpty() && pointsProbe == null) {
                    showToast("积分任务登录信息无效，未保存")
                    return@launch
                }
                // 两条凭据必须属于同一账户，否则会写出一个不存在的混合身份
                if (deviceProbe != null && pointsProbe != null) {
                    if (deviceProbe.uid.isEmpty() || pointsProbe.uid.isEmpty()) {
                        showToast("无法确认两条登录信息属于同一账户，未保存")
                        return@launch
                    }
                    if (deviceProbe.uid != pointsProbe.uid) {
                        showToast("两条登录信息不属于同一账户，未保存")
                        return@launch
                    }
                }
                // 填了积分凭据却没拿到账号信息时，uid 只能取设备侧探测结果
                val uid = deviceProbe.uid.ifEmpty { pointsProbe?.uid ?: "" }
                clearAccountData()
                selectAccount(
                    Account(
                        isLoggedIn = true,
                        pointsLoginDone = pointsProbe != null,
                        uid = uid,
                        appToken = deviceToken,
                        token = pointsToken,
                    ),
                )
                saveAccount()
                reloadAccountData()
                showToast(if (pointsProbe != null) "登录成功" else "设备控制登录成功，积分任务未填写")
            } catch (e: Exception) {
                val msg = "登录失败: ${e.message}"
                errorMessage = msg
                showToast(msg)
            } finally {
                isLoading = false
            }
        }
    }

    // 探测登录信息，失败或无效统一返回 null，避免调用方重复判空
    private suspend fun probeTokenOrNull(token: String): TokenProbe? =
        try {
            api.probeToken(token).takeIf { it.appValid || it.mainValid }
        } catch (e: Exception) {
            logError("probeToken", e)
            null
        }

    // 验证码
    var captchaKey by mutableStateOf("")
        private set

    var captchaImage by mutableStateOf<ImageBitmap?>(null)
        private set

    fun loadCaptcha(clearError: Boolean = true) {
        viewModelScope.launch {
            isLoading = true
            if (clearError) errorMessage = null
            smsSent = false
            try {
                val key = api.newCaptchaKey()
                captchaKey = key
                val bytes = api.getCaptcha(key)
                captchaImage = bytes.toImageBitmap()
            } catch (e: Exception) {
                logError("loadCaptcha", e)
                errorMessage = "获取图形验证码失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // 登录
    var smsSent by mutableStateOf(false)
        private set

    // 发送验证码成功次数：驱动界面仅在成功后进入倒计时
    var smsSendSuccessTick by mutableIntStateOf(0)
        private set

    fun sendSmsCode(
        phone: String,
        graphCode: String,
    ) {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val response = api.sendSmsCode(phone, graphCode, captchaKey)
                val json = Json { ignoreUnknownKeys = true }
                val obj = json.parseToJsonElement(response).jsonObject
                val code = obj.int("code", -1)
                if (code == 0) {
                    smsSent = true
                    smsSendSuccessTick++
                    showToast("验证码已发送")
                } else {
                    val msg = obj.str("msg", "发送验证码失败")
                    errorMessage = msg
                    showToast(msg)
                    loadCaptcha(clearError = false)
                }
            } catch (e: Exception) {
                val msg = "发送验证码失败: ${e.message}"
                errorMessage = msg
                showToast(msg)
                loadCaptcha(clearError = false)
            } finally {
                isLoading = false
            }
        }
    }

    fun resetCaptcha() {
        captchaKey = ""
        captchaImage = null
        smsSent = false
    }

    fun login(
        phone: String,
        smsCode: String,
        isAlipay: Boolean,
    ) {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val result = api.login(phone, smsCode, isAlipay)
                if (result.success) {
                    clearAccountData()
                    val prev = state.account
                    // 身份不同则不继承旧凭据，作为新账号入列
                    val sameIdentity =
                        (prev.uid.isNotEmpty() && prev.uid == result.uid) || (prev.phone.isNotEmpty() && prev.phone == phone)
                    val base = if (sameIdentity) prev else Account()
                    selectAccount(
                        base.copy(
                            phone = phone,
                            isLoggedIn = true,
                            pointsLoginDone = if (isAlipay) true else base.pointsLoginDone,
                            uid = result.uid.ifEmpty { base.uid },
                            token = if (isAlipay) result.token else base.token,
                            appToken = if (!isAlipay) result.token else base.appToken,
                        ),
                    )
                    device.loadDeviceInfo()
                    loadAccountInfo()
                    if (isAlipay) loadScoreInfo()
                    wallet.loadSpendingStats()
                    task.loadMissions()
                    resetCaptcha()
                    saveAccount()
                    showToast("登录成功")
                } else {
                    errorMessage = result.error
                    showToast(result.error.ifEmpty { "登录失败" })
                }
            } catch (e: Exception) {
                val msg = "登录失败: ${e.message}"
                errorMessage = msg
                showToast(msg)
            } finally {
                isLoading = false
            }
        }
    }

    private fun clearAccountData() {
        task.reset()
        device.reset()
        wallet.reset()
        scoreLists = emptyScoreLists()
        scoreFilter = ScoreFilter.All
        lastScoreLoadTime = 0L
        lastAccountLoadTime = 0L
        state =
            state.copy(
                devices = emptyList(),
                points = PointsInfo(),
                accountInfo = AccountInfo(),
                wallets = emptyList(),
                activeWalletId = "",
                billRecords = emptyList(),
                taskLogs = emptyList(),
                taskCompleted = false,
                weekMask = 0,
            )
    }

    fun logout() {
        clearLoginState()
        showToast("已退出登录")
    }

    private fun clearLoginState() {
        clearAccountData()
        resetCaptcha()
        isLoading = false
        errorMessage = null
        // 退出登录：从列表移除当前账号并回到未登录，其余账号保留
        val list = state.accountList.toMutableList()
        val index = state.currentAccountIndex
        if (index in list.indices) list.removeAt(index)
        state = state.copy(account = Account(), accountList = list, currentAccountIndex = -1)
        saveAccount()
    }

    val homeDeviceType: HomeDeviceType get() = device.homeDeviceType
    val scannedDeviceId: String? get() = device.scannedDeviceId
    val deviceDetail: DeviceDetailInfo? get() = device.deviceDetail
    val pollingDeviceId: String? get() = device.pollingDeviceId
    val startingDeviceId: String? get() = device.startingDeviceId
    val autoDeduct: Boolean get() = device.autoDeduct

    fun selectHomeDeviceType(type: HomeDeviceType) = device.selectHomeDeviceType(type)

    val deviceNotes: DeviceNotes get() = device.deviceNotes

    fun deviceNote(deviceId: String): String = device.deviceNote(deviceId)

    fun updateDeviceNote(
        deviceId: String,
        text: String,
    ): Boolean = device.updateDeviceNote(deviceId, text)

    fun dropDeviceNotes(deviceIds: Collection<String>) = device.dropDeviceNotes(deviceIds)

    val deviceAliases: DeviceAliases get() = device.deviceAliases

    fun deviceAlias(deviceId: String): String = device.deviceAlias(deviceId)

    fun updateDeviceAlias(
        deviceId: String,
        text: String,
    ): Boolean = device.updateDeviceAlias(deviceId, text)

    fun clearAllDeviceNotes(): Int = device.clearAllDeviceNotes()

    fun clearAllDeviceAliases(): Int = device.clearAllDeviceAliases()

    fun clearDeviceCustomInfo(
        noteDeviceIds: Collection<String>,
        aliasDeviceIds: Collection<String>,
    ): Int = device.clearDeviceCustomInfo(noteDeviceIds, aliasDeviceIds)

    /**
     * 备份页的分组数据：账号组与设备自定义信息组。
     *
     * 账号条目 key 用 `account:<下标>`，因为手机号可能重复或为空；设备条目 key 用
     * `device:<设备 ID>`，与存储键一致，导出时可直接回查原始值。
     */
    fun backupGroups(): List<BackupGroup> {
        val accounts =
            state.accountList.mapIndexed { index, entry ->
                val backup = entry.toBackupAccount()
                BackupOption(
                    key = "account:$index",
                    title = backup.displayName(),
                    summary = backup.displaySummary(),
                )
            }
        val devices =
            device.deviceNotes
                .entries()
                .map { note ->
                    val alias = device.deviceAlias(note.deviceId)
                    BackupOption(
                        key = "device:${note.deviceId}",
                        title = alias.ifEmpty { note.deviceId },
                        summary = "备注：${note.text}",
                    )
                } +
                device.deviceAliases
                    .entries()
                    .filter { device.deviceNote(it.deviceId).isEmpty() }
                    .map { alias ->
                        BackupOption(
                            key = "device:${alias.deviceId}",
                            title = alias.text,
                            summary = "别名（无备注）",
                        )
                    }

        return listOf(
            BackupGroup(
                id = "group:accounts",
                title = "账号",
                emptyHint = "本机没有已保存的账号",
                options = accounts,
            ),
            BackupGroup(
                id = "group:devices",
                title = "设备",
                emptyHint = "本机没有设备别名或备注",
                options = devices.sortedBy { it.key },
            ),
        )
    }

    /** 按勾选结果生成备份文档。未勾选的条目不会出现在结果里。 */
    fun buildBackup(
        groups: List<BackupGroup>,
        selectedLeafKeys: Set<String>,
    ): BackupDocument {
        val accounts =
            state.accountList.mapIndexedNotNull { index, entry ->
                if ("account:$index" in selectedLeafKeys) entry.toBackupAccount() else null
            }
        // 别名与备注是同一设备上的两项独立数据，key 相同，故分两轮过滤
        val notes =
            device.deviceNotes
                .entries()
                .filter { "device:${it.deviceId}" in selectedLeafKeys }
                .associate { it.deviceId to it.text }
        val aliases =
            device.deviceAliases
                .entries()
                .filter { "device:${it.deviceId}" in selectedLeafKeys }
                .associate { it.deviceId to it.text }
        return BackupDocument(
            createdAt = currentTimeMillis(),
            accounts = accounts,
            deviceNotes = notes,
            deviceAliases = aliases,
        )
    }

    /**
     * 解析导入的备份文本并生成本机对照预览。
     *
     * 返回 null 表示文本不是合法备份（解析失败或空文件），由调用方提示用户；这与「解析
     * 成功但没有任何条目」不同，后者会返回一个 [ImportPreview.isEmpty] 为 true 的对象。
     */
    fun buildImportPreview(raw: String?): ImportPreview? {
        val doc = decodeBackup(raw) ?: return null
        importDocument = doc
        return buildImportPreview(
            doc = doc,
            currentAccounts = state.accountList.map { it.toBackupAccount() },
            currentNotes = device.deviceNotes.asMap(),
            currentAliases = device.deviceAliases.asMap(),
        )
    }

    /** 最近一次解析成功的备份文档，导入确认时据此还原账号凭据。 */
    private var importDocument: BackupDocument? = null

    /** 丢弃上次解析的备份，离开导入页时调用，避免长期持有用户的凭据文本。 */
    fun clearImportDocument() {
        importDocument = null
    }

    /**
     * 应用导入结果。
     *
     * 账号按 uid/phone 去重后并入账号列表，已存在的账号保留本机凭据——旧备份覆盖会把用户
     * 登出，因此账号不做冲突覆盖。设备别名与备注直接写入。返回各类实际写入数量。
     */
    fun applyImport(result: ImportMergeResult): ImportApplyOutcome {
        var accountsAdded = 0
        if (result.accounts.isNotEmpty()) {
            val doc = importDocument
            val list = state.accountList.toMutableList()
            result.accounts.forEach { item ->
                val incoming = doc?.accountForImportKey(item.key) ?: return@forEach
                val exists = list.any { sameAccount(it.account, incoming) }
                if (!exists && (incoming.uid.isNotEmpty() || incoming.phone.isNotEmpty())) {
                    list.add(AccountEntry(incoming, AccountInfo()))
                    accountsAdded++
                }
            }
            if (accountsAdded > 0) {
                state = state.copy(accountList = list)
                saveAccount()
            }
        }
        val deviceChanged = device.applyImportedDeviceInfo(result.deviceNotes, result.deviceAliases)
        return ImportApplyOutcome(accountsAdded = accountsAdded, deviceChanged = deviceChanged)
    }

    fun updateAutoDeduct(enabled: Boolean) = device.updateAutoDeduct(enabled)

    fun loadDeviceInfo(force: Boolean = false): Job? = device.loadDeviceInfo(force)

    fun addDevice(id: String) = device.addDevice(id)

    fun onDeviceDetailClosed(deviceId: String) = device.onDeviceDetailClosed(deviceId)

    fun submitScannedRaw(raw: String) = device.submitScannedRaw(raw)

    fun consumeScannedDeviceId() = device.consumeScannedDeviceId()

    fun toggleDeviceFavorite(
        deviceId: String,
        currentlyFollowed: Boolean,
    ) = device.toggleDeviceFavorite(deviceId, currentlyFollowed)

    fun loadDeviceDetail(deviceId: String): Job? = device.loadDeviceDetail(deviceId)

    fun toggleDeviceRunning(
        deviceId: String,
        args: String = "",
        forceStop: Boolean = false,
    ) = device.toggleDeviceRunning(deviceId, args, forceStop)

    fun stopDevice(deviceId: String) = device.stopDevice(deviceId)

    fun loadAccountInfo(): Job? {
        val token = currentToken()
        if (token.isEmpty()) return null
        val now = currentTimeMillis()
        if (now - lastAccountLoadTime < LOAD_COOLDOWN_MS) return null
        lastAccountLoadTime = now
        return viewModelScope.launch {
            try {
                val info = api.getAccountInfo(token)
                if (state.account.appToken != token && state.account.token != token) return@launch
                if (info == null) {
                    handleSessionExpired()
                    return@launch
                }
                // 与已有资料逐字段合并
                val newInfo =
                    AccountInfo(
                        img = info.img.ifEmpty { state.accountInfo.img },
                        name = info.name.ifEmpty { state.accountInfo.name },
                        pn = info.pn.ifEmpty { state.accountInfo.pn },
                    )
                if (state.accountInfo != newInfo) {
                    state = state.copy(accountInfo = newInfo)
                    saveAccount()
                }
            } catch (e: Exception) {
                logError("loadAccountInfo", e)
            }
        }
    }

    // 积分/任务
    private data class ScoreListState(
        val records: List<ScoreRecord> = emptyList(),
        val page: Int = 0,
        val hasMore: Boolean = false,
        val loadingMore: Boolean = false,
        val loaded: Boolean = false,
    )

    private var scoreLists by mutableStateOf(emptyScoreLists())

    var scoreFilter by mutableStateOf(ScoreFilter.All)
        private set

    val scoreRecords: List<ScoreRecord>
        get() = scoreLists[scoreFilter]?.records ?: emptyList()
    val scoreHasMore: Boolean
        get() = scoreLists[scoreFilter]?.hasMore ?: false
    val scoreLoadingMore: Boolean
        get() = scoreLists[scoreFilter]?.loadingMore ?: false

    private fun toScoreRecord(dto: ScoreDto) = ScoreRecord(score = dto.score, name = dto.name, time = dto.time)

    private fun currentToken(): String = state.account.preferredToken

    private fun emptyScoreLists(): Map<ScoreFilter, ScoreListState> = ScoreFilter.entries.associateWith { ScoreListState() }

    private fun loadScoreFirstPage(filter: ScoreFilter): Job? {
        val token = currentToken()
        if (token.isEmpty()) return null
        val src = filter.src
        return viewModelScope.launch {
            try {
                val result = api.getScoreList(token, size = SCORE_PAGE_SIZE, src = src)
                if (currentToken() != token) return@launch
                scoreLists = scoreLists + (
                    filter to
                        ScoreListState(
                            records = result.records.map(::toScoreRecord),
                            page = 0,
                            hasMore = result.total > result.records.size,
                            loaded = true,
                        )
                )
            } catch (e: Exception) {
                logError("loadScoreFirstPage", e)
            }
        }
    }

    fun loadScoreInfo() {
        val token = currentToken()
        if (token.isEmpty()) return
        val now = currentTimeMillis()
        if (now - lastScoreLoadTime < LOAD_COOLDOWN_MS) return
        lastScoreLoadTime = now
        loadScoreFirstPage(scoreFilter)
        loadScoreSummary()
    }

    fun loadScoreInfoNoCooldown(): Job? = loadScoreFirstPage(scoreFilter)

    fun selectScoreFilter(filter: ScoreFilter) {
        if (scoreFilter == filter) return
        scoreFilter = filter
        // 分类已缓存则直接展示；未缓存（如首次加载失败）时补一次请求
        if (scoreLists[filter]?.loaded != true) loadScoreFirstPage(filter)
    }

    fun loadMoreScores() {
        val filter = scoreFilter
        val current = scoreLists[filter] ?: return
        val token = currentToken()
        if (token.isEmpty() || current.loadingMore || !current.hasMore || !current.loaded) return
        scoreLists = scoreLists + (filter to current.copy(loadingMore = true))
        val src = filter.src
        viewModelScope.launch {
            try {
                val nextPage = current.page + 1
                val result = api.getScoreList(token, page = nextPage, size = SCORE_PAGE_SIZE, src = src)
                if (currentToken() != token) return@launch
                val latest = scoreLists[filter] ?: return@launch
                val seen = latest.records.map { "${it.time}|${it.score}|${it.name}" }.toMutableSet()
                val appended =
                    result.records
                        .map(::toScoreRecord)
                        .filter { seen.add("${it.time}|${it.score}|${it.name}") }
                scoreLists = scoreLists + (
                    filter to
                        latest.copy(
                            records = latest.records + appended,
                            page = nextPage,
                            hasMore = (nextPage + 1) * SCORE_PAGE_SIZE < result.total,
                            loadingMore = false,
                        )
                )
            } catch (e: Exception) {
                logError("loadMoreScores", e)
                val latest = scoreLists[filter]
                if (latest != null) {
                    scoreLists = scoreLists + (filter to latest.copy(loadingMore = false))
                }
            }
        }
    }

    // 积分概览
    fun loadScoreSummary(): Job? {
        val token = currentToken()
        if (token.isEmpty()) return null
        return viewModelScope.launch {
            try {
                val result = api.getMissionList(token)
                if (currentToken() != token) return@launch
                state =
                    state.copy(
                        points =
                            state.points.copy(
                                available = result.validScore ?: state.points.available,
                                total = result.totalScore ?: state.points.total,
                            ),
                    )
            } catch (e: Exception) {
                logError("loadScoreSummary", e)
            }
        }
    }

    // 进入积分明细页：重置为全部分类，一次性拉取全部分类并缓存，之后切换分类直接读缓存
    fun refreshScorePage() {
        scoreFilter = ScoreFilter.All
        lastScoreLoadTime = currentTimeMillis()
        ScoreFilter.entries.forEach { loadScoreFirstPage(it) }
        loadScoreSummary()
    }

    var scoreRefreshing by mutableStateOf(false)
        private set

    fun refreshScores() {
        if (scoreRefreshing) return
        scoreRefreshing = true
        viewModelScope.launch {
            try {
                lastScoreLoadTime = currentTimeMillis()
                val jobs =
                    ScoreFilter.entries.mapNotNull { loadScoreFirstPage(it) } +
                        listOfNotNull(loadScoreSummary())
                jobs.joinAll()
            } finally {
                scoreRefreshing = false
            }
        }
    }

    // 积分兑换
    var exchangeSubmitting by mutableStateOf(false)
        private set

    suspend fun submitScoreExchange(score: Int): DevResult {
        val token = currentToken()
        val activeWallet = state.wallets.firstOrNull { it.id == state.activeWalletId }
        if (token.isEmpty() || activeWallet == null || activeWallet.eid.isEmpty()) {
            return DevResult(false, -1, "钱包信息缺失，请重新进入")
        }
        exchangeSubmitting = true
        return try {
            val billId =
                api.exchangeScore(token, activeWallet.eid, score)
                    ?: return DevResult(false, -1, "")
            val completed = api.isExchangeBillCompleted(token, billId)
            wallet.loadWallet()
            refreshScorePage()
            if (completed) {
                DevResult(true, 0, "兑换已完成，已兑换至「${activeWallet.name}」")
            } else {
                DevResult(false, -1, "兑换结果待确认，请先核对官方记录，勿重复兑换")
            }
        } catch (e: Exception) {
            DevResult(false, -1, "兑换失败：${e.message}")
        } finally {
            exchangeSubmitting = false
        }
    }

    val billLoadingMore: Boolean get() = wallet.billLoadingMore
    val billHasMore: Boolean get() = wallet.billHasMore
    val billStatus: Int get() = wallet.billStatus
    val billRefreshing: Boolean get() = wallet.billRefreshing
    val refundProgress: RefundProgress? get() = wallet.refundProgress
    val spendingStats: SpendingStats get() = wallet.spendingStats
    val rechargeProducts: List<RechargeProduct> get() = wallet.rechargeProducts
    val rechargeLoading: Boolean get() = wallet.rechargeLoading
    val rechargePaying: Boolean get() = wallet.rechargePaying
    val refundSubmitting: Boolean get() = wallet.refundSubmitting

    fun loadWallet(): Job? = wallet.loadWallet()

    fun selectWallet(id: String) = wallet.selectWallet(id)

    fun refreshBillPage() = wallet.refreshBillPage()

    fun selectBillStatus(status: Int) = wallet.selectBillStatus(status)

    fun loadMoreBills() = wallet.loadMoreBills()

    fun refreshBills() = wallet.refreshBills()

    suspend fun getBillDetail(billId: String): BillDetailInfo? = wallet.getBillDetail(billId)

    fun loadSpendingStats(force: Boolean = false): Job? = wallet.loadSpendingStats(force)

    fun loadRechargeProducts() = wallet.loadRechargeProducts()

    suspend fun submitRecharge(product: RechargeProduct): AlipayPayResult = wallet.submitRecharge(product)

    suspend fun submitRefund(): DevResult = wallet.submitRefund()

    val missions: List<MissionInfo> get() = task.missions
    val tasksRefreshing: Boolean get() = task.tasksRefreshing
    val allTasksCompleted: Boolean get() = task.areAllTasksCompleted()

    fun loadMissions() = task.loadMissions()

    fun runTasksFromShortcut() = task.runFromShortcut()

    fun runAllTasks() = task.runAll()

    fun stopTasks() = task.stop()

    fun setTaskCompleted() = task.setCompleted()

    fun refreshTasks() = task.refresh()

    // 设置
    fun setDynamicColor(enabled: Boolean) {
        state = state.copy(dynamicColor = enabled)
        AppStorage.instance.saveBoolean(StorageKeys.DYNAMIC_COLOR, enabled)
    }

    fun setCustomColor(enabled: Boolean) {
        state = state.copy(customColor = enabled)
        AppStorage.instance.saveBoolean(StorageKeys.CUSTOM_COLOR, enabled)
    }

    fun setPaletteStyle(style: PaletteStyle) {
        state = state.copy(paletteStyle = style)
        AppStorage.instance.saveString(StorageKeys.PALETTE_STYLE, style.name)
    }

    fun setSeedColor(color: Int) {
        state = state.copy(seedColor = color)
        AppStorage.instance.saveInt(StorageKeys.SEED_COLOR, color)
    }

    fun setFloatingNav(enabled: Boolean) {
        state = state.copy(floatingNav = enabled)
        AppStorage.instance.saveBoolean(StorageKeys.FLOATING_NAV, enabled)
    }

    fun setAppBlur(enabled: Boolean) {
        state = state.copy(appBlur = enabled)
        AppStorage.instance.saveBoolean(StorageKeys.APP_BLUR, enabled)
    }

    fun setPredictiveBackEnabled(enabled: Boolean) {
        state = state.copy(predictiveBackEnabled = enabled)
        AppStorage.instance.saveBoolean(StorageKeys.PREDICTIVE_BACK, enabled)
    }

    fun setThemeMode(mode: ThemeMode) {
        state = state.copy(themeMode = mode)
        AppStorage.instance.saveString(StorageKeys.THEME_MODE, mode.name)
    }

    // 首次启动须确认免责声明
    fun acknowledgeDisclaimer() {
        if (state.disclaimerAcknowledged) return
        state = state.copy(disclaimerAcknowledged = true)
        AppStorage.instance.saveBoolean(StorageKeys.DISCLAIMER_SHOWN, true)
    }

    // 启动次数统计与赞助提示：第 21/51/101 次启动弹出，达上限后不再计数
    private fun registerLaunch() {
        val storage = AppStorage.instance
        if (storage.getBoolean(StorageKeys.SPONSOR_PROMPT_STOPPED, false)) return
        val count = storage.getInt(StorageKeys.LAUNCH_COUNT, 0)
        if (count >= MAX_LAUNCH_COUNT) return
        val next = count + 1
        storage.saveInt(StorageKeys.LAUNCH_COUNT, next)
        if (next in SPONSOR_PROMPT_AT) sponsorPromptCount = next - 1
    }

    // 关闭赞助提示但不作选择：继续计数
    fun dismissSponsor() {
        sponsorPromptCount = 0
    }

    // 不再提醒：停止计数
    fun neverRemindSponsor() {
        sponsorPromptCount = 0
        AppStorage.instance.saveBoolean(StorageKeys.SPONSOR_PROMPT_STOPPED, true)
    }

    // 立即赞助：打开链接并记录待确认状态
    fun openSponsor(url: String = SPONSOR_URL) {
        sponsorPromptCount = 0
        if (openSponsorPage(url)) {
            sponsorPending = true
            sponsorClickedAt = currentTimeMillis()
        }
    }

    // 从外部应用返回：确实跳出过则感谢并停止计数，仅弹出选择器未跳转则继续计数
    private fun handleSponsorReturn() {
        if (!sponsorPending) return
        sponsorPending = false
        if (AppLifecycle.backgroundedAt >= sponsorClickedAt &&
            currentTimeMillis() - AppLifecycle.backgroundedAt >= SPONSOR_RETURN_THRESHOLD_MS
        ) {
            AppStorage.instance.saveBoolean(StorageKeys.SPONSOR_PROMPT_STOPPED, true)
            showToast("感谢支持")
        }
    }

    fun clearError() {
        errorMessage = null
    }

    var homeRefreshing by mutableStateOf(false)
        private set

    fun refreshHome() {
        if (homeRefreshing) return
        homeRefreshing = true
        viewModelScope.launch {
            try {
                lastAccountLoadTime = 0L
                lastScoreLoadTime = 0L
                listOfNotNull(
                    device.loadDeviceInfo(force = true),
                    loadAccountInfo(),
                    loadScoreInfoNoCooldown(),
                    loadScoreSummary(),
                    wallet.loadSpendingStats(force = true),
                ).joinAll()
            } finally {
                homeRefreshing = false
            }
        }
    }

    override fun onCleared() {
        AppLifecycle.onResumed = null
    }

    val updateDialog: UpdateDialogState get() = update.dialog

    val githubProxyUrl: String get() = update.githubProxyUrl

    val checkUpdateOnStart: Boolean get() = update.checkUpdateOnStart

    val developerMode: Boolean get() = update.developerMode

    fun checkForUpdate(silent: Boolean = false) = update.checkForUpdate(silent)

    fun startUpdate() = update.startUpdate()

    fun hideUpdateProgressDialog() = update.hideUpdateProgressDialog()

    fun reopenUpdateProgressDialog() = update.reopenUpdateProgressDialog()

    fun stopUpdate() = update.stopUpdate()

    fun openInstallSettings() = update.openInstallSettings()

    fun dismissUpdateDialog() = update.dismissDialog()

    fun setGithubProxy(url: String) = update.setGithubProxy(url)

    fun setCheckUpdateOnStartEnabled(enabled: Boolean) = update.setCheckUpdateOnStartEnabled(enabled)

    fun enableDeveloperMode() = update.enableDeveloperMode()

    fun disableDeveloperMode() = update.disableDeveloperMode()
}
