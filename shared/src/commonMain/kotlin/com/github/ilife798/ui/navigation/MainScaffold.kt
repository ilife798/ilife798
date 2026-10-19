package com.github.ilife798.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.github.ilife798.data.model.ImportPreview
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.showToast
import com.github.ilife798.ui.page.account.AccountPage
import com.github.ilife798.ui.page.backup.BackupImportPage
import com.github.ilife798.ui.page.backup.BackupPage
import com.github.ilife798.ui.page.bill.BillDetailPage
import com.github.ilife798.ui.page.bill.BillPage
import com.github.ilife798.ui.page.device.DeviceAddPage
import com.github.ilife798.ui.page.device.DeviceDetailPage
import com.github.ilife798.ui.page.device.QrScannerPage
import com.github.ilife798.ui.page.home.HomePage
import com.github.ilife798.ui.page.license.LicensePage
import com.github.ilife798.ui.page.login.LoginMethodPage
import com.github.ilife798.ui.page.login.LoginPage
import com.github.ilife798.ui.page.login.TokenLoginPage
import com.github.ilife798.ui.page.me.CardBagPage
import com.github.ilife798.ui.page.me.MePage
import com.github.ilife798.ui.page.score.ScorePage
import com.github.ilife798.ui.page.tasks.TasksPage
import com.github.ilife798.ui.theme.appBarBlur
import com.github.ilife798.ui.theme.blurAppBarColor
import com.github.ilife798.ui.theme.captureForBlur
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.runtime.NavChange
import top.yukonga.miuix.kmp.nav.transition.NavGesture
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavRole
import top.yukonga.miuix.kmp.nav.transition.NavSettle
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val tabs =
    listOf(
        NavigationItem("首页", MiuixIcons.Home),
        NavigationItem("任务", MiuixIcons.ListView),
        NavigationItem("我的", MiuixIcons.Contacts),
    )

// 宽屏阈值：达到该宽度时改用 NavigationRail 侧边导航
private val WIDE_SCREEN_MIN_WIDTH = 600.dp

// 宽屏二级页面动效：从中心放大进入、缩小退出
private val WideScaleTransition: NavTransition =
    navGraphicsTransition(
        opaqueDepth = 2f,
        motion =
            NavMotion(
                commit = NavSettleSpec.Tween(200, FastOutSlowInEasing),
                cancel = NavSettleSpec.Tween(200, FastOutSlowInEasing),
                programmatic = NavSettleSpec.Tween(200, FastOutSlowInEasing),
            ),
    ) { scope ->
        val depth = scope.relativeDepth
        if (depth <= 0f) {
            val progress = (-depth).coerceIn(0f, 1f)
            val scale = 1f - 0.12f * progress
            scaleX = scale
            scaleY = scale
            alpha = 1f - 0.2f * progress
        }
    }

// 跳过条件：MultiPop 扫出，或从添加页进入详情后的手势预览
private fun shouldSkipDeviceAdd(
    scope: NavTransitionScope,
    skipArmed: Boolean,
): Boolean = scope.change is NavChange.MultiPop || (skipArmed && scope.gesture != null)

// 跳过时覆盖层深度前移一层(d-1)使揭示页跟手；仅 depth>0 生效
private class SkippedDepthScope(
    private val delegate: NavTransitionScope,
    private val skipping: () -> Boolean,
) : NavTransitionScope {
    override val relativeDepth: Float
        get() {
            val raw = delegate.relativeDepth
            return if (raw > 0f && skipping()) (raw - 1f).coerceAtLeast(0f) else raw
        }

    override val role: NavRole get() = delegate.role

    override val change: NavChange get() = delegate.change

    override val gesture: NavGesture? get() = delegate.gesture

    override val settle: NavSettle? get() = delegate.settle

    override val layoutSize: IntSize get() = delegate.layoutSize

    override val layoutDirection: LayoutDirection get() = delegate.layoutDirection

    override val density: Density get() = delegate.density
}

// DeviceAdd 路由：跳过时前移深度 + scrim 归零；opaqueDepth=2 保证 Home 在 depth 2 不被窗口裁掉
private fun NavTransition.withDeviceAddSkip(isSkipping: (NavTransitionScope) -> Boolean): NavTransition =
    object : NavTransition {
        private val base = this@withDeviceAddSkip

        override fun Modifier.transformEntry(scope: NavTransitionScope): Modifier {
            val modifier = this
            val shifted = SkippedDepthScope(scope) { isSkipping(scope) }
            return with(base) { modifier.transformEntry(shifted) }
        }

        override val opaqueDepth: Float = 2f

        override val dismissDirection: NavSwipeDirection get() = base.dismissDirection

        override val motion: NavMotion get() = base.motion

        override fun scrimFraction(scope: NavTransitionScope): Float = if (isSkipping(scope)) 0f else base.scrimFraction(scope)
    }

@Composable
private fun NavBarContent(
    floatingNav: Boolean,
    navBarBackdrop: LayerBackdrop?,
    pagerState: PagerState,
    onSelect: (Int) -> Unit,
) {
    val blurActive = navBarBackdrop != null
    if (floatingNav) {
        FloatingNavigationBar(
            color = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
            modifier =
                if (blurActive) {
                    Modifier.appBarBlur(
                        navBarBackdrop,
                        shape = RoundedCornerShape(50.dp),
                        blendColor = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f),
                    )
                } else {
                    Modifier
                },
        ) {
            tabs.forEachIndexed { index, tab ->
                FloatingNavigationBarItem(
                    selected = pagerState.currentPage == index,
                    onClick = { onSelect(index) },
                    icon = tab.icon,
                    label = tab.label,
                )
            }
        }
    } else {
        NavigationBar(
            color = blurAppBarColor(navBarBackdrop),
            modifier = Modifier.appBarBlur(navBarBackdrop),
        ) {
            tabs.forEachIndexed { index, tab ->
                NavigationBarItem(
                    selected = pagerState.currentPage == index,
                    onClick = { onSelect(index) },
                    icon = tab.icon,
                    label = tab.label,
                )
            }
        }
    }
}

@Composable
private fun HomePagerContent(
    viewModel: AppViewModel,
    pagerState: PagerState,
    navigate: (Page) -> Unit,
    bottomPadding: Dp,
    blurBackdrop: LayerBackdrop?,
    wideScreen: Boolean,
) {
    val pagerModifier =
        Modifier
            .fillMaxSize()
            .captureForBlur(blurBackdrop)
    val pageContent: @Composable (Int) -> Unit = { page ->
        HomePagerPage(
            viewModel = viewModel,
            page = page,
            navigate = navigate,
            bottomPadding = bottomPadding,
            wideScreen = wideScreen,
        )
    }
    if (wideScreen) {
        VerticalPager(
            state = pagerState,
            userScrollEnabled = true,
            beyondViewportPageCount = 1,
            modifier = pagerModifier,
        ) { page -> pageContent(page) }
    } else {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = true,
            beyondViewportPageCount = 1,
            modifier = pagerModifier,
        ) { page -> pageContent(page) }
    }
}

@Composable
private fun HomePagerPage(
    viewModel: AppViewModel,
    page: Int,
    navigate: (Page) -> Unit,
    bottomPadding: Dp,
    wideScreen: Boolean,
) {
    when (page) {
        0 -> {
            HomePage(
                viewModel = viewModel,
                onDeviceAddClick = { navigate(Page.DeviceAdd) },
                onDeviceClick = { navigate(Page.DeviceDetail(it)) },
                bottomPadding = bottomPadding,
            )
        }

        1 -> {
            TasksPage(
                viewModel = viewModel,
                onLoginClick = { isAlipay -> navigate(Page.Login(isAlipay = isAlipay)) },
                bottomPadding = bottomPadding,
            )
        }

        2 -> {
            MePage(
                viewModel = viewModel,
                onLoginClick = { isAlipay -> navigate(Page.Login(isAlipay = isAlipay)) },
                onScoreClick = { navigate(Page.Score) },
                onAccountClick = { navigate(Page.Account) },
                onBillClick = { navigate(Page.Bill) },
                onCardBagClick = { navigate(Page.CardBag) },
                onLicenseClick = { navigate(Page.License) },
                onBackupClick = { navigate(Page.Backup) },
                bottomPadding = bottomPadding,
                wideScreen = wideScreen,
            )
        }
    }
}

@Composable
private fun NavEntry(
    interceptPredictiveBack: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = state,
        isBackEnabled = interceptPredictiveBack,
        onBackCompleted = onBack,
    )
    content()
}

@Composable
fun MainScaffold(viewModel: AppViewModel) {
    val navBackStack = rememberNavBackStack<Page>(Page.Home)

    // 增删查用 MutableList 视图，避免 IDE 在 commonMain 解析 SnapshotStateList（Android 实现 Parcelable）
    val backStack: MutableList<NavKey> = navBackStack
    val floatingNav = viewModel.state.floatingNav
    val predictiveBackEnabled = viewModel.state.predictiveBackEnabled
    // 已从添加页压入详情：本次返回（含手势预览）跳过添加页
    val skipDeviceAdd = rememberSaveable { mutableStateOf(false) }

    // 导入预览只在本次会话内有效：含有账号凭据，刻意不随路由参数序列化，也不写盘。
    // 页面回到此路由时若已丢失（进程被回收），导入页会立即退回。
    var pendingImport by remember { mutableStateOf<ImportPreview?>(null) }
    val importToken = remember { mutableStateOf(0L) }
    val nextImportToken: () -> Long = {
        importToken.value += 1
        importToken.value
    }

    val onBack: () -> Unit =
        remember(backStack) {
            {
                val skipAdd =
                    backStack.size >= 2 &&
                        backStack.last() is Page.DeviceDetail &&
                        backStack[backStack.lastIndex - 1] == Page.DeviceAdd
                backStack.removeLastOrNull()
                if (skipAdd) {
                    backStack.removeLastOrNull()
                }
            }
        }

    // 防止连点重复压入相同路由（Navigation 3 不允许重复 key）
    val navigate: (Page) -> Unit =
        remember(backStack) {
            { page ->
                if (page == Page.DeviceAdd) skipDeviceAdd.value = false
                if (backStack.lastOrNull() != page) backStack.add(page)
            }
        }

    val clearToRoot: () -> Unit =
        remember(backStack) {
            { while (backStack.size > 1) backStack.removeLastOrNull() }
        }

    val interceptPredictiveBack = !predictiveBackEnabled && backStack.size > 1

    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    val mainPagerState = rememberMainPagerState(pagerState, coroutineScope)
    val navBarBackdrop = rememberAppBlurBackdrop(viewModel.state.appBlur)
    val navRailState = rememberNavigationRailState()

    // 扫码/NFC 解析完成：自动收藏，清栈回首页并打开对应设备详情页
    val scannedDeviceId = viewModel.scannedDeviceId
    LaunchedEffect(scannedDeviceId) {
        if (scannedDeviceId == null) return@LaunchedEffect
        viewModel.consumeScannedDeviceId()
        viewModel.addDevice(scannedDeviceId)
        clearToRoot()
        mainPagerState.animateToPage(0)
        navigate(Page.DeviceDetail(scannedDeviceId))
    }

    // 平台 Intent/快捷方式/通知触发的导航与操作
    AppIntentEffects(
        viewModel = viewModel,
        mainPagerState = mainPagerState,
        navigate = navigate,
        clearToRoot = clearToRoot,
    )

    LaunchedEffect(pagerState.currentPage) {
        mainPagerState.syncPage()
        when (pagerState.currentPage) {
            0 -> {
                viewModel.loadDeviceInfo()
                viewModel.loadScoreInfo()
                viewModel.loadSpendingStats()
            }

            1 -> {
                viewModel.loadMissions()
            }

            2 -> {
                viewModel.loadAccountInfo()
            }
        }
    }

    // 回到上一层：在非首个 Tab 时先回到首个 Tab
    val isPagerBackHandlerEnabled by remember {
        androidx.compose.runtime.derivedStateOf {
            backStack.size == 1 && mainPagerState.selectedPage != 0
        }
    }
    val pagerBackEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = pagerBackEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = { mainPagerState.animateToPage(0) },
    )

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background),
    ) {
        val wideScreen = maxWidth >= WIDE_SCREEN_MIN_WIDTH
        val wideCornerRadius = rememberNavSystemCornerRadius()
        val detailTransition = if (wideScreen) WideScaleTransition else null
        val deviceAddTransition =
            remember(detailTransition) {
                (detailTransition ?: NavTransitions.MiuixDefault)
                    .withDeviceAddSkip { scope -> shouldSkipDeviceAdd(scope, skipDeviceAdd.value) }
            }
        NavDisplay(
            backStack = navBackStack,
            onBack = onBack,
            transition = NavTransitions.MiuixDefault,
            effects =
                NavDisplayEffects(
                    enableCornerClip = true,
                    cornerClipRadius = if (wideScreen) wideCornerRadius else 32.dp,
                    cornerClipMode = if (wideScreen) NavCornerClipMode.All else NavCornerClipMode.Leading,
                    dimAmount = 0.5f,
                    backdropColor = if (wideScreen) MiuixTheme.colorScheme.background else MiuixTheme.colorScheme.surface,
                    blockInputDuringTransition = false,
                ),
        ) {
            entry<Page.Home> {
                NavEntry(interceptPredictiveBack, onBack) {
                    if (wideScreen) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            NavigationRail(state = navRailState, showDivider = false) {
                                tabs.forEachIndexed { index, tab ->
                                    NavigationRailItem(
                                        selected = pagerState.currentPage == index,
                                        onClick = { mainPagerState.animateToPage(index) },
                                        icon = tab.icon,
                                        label = tab.label,
                                    )
                                }
                            }
                            VerticalDivider(
                                modifier =
                                    Modifier
                                        .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)),
                            )
                            HomePagerContent(
                                viewModel = viewModel,
                                pagerState = pagerState,
                                navigate = navigate,
                                bottomPadding = 16.dp,
                                blurBackdrop = null,
                                wideScreen = true,
                            )
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            HomePagerContent(
                                viewModel = viewModel,
                                pagerState = pagerState,
                                navigate = navigate,
                                bottomPadding = 80.dp,
                                blurBackdrop = navBarBackdrop,
                                wideScreen = false,
                            )
                            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                                NavBarContent(
                                    floatingNav = floatingNav,
                                    navBarBackdrop = navBarBackdrop,
                                    pagerState = pagerState,
                                    onSelect = { mainPagerState.animateToPage(it) },
                                )
                            }
                        }
                    }
                }
            }
            entry<Page.Login>(transition = detailTransition) { key ->
                NavEntry(interceptPredictiveBack, onBack) {
                    LoginPage(
                        viewModel = viewModel,
                        isAlipay = key.isAlipay,
                        switchAccount = key.switchAccount,
                        onBack = onBack,
                    )
                }
            }
            entry<Page.LoginMethod>(transition = detailTransition) {
                NavEntry(interceptPredictiveBack, onBack) {
                    LoginMethodPage(
                        viewModel = viewModel,
                        onPhoneLogin = { navigate(Page.Login(switchAccount = true)) },
                        onCredentialLogin = { navigate(Page.TokenLogin) },
                        onBack = onBack,
                    )
                }
            }
            entry<Page.TokenLogin>(transition = detailTransition) {
                NavEntry(interceptPredictiveBack, onBack) {
                    TokenLoginPage(
                        viewModel = viewModel,
                        onBack = onBack,
                    )
                }
            }
            entry<Page.Score>(transition = detailTransition) {
                NavEntry(interceptPredictiveBack, onBack) {
                    ScorePage(
                        viewModel = viewModel,
                        onBack = onBack,
                    )
                }
            }
            entry<Page.Account>(transition = detailTransition) {
                NavEntry(interceptPredictiveBack, onBack) {
                    AccountPage(
                        viewModel = viewModel,
                        onBack = onBack,
                        onLoginClick = { navigate(Page.LoginMethod) },
                    )
                }
            }
            entry<Page.Bill>(transition = detailTransition) {
                NavEntry(interceptPredictiveBack, onBack) {
                    BillPage(
                        viewModel = viewModel,
                        onBack = onBack,
                        onRecordClick = { navigate(Page.BillDetail(it)) },
                    )
                }
            }
            entry<Page.BillDetail>(transition = detailTransition) { key ->
                NavEntry(interceptPredictiveBack, onBack) {
                    BillDetailPage(
                        viewModel = viewModel,
                        billId = key.billId,
                        onBack = onBack,
                    )
                }
            }
            entry<Page.DeviceAdd>(transition = deviceAddTransition) {
                NavEntry(interceptPredictiveBack, onBack) {
                    val scope = LocalNavTransitionScope.current
                    Box(
                        Modifier.graphicsLayer {
                            if (shouldSkipDeviceAdd(scope, skipDeviceAdd.value)) {
                                alpha = 0f
                                translationX = scope.layoutSize.width.toFloat()
                            }
                        },
                    ) {
                        DeviceAddPage(
                            viewModel = viewModel,
                            onBack = onBack,
                            onScanClick = {
                                navigate(Page.DeviceScan)
                            },
                            onNavigateToDevice = { id ->
                                // 压入详情以保留进入动画；跳过添加页见 onBack 与视觉层
                                skipDeviceAdd.value = true
                                navigate(Page.DeviceDetail(id))
                            },
                        )
                    }
                }
            }
            entry<Page.DeviceScan>(transition = detailTransition) {
                NavEntry(interceptPredictiveBack, onBack) {
                    QrScannerPage(
                        onBack = onBack,
                        onResult = { raw ->
                            viewModel.submitScannedRaw(raw)
                            onBack()
                        },
                    )
                }
            }
            entry<Page.CardBag>(transition = detailTransition) {
                NavEntry(interceptPredictiveBack, onBack) {
                    CardBagPage(
                        viewModel = viewModel,
                        onScannerRequest = { navigate(Page.CardScan) },
                        onBack = onBack,
                    )
                }
            }
            entry<Page.CardScan>(transition = detailTransition) {
                NavEntry(interceptPredictiveBack, onBack) {
                    QrScannerPage(
                        onBack = onBack,
                        onResult = { raw ->
                            viewModel.submitCardScannedRaw(raw)
                            onBack()
                        },
                    )
                }
            }
            entry<Page.DeviceDetail>(transition = detailTransition) { key ->
                val closeDetail: () -> Unit = {
                    viewModel.onDeviceDetailClosed(key.deviceId)
                    onBack()
                }
                NavEntry(interceptPredictiveBack, closeDetail) {
                    DeviceDetailPage(
                        viewModel = viewModel,
                        deviceId = key.deviceId,
                        onBack = closeDetail,
                    )
                }
            }
            entry<Page.License>(transition = detailTransition) {
                NavEntry(interceptPredictiveBack, onBack) {
                    LicensePage(
                        viewModel = viewModel,
                        onBack = onBack,
                    )
                }
            }
            entry<Page.Backup>(transition = detailTransition) {
                NavEntry(interceptPredictiveBack, onBack) {
                    BackupPage(
                        viewModel = viewModel,
                        onBack = onBack,
                        onImportReady = { preview ->
                            pendingImport = preview
                            navigate(Page.BackupImport(token = nextImportToken()))
                        },
                    )
                }
            }
            entry<Page.BackupImport>(transition = detailTransition) {
                NavEntry(interceptPredictiveBack, onBack) {
                    val preview = pendingImport
                    if (preview == null) {
                        // 进程被回收后回到此路由时预览已丢失，直接退回，避免空白页
                        LaunchedEffect(Unit) { onBack() }
                    } else {
                        BackupImportPage(
                            viewModel = viewModel,
                            preview = preview,
                            onBack = onBack,
                            onFinished = { count ->
                                viewModel.clearImportDocument()
                                pendingImport = null
                                if (count > 0) {
                                    showToast("已导入 $count 项")
                                } else {
                                    showToast("没有可导入的内容")
                                }
                                onBack()
                            },
                        )
                    }
                }
            }
        }
    }
}
