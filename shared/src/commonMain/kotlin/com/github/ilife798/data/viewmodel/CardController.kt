package com.github.ilife798.data.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.ilife798.data.api.CODE_CARD_KEY_REQUIRED
import com.github.ilife798.data.api.CODE_OK
import com.github.ilife798.data.api.IlifeApi
import com.github.ilife798.data.model.AppState
import com.github.ilife798.data.model.CloudCard
import com.github.ilife798.data.model.needsActivation
import com.github.ilife798.util.CardQrParser
import com.github.ilife798.util.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// 云卡列表加载、绑定/解绑与周期激活。
// 云卡由实体卡扫码绑定，绑定后每 12 小时需激活一次（服务端要求）。
class CardController(
    private val scope: CoroutineScope,
    private val apiProvider: () -> IlifeApi,
    private val getState: () -> AppState,
    private val onToast: (String) -> Unit,
    private val onLogError: (String, Exception) -> Unit,
    private val onSessionExpired: () -> Unit,
) {
    private val api: IlifeApi get() = apiProvider()

    var cards by mutableStateOf<List<CloudCard>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    // 最近一次加载是否成功，用于区分「无卡」与「加载失败」两种空列表
    var loaded by mutableStateOf(false)
        private set

    private var lastLoadTime = 0L
    private var loadJob: Job? = null

    // 已成功激活的卡，避免每次进入页面重复请求。
    // 只记成功项：失败（网络异常或服务端拒绝）的卡必须可重试，
    // 否则本次会话内该卡永远无法激活——它仍处超时状态却再也不会被再尝试。
    private val activationSucceeded = mutableSetOf<String>()

    fun loadCards(force: Boolean = false): Job? {
        val token = currentToken()
        if (token.isEmpty()) {
            cards = emptyList()
            loaded = false
            return null
        }
        val now = currentTimeMillis()
        if (!force && loadJob?.isActive == true) return loadJob
        if (!force && now - lastLoadTime < LOAD_COOLDOWN_MS) return loadJob
        lastLoadTime = now
        loading = true
        loadJob =
            scope.launch {
                try {
                    val list = api.getCardList(token)
                    // 请求返回期间可能已切换账号
                    if (currentToken() != token) return@launch
                    cards = list
                    loaded = true
                    activateExpired(token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onLogError("加载卡包失败", e)
                    onToast("加载卡包失败")
                } finally {
                    loading = false
                }
            }
        return loadJob
    }

    // 超过激活时限的卡静默重新激活；失败不打扰用户，下次进入会再试
    private fun activateExpired(token: String) {
        val now = currentTimeMillis()
        val pending = cards.filter { it.needsActivation(now) && it.id !in activationSucceeded }
        if (pending.isEmpty()) return
        scope.launch {
            var changed = false
            for (card in pending) {
                val ok =
                    try {
                        api.validCard(token, card.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onLogError("云卡激活失败", e)
                        false
                    }
                // 仅成功才登记；失败留给下次进入页面时重试
                if (ok) {
                    activationSucceeded += card.id
                    changed = true
                }
            }
            if (changed && currentToken() == token) {
                try {
                    cards = api.getCardList(token).ifEmpty { cards }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onLogError("刷新卡包失败", e)
                }
            }
        }
    }

    // 待补填绑卡密钥的卡号；服务端要求密钥时置位，用户在绑定对话框补填后清空。
    // 对应官方 ModifyCardActivity 的两段式绑定：首提 ic/bind 被 -15 驳回 → 显示密钥框。
    var pendingCardKeyId by mutableStateOf("")
        private set

    // 用户放弃补填密钥时复位。若不复位，该状态会一直残留：
    // 下次为其它卡打开绑定对话框时会误显示密钥框并回填上一张卡的卡号。
    fun clearPendingCardKey() {
        pendingCardKeyId = ""
    }

    // 扫码得到的原始文本。扫码页与卡包页是两个路由，扫完即返回，故结果必须存在
    // ViewModel 里才能跨导航存活；卡包页消费后调用 clearScannedRaw() 复位。
    var scannedCardRaw by mutableStateOf("")
        private set

    fun clearScannedRaw() {
        scannedCardRaw = ""
    }

    // 绑定云卡。cardKey 为「绑卡密钥」（官方 CardBindParm.token），部分卡/商户需要；
    // 为空时先提交一次，若服务端回 -15 则进入待补填状态，由 UI 提示用户填写后再次调用。
    //
    // onSettled 在本次请求结束时必定回调（成功、需要密钥、失败、异常都算），
    // 供 UI 复位「提交中」标志；若只在成功时回调，-15 会使提交中标志永远不复位，
    // 绑定按钮被永久禁用，用户填了密钥也交不上去。
    fun bindCard(
        cardId: String,
        cardKey: String,
        name: String,
        onSuccess: () -> Unit = {},
        onSettled: () -> Unit = {},
    ) {
        val token = currentToken()
        if (token.isEmpty()) {
            onToast("请先登录")
            onSettled()
            return
        }
        if (cardId.isEmpty()) {
            onToast("请填写卡号")
            onSettled()
            return
        }
        scope.launch {
            try {
                when (api.bindCard(token, cardId, cardKey, name)) {
                    CODE_OK -> {
                        pendingCardKeyId = ""
                        onToast("绑定成功")
                        loadCards(force = true)
                        onSuccess()
                    }

                    CODE_CARD_KEY_REQUIRED -> {
                        pendingCardKeyId = cardId
                        onToast(if (cardKey.isEmpty()) "该卡需要绑卡密钥，请补填后重试" else "绑卡密钥不正确")
                    }

                    else -> {
                        onToast("绑定失败")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onLogError("绑定云卡失败", e)
                onToast("绑定失败")
            } finally {
                onSettled()
            }
        }
    }

    // 修改云卡设置（名称、单次额度、是否全国可用）
    fun updateCard(
        cardId: String,
        name: String,
        limit: Double,
        otherEp: Int,
    ) {
        val token = currentToken()
        if (token.isEmpty()) {
            onToast("请先登录")
            return
        }
        val card = cards.firstOrNull { it.id == cardId }
        if (card == null) {
            onToast("卡不存在")
            return
        }
        scope.launch {
            try {
                if (api.updateCard(token, cardId, card.token, name, limit, otherEp)) {
                    onToast("已保存")
                    loadCards(force = true)
                } else {
                    onToast("保存失败")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onLogError("修改云卡失败", e)
                onToast("保存失败")
            }
        }
    }

    // 解绑/挂失云卡
    fun unbindCard(cardId: String) {
        val token = currentToken()
        if (token.isEmpty()) {
            onToast("请先登录")
            return
        }
        scope.launch {
            try {
                if (api.unbindCard(token, cardId)) {
                    onToast("已解绑")
                    activationSucceeded -= cardId
                    loadCards(force = true)
                } else {
                    onToast("解绑失败")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onLogError("解绑云卡失败", e)
                onToast("解绑失败")
            }
        }
    }

    // 手动激活指定云卡。静默激活失败后用户可自行重试，无需退出页面或等下次加载。
    fun activateCard(cardId: String) {
        val token = currentToken()
        if (token.isEmpty()) {
            onToast("请先登录")
            return
        }
        if (cardId.isEmpty()) return
        scope.launch {
            val ok =
                try {
                    api.validCard(token, cardId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onLogError("云卡激活失败", e)
                    false
                }
            if (ok) {
                activationSucceeded += cardId
                onToast("激活成功")
                loadCards(force = true)
            } else {
                onToast("激活失败，请稍后重试")
            }
        }
    }

    // 扫码结果登记：只记录原文，不直接发起绑定。
    // 卡号解析后由绑定对话框统一提交，这样扫码与手输走完全相同的确认流程。
    fun submitScannedRaw(raw: String) {
        scannedCardRaw = raw.trim()
    }

    fun reset() {
        loadJob?.cancel()
        loadJob = null
        cards = emptyList()
        loaded = false
        loading = false
        lastLoadTime = 0L
        pendingCardKeyId = ""
        scannedCardRaw = ""
        activationSucceeded.clear()
    }

    private fun currentToken(): String {
        val account = getState().account
        return account.appToken.ifEmpty { account.token }
    }

    private companion object {
        const val LOAD_COOLDOWN_MS = 3000L
    }
}
