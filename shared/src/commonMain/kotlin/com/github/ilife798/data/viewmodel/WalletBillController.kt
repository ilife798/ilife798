package com.github.ilife798.data.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.ilife798.data.api.DevResult
import com.github.ilife798.data.api.IlifeApi
import com.github.ilife798.data.model.AppState
import com.github.ilife798.data.model.BillDetailInfo
import com.github.ilife798.data.model.BillRecord
import com.github.ilife798.data.model.PayChannel
import com.github.ilife798.data.model.PayChannelType
import com.github.ilife798.data.model.PendingRecharge
import com.github.ilife798.data.model.RechargeProduct
import com.github.ilife798.data.model.RefundProgress
import com.github.ilife798.data.model.SpendingStats
import com.github.ilife798.data.model.WalletAccount
import com.github.ilife798.pay.AlipayPayResult
import com.github.ilife798.pay.payWithAlipay
import com.github.ilife798.pay.payWithOneCard
import com.github.ilife798.pay.payWithWeChat
import com.github.ilife798.util.currentTimeFormatted
import com.github.ilife798.util.currentTimeMillis
import com.github.ilife798.util.formatTimestamp
import com.github.ilife798.util.getTodayStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

private const val BILL_PAGE_SIZE = 200
private const val BILL_STATUS = 3
private const val SPENDING_MAX_PAGES = 10

// 钱包、账单分页、消费统计、充值与退款。
class WalletBillController(
    private val scope: CoroutineScope,
    private val apiProvider: () -> IlifeApi,
    private val getState: () -> AppState,
    private val setState: ((AppState) -> AppState) -> Unit,
    private val onLogError: (String, Exception) -> Unit,
) {
    private val api: IlifeApi get() = apiProvider()

    var billLoadingMore by mutableStateOf(false)
        private set
    var billHasMore by mutableStateOf(false)
        private set

    // 账单类型（1 未付款 / 3 已付款 / 2 待确认 / 4 付款失败 / 9 已取消）
    var billStatus by mutableStateOf(BILL_STATUS)
        private set

    var billRefreshing by mutableStateOf(false)
        private set

    var refundProgress by mutableStateOf<RefundProgress?>(null)
        private set

    var spendingStats by mutableStateOf(SpendingStats())
        private set

    var rechargeProducts by mutableStateOf<List<RechargeProduct>>(emptyList())
        private set
    var rechargeLoading by mutableStateOf(false)
        private set
    var rechargePaying by mutableStateOf(false)
        private set

    /**
     * 待支付订单。
     *
     * 渠道查询必须带账单 ID，所以流程拆成两步：先下单拿到 [PendingRecharge]，
     * 把渠道列表展示给用户选择，选定后再用它去拉预支付串。
     */
    var pendingRecharge by mutableStateOf<PendingRecharge?>(null)
        private set

    var refundSubmitting by mutableStateOf(false)
        private set

    private data class BillCacheEntry(
        val records: List<BillRecord>,
        val page: Int,
        val hasMore: Boolean,
    )

    // 各账单类型的数据缓存，进入页面/刷新时清空，切换类型时命中缓存则不重复请求
    private var billCache = mutableMapOf<Int, BillCacheEntry>()
    private var billPage = 0
    private var billLoadToken = ""

    // 账单加载代次，刷新/切换类型后自增，避免旧分页请求回填过期数据
    private var billLoadGen = 0
    private var lastSpendingLoadTime = 0L

    private fun currentToken(): String = getState().account.preferredToken

    /**
     * 第一步：下单并查可用渠道。
     *
     * 成功则把订单与渠道放进 [pendingRecharge] 供界面弹出选择框；失败返回错误文案。
     */
    suspend fun startRecharge(product: RechargeProduct): String? {
        val token = currentToken()
        val wallet = getState().wallets.firstOrNull { it.id == getState().activeWalletId }
        if (token.isEmpty() || wallet == null || wallet.eid.isEmpty() || wallet.ownerId.isEmpty()) {
            return "钱包信息缺失，请重新进入"
        }
        if (rechargePaying) return "正在支付中，请稍候"
        rechargePaying = true
        return try {
            val orderId =
                api.createRechargeOrder(token, wallet.eid, wallet.ownerId, product.id)
                    ?: return ""
            // 渠道查询失败不阻断充值：回退到一直可用的支付宝
            val channels =
                api.getPayChannels(token, orderId)
                    ?: listOf(PayChannel(PayChannelType.ALIPAY_APP, "支付宝（APP）"))
            if (channels.none { !it.unavailable }) return "没有可用的支付方式"
            pendingRecharge =
                PendingRecharge(
                    orderId = orderId,
                    token = token,
                    amount = product.price,
                    channels = channels,
                )
            null
        } catch (e: Exception) {
            "支付失败：${e.message}"
        } finally {
            rechargePaying = false
        }
    }

    /**
     * 第二步：用户选定的渠道去拉预支付串并唤起对应收银台。
     *
     * 各渠道的预支付串形态不同，因此必须按 [payType] 分派：
     * - 21 支付宝：`data` 是 orderInfo，交给支付宝 SDK `PayTask.payV2`
     * - 93 一卡通：`data` 是 sign_params，交给支付宝 SDK `OpenAuthTask`
     * - 11 微信：`data` 是 `PayInfo` JSON 对象，交给微信 SDK `PayReq`
     * - 91 商家钱包：纯服务端扣款，预支付成功即完成，直接刷新余额与账单
     */
    suspend fun payRecharge(payType: Int): AlipayPayResult {
        val pending = pendingRecharge ?: return AlipayPayResult(false, "")
        if (payType !in PayChannelType.SUPPORTED) {
            return AlipayPayResult(false, "该支付方式暂不可用")
        }
        if (rechargePaying) return AlipayPayResult(false, "正在支付中，请稍候")
        rechargePaying = true
        return try {
            val data =
                api.prepay(pending.token, pending.orderId, payType)
                    ?: return AlipayPayResult(false, "获取预支付信息失败，请稍后重试")
            val result =
                when (payType) {
                    PayChannelType.WALLET -> {
                        AlipayPayResult(true, "支付成功")
                    }

                    PayChannelType.ALIPAY_APP -> {
                        val orderInfo = (data as? JsonPrimitive)?.content
                        if (orderInfo.isNullOrEmpty()) {
                            AlipayPayResult(false, "获取支付参数失败，请稍后重试")
                        } else {
                            payWithAlipay(orderInfo)
                        }
                    }

                    PayChannelType.THIRD -> {
                        val signParams = (data as? JsonPrimitive)?.content
                        if (signParams.isNullOrEmpty()) {
                            AlipayPayResult(false, "获取一卡通授权参数失败，请稍后重试")
                        } else {
                            payWithOneCard(signParams)
                        }
                    }

                    PayChannelType.WECHAT_APP -> {
                        payWithWeChat(data.toString())
                    }

                    else -> {
                        AlipayPayResult(false, "该支付方式暂不可用")
                    }
                }
            if (result.success) {
                loadWallet()
                loadBillsNoCooldown()
            }
            result
        } catch (e: Exception) {
            AlipayPayResult(false, "支付失败：${e.message}")
        } finally {
            rechargePaying = false
            pendingRecharge = null
        }
    }

    fun dismissRecharge() {
        if (!rechargePaying) pendingRecharge = null
    }

    // 账单详情（bill/view-full）；网络异常返回 null 由页面展示失败态
    suspend fun getBillDetail(billId: String): BillDetailInfo? {
        val token = currentToken()
        if (token.isEmpty()) return null
        return try {
            api.getBillDetail(token, billId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onLogError("getBillDetail", e)
            null
        }
    }

    fun reset() {
        clearBillPaging()
        rechargeProducts = emptyList()
        refundProgress = null
        spendingStats = SpendingStats()
        lastSpendingLoadTime = 0L
    }

    // 账单分页与缓存的公共重置
    private fun clearBillPaging() {
        billHasMore = false
        billLoadingMore = false
        billPage = 0
        billLoadToken = ""
        billStatus = BILL_STATUS
        billCache = mutableMapOf()
    }

    fun loadWallet(): Job? {
        val token = currentToken()
        if (token.isEmpty()) return null
        return scope.launch {
            try {
                val result = api.getWalletOwner(token)
                if (currentToken() != token) return@launch
                val active = result.active
                val merged =
                    if (active != null && result.wallets.none { it.id == active.id }) {
                        listOf(active) + result.wallets
                    } else {
                        result.wallets
                    }
                val wallets = merged.distinctBy { it.id.ifEmpty { it.eid } }
                val activeId =
                    active?.id?.takeIf { it.isNotEmpty() }
                        ?: wallets.firstOrNull()?.id ?: ""
                setState { it.copy(wallets = wallets, activeWalletId = activeId) }
                refundProgress = result.refundProgress
                wallets.firstOrNull { it.id == activeId }?.let { loadWalletDetail(it) }
            } catch (e: Exception) {
                onLogError("loadWallet", e)
            }
        }
    }

    private fun loadWalletDetail(wallet: WalletAccount) {
        val token = currentToken()
        if (token.isEmpty()) return
        scope.launch {
            try {
                val detail = api.getWalletDetail(token, wallet.id, wallet.eid) ?: return@launch
                if (currentToken() != token) return@launch
                setState { state ->
                    state.copy(
                        wallets =
                            state.wallets.map {
                                if (it.id == wallet.id) {
                                    it.copy(
                                        name = detail.name.ifEmpty { it.name },
                                        total = detail.total,
                                        olCash = detail.olCash,
                                        olGift = detail.olGift,
                                        ofCash = detail.ofCash,
                                        ofGift = detail.ofGift,
                                        auth = detail.auth,
                                    )
                                } else {
                                    it
                                }
                            },
                    )
                }
            } catch (e: Exception) {
                onLogError("loadWalletDetail", e)
            }
        }
    }

    fun selectWallet(id: String) {
        if (getState().activeWalletId == id) return
        rechargeProducts = emptyList()
        setState { it.copy(activeWalletId = id) }
        getState().wallets.firstOrNull { it.id == id }?.let { loadWalletDetail(it) }
    }

    private fun loadBillFirstPage(token: String): Job {
        val status = billStatus
        val gen = ++billLoadGen
        return scope.launch {
            try {
                val result = api.getBillList(token, size = BILL_PAGE_SIZE, status = status)
                if (gen != billLoadGen || currentToken() != token) return@launch
                val hasMore = result.total > result.records.size
                billCache[status] = BillCacheEntry(result.records, 0, hasMore)
                // 请求期间已切换到其它类型，仅写入缓存
                if (status != billStatus) return@launch
                billLoadToken = token
                billPage = 0
                billHasMore = hasMore
                setState { it.copy(billRecords = result.records) }
                // 首屏返回后继续拉取剩余页，保证最后一页（不足 20 条）也被取到
                if (hasMore) loadAllBills()
            } catch (e: Exception) {
                onLogError("loadBillFirstPage", e)
            }
        }
    }

    fun selectBillStatus(status: Int) {
        if (billStatus == status) return
        billStatus = status
        billLoadingMore = false
        val cached = billCache[status]
        if (cached != null) {
            billLoadToken = currentToken()
            billPage = cached.page
            billHasMore = cached.hasMore
            setState { it.copy(billRecords = cached.records) }
        } else {
            billLoadToken = ""
            billPage = 0
            billHasMore = false
            setState { it.copy(billRecords = emptyList()) }
            loadBillsNoCooldown()
        }
    }

    fun loadBillsNoCooldown(): Job? {
        val token = currentToken()
        if (token.isEmpty()) return null
        return loadBillFirstPage(token)
    }

    // 拉取下一页并追加；hasMore 为 false、账号/类型已切换或重新加载时返回 false
    private suspend fun loadNextBillPage(gen: Int): Boolean {
        val token = currentToken()
        val status = billStatus
        if (gen != billLoadGen || token.isEmpty() || !billHasMore || billLoadToken != token) return false
        val nextPage = billPage + 1
        val result = api.getBillList(token, page = nextPage, size = BILL_PAGE_SIZE, status = status)
        if (gen != billLoadGen || currentToken() != token || status != billStatus) return false
        billPage = nextPage
        val current = getState().billRecords
        val seen = current.map { it.id }.toMutableSet()
        val appended = result.records.filter { it.id.isEmpty() || seen.add(it.id) }
        val newRecords = current + appended
        billHasMore = result.records.size >= BILL_PAGE_SIZE
        billCache[status] = BillCacheEntry(newRecords, billPage, billHasMore)
        setState { it.copy(billRecords = newRecords) }
        return true
    }

    fun loadMoreBills() {
        val token = currentToken()
        if (token.isEmpty() || billLoadingMore || !billHasMore || billLoadToken != token) return
        val gen = billLoadGen
        billLoadingMore = true
        scope.launch {
            try {
                loadNextBillPage(gen)
            } catch (e: Exception) {
                onLogError("loadMoreBills", e)
            } finally {
                billLoadingMore = false
            }
        }
    }

    // 自动拉取剩余全部账单（按 20 条/页，直到最后一页不足 20 条）
    private fun loadAllBills() {
        val token = currentToken()
        if (token.isEmpty() || billLoadingMore || !billHasMore || billLoadToken != token) return
        val gen = billLoadGen
        billLoadingMore = true
        scope.launch {
            try {
                var guard = 0
                while (billHasMore && gen == billLoadGen && guard < 100) {
                    if (!loadNextBillPage(gen)) break
                    guard++
                }
            } catch (e: Exception) {
                onLogError("loadAllBills", e)
            } finally {
                billLoadingMore = false
            }
        }
    }

    // 进入我的账单页：重置为默认（已付款），清空缓存后重新请求
    fun refreshBillPage() {
        clearBillPaging()
        setState { it.copy(billRecords = emptyList()) }
        loadWallet()
        loadBillsNoCooldown()
    }

    fun refreshBills() {
        if (billRefreshing) return
        billRefreshing = true
        // 下拉刷新：清空缓存后重新请求当前类型
        billCache = mutableMapOf()
        scope.launch {
            try {
                listOfNotNull(loadWallet(), loadBillsNoCooldown()).joinAll()
            } finally {
                billRefreshing = false
            }
        }
    }

    fun loadSpendingStats(force: Boolean = false): Job? {
        val token = currentToken()
        if (token.isEmpty()) return null
        val now = currentTimeMillis()
        if (!force && now - lastSpendingLoadTime < LOAD_COOLDOWN_MS) return null
        lastSpendingLoadTime = now
        return scope.launch {
            try {
                val monthPrefix = currentTimeFormatted("yyyy-MM")
                val todayKey = currentTimeFormatted("yyyy-MM-dd")
                val yesterdayKey = formatTimestamp(getTodayStart(now) - 86_400_000L, "yyyy-MM-dd")
                val accumulator = SpendingAccumulator(todayKey, yesterdayKey, monthPrefix)
                var page = 0
                while (page < SPENDING_MAX_PAGES) {
                    val result = api.getBillList(token, page = page, size = BILL_PAGE_SIZE, status = BILL_STATUS)
                    if (currentToken() != token) return@launch
                    if (result.records.isEmpty()) break
                    for (record in result.records) {
                        accumulator.add(record, formatTimestamp(record.time, "yyyy-MM-dd"))
                    }
                    val oldest = result.records.minOfOrNull { it.time } ?: break
                    if (formatTimestamp(oldest, "yyyy-MM") < monthPrefix) break
                    if (result.records.size < BILL_PAGE_SIZE) break
                    page++
                }
                spendingStats = accumulator.build()
            } catch (e: Exception) {
                onLogError("loadSpendingStats", e)
            }
        }
    }

    fun loadRechargeProducts() {
        val token = currentToken()
        val wallet = getState().wallets.firstOrNull { it.id == getState().activeWalletId }
        if (token.isEmpty() || wallet == null || wallet.eid.isEmpty()) return
        rechargeLoading = true
        scope.launch {
            try {
                val products = api.getRechargeProducts(token, wallet.eid)
                if (currentToken() != token || getState().activeWalletId != wallet.id) return@launch
                rechargeProducts = products
            } catch (e: Exception) {
                onLogError("loadRechargeProducts", e)
            } finally {
                rechargeLoading = false
            }
        }
    }

    suspend fun submitRefund(): DevResult {
        val token = currentToken()
        val wallet =
            getState().wallets.firstOrNull { it.id == getState().activeWalletId }
                ?: return DevResult(false, -1, "钱包信息缺失，请重新进入")
        if (wallet.eid.isEmpty()) return DevResult(false, -1, "钱包信息缺失，请重新进入")
        if (wallet.refundable <= 0.0) return DevResult(false, -1, "可退金额为0，无法退款")
        if (refundSubmitting) return DevResult(false, -1, "正在提交退款，请稍候")
        refundSubmitting = true
        return try {
            val result = api.refundWallet(token, wallet.eid)
            if (result.success) {
                loadWallet()
                loadBillsNoCooldown()
                result
            } else {
                // 错误信息由 API 层上报，返回值不再重复携带
                DevResult(false, result.code, "")
            }
        } catch (e: Exception) {
            DevResult(false, -1, "退款失败：${e.message}")
        } finally {
            refundSubmitting = false
        }
    }
}
