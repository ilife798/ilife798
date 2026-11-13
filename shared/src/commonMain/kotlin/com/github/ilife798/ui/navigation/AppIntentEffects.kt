package com.github.ilife798.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.github.ilife798.AppRequests
import com.github.ilife798.data.viewmodel.AppViewModel

// 平台 Intent、桌面快捷方式与运行通知触发的导航/操作请求
@Composable
fun AppIntentEffects(
    viewModel: AppViewModel,
    mainPagerState: MainPagerState,
    navigate: (Page) -> Unit,
    clearToRoot: () -> Unit,
) {
    // 桌面快捷方式“扫一扫”：冷启动与 onNewIntent 均通过该信号跳转扫码页
    val scanRequestId = AppRequests.scan.id
    LaunchedEffect(scanRequestId) {
        if (scanRequestId <= 0) return@LaunchedEffect
        navigate(Page.DeviceScan)
        AppRequests.scan.consume()
    }

    // NFC 标签：取出携带的设备链接，复用扫码解析→收藏→跳转详情的完整流程
    val tagUri = AppRequests.tagContent.value
    LaunchedEffect(tagUri) {
        if (tagUri.isNullOrEmpty()) return@LaunchedEffect
        viewModel.submitScannedRaw(tagUri)
        AppRequests.tagContent.consume()
    }

    // 桌面小组件：校验并切换绑定账号，再打开设备详情。
    val widgetDevice = AppRequests.openWidgetDevice.value
    LaunchedEffect(widgetDevice) {
        val target = widgetDevice ?: return@LaunchedEffect
        if (!viewModel.selectWidgetAccount(target)) {
            AppRequests.openWidgetDevice.consume()
            return@LaunchedEffect
        }
        clearToRoot()
        mainPagerState.animateToPage(0)
        navigate(Page.DeviceDetail(target.deviceId))
        // 导航可能复用同一设备页面；每次点击卡片都重新查询绑定账号下的详情。
        viewModel.requestWidgetDeviceDetailRefresh()
        AppRequests.openWidgetDevice.consume()
    }

    // 快捷设置图块：回到首页并打开对应设备详情页
    val startDeviceId = AppRequests.startDevice.value
    LaunchedEffect(startDeviceId) {
        val id = startDeviceId ?: return@LaunchedEffect
        clearToRoot()
        mainPagerState.animateToPage(0)
        navigate(Page.DeviceDetail(id))
        AppRequests.startDevice.consume()
    }

    // 桌面快捷方式“运行积分任务” / scheme 运行积分任务：bg=true 时后台执行不跳转
    val runTasksBg = AppRequests.runTasks.value
    LaunchedEffect(runTasksBg) {
        val bg = runTasksBg ?: return@LaunchedEffect
        if (!bg) {
            clearToRoot()
            mainPagerState.animateToPage(1)
        }
        viewModel.runTasksFromShortcut()
        AppRequests.runTasks.consume()
    }

    // 定时任务闹钟到点：与手动按下批量运行按钮等价，全部已登录账号依次执行
    val runScheduledTasksRequestId = AppRequests.runScheduledTasks.id
    LaunchedEffect(runScheduledTasksRequestId) {
        if (runScheduledTasksRequestId <= 0) return@LaunchedEffect
        clearToRoot()
        mainPagerState.animateToPage(1)
        viewModel.runScheduledTasks()
        AppRequests.runScheduledTasks.consume()
    }

    // 点击“正在下载”通知：回到“我的”页并重新弹出下载对话框
    val updateRequestId = AppRequests.showUpdate.id
    LaunchedEffect(updateRequestId) {
        if (updateRequestId <= 0) return@LaunchedEffect
        clearToRoot()
        mainPagerState.animateToPage(2)
        viewModel.reopenUpdateProgressDialog()
        AppRequests.showUpdate.consume()
    }

    // 点击设备运行通知正文：仅回到首页
    val openHomeRequestId = AppRequests.openHome.id
    LaunchedEffect(openHomeRequestId) {
        if (openHomeRequestId <= 0) return@LaunchedEffect
        clearToRoot()
        mainPagerState.animateToPage(0)
        AppRequests.openHome.consume()
    }

    // 设备运行通知“停止”按钮 / scheme devices/{id}/stop：bg=true 时后台执行不跳转
    val stopDeviceRequest = AppRequests.stopDevice.value
    LaunchedEffect(stopDeviceRequest) {
        val request = stopDeviceRequest ?: return@LaunchedEffect
        if (!request.bg) {
            clearToRoot()
            mainPagerState.animateToPage(0)
        }
        viewModel.stopDevice(request.id)
        AppRequests.stopDevice.consume()
    }

    // scheme devices/{id}/run 启动设备：bg=true 时后台执行不跳转
    val runDeviceRequest = AppRequests.runDevice.value
    LaunchedEffect(runDeviceRequest) {
        val request = runDeviceRequest ?: return@LaunchedEffect
        if (!request.bg) {
            clearToRoot()
            mainPagerState.animateToPage(0)
        }
        viewModel.runDevice(request.id)
        AppRequests.runDevice.consume()
    }

    // 点击积分任务通知正文：跳转到任务页
    val openTasksRequestId = AppRequests.openTasks.id
    LaunchedEffect(openTasksRequestId) {
        if (openTasksRequestId <= 0) return@LaunchedEffect
        clearToRoot()
        mainPagerState.animateToPage(1)
        AppRequests.openTasks.consume()
    }

    // 积分任务通知“停止”按钮 / scheme tasks/stop：bg=true 时后台执行不跳转
    val stopTasksBg = AppRequests.stopTasks.value
    LaunchedEffect(stopTasksBg) {
        val bg = stopTasksBg ?: return@LaunchedEffect
        if (!bg) {
            clearToRoot()
            mainPagerState.animateToPage(1)
        }
        viewModel.stopTasks()
        AppRequests.stopTasks.consume()
    }

    // 以下是快捷指令 / 外部链接（ilife798://…）触发的页面跳转，与 Android 的
    // handleIntent 共用同一套信号。纯跳转类动作只切页，不改变业务状态。
    val openScoreRequestId = AppRequests.openScore.id
    LaunchedEffect(openScoreRequestId) {
        if (openScoreRequestId <= 0) return@LaunchedEffect
        navigate(Page.Score)
        AppRequests.openScore.consume()
    }

    val openBillRequestId = AppRequests.openBill.id
    LaunchedEffect(openBillRequestId) {
        if (openBillRequestId <= 0) return@LaunchedEffect
        navigate(Page.Bill)
        AppRequests.openBill.consume()
    }

    val openCardsRequestId = AppRequests.openCards.id
    LaunchedEffect(openCardsRequestId) {
        if (openCardsRequestId <= 0) return@LaunchedEffect
        navigate(Page.CardBag)
        AppRequests.openCards.consume()
    }

    val cardScanRequestId = AppRequests.cardScan.id
    LaunchedEffect(cardScanRequestId) {
        if (cardScanRequestId <= 0) return@LaunchedEffect
        navigate(Page.CardScan)
        AppRequests.cardScan.consume()
    }

    // 添加设备：value 非空时把编号带进页面预填
    val deviceAddPrefill = AppRequests.deviceAdd.value
    LaunchedEffect(deviceAddPrefill) {
        if (deviceAddPrefill == null) return@LaunchedEffect
        clearToRoot()
        mainPagerState.animateToPage(0)
        navigate(Page.DeviceAdd(prefillId = deviceAddPrefill))
        AppRequests.deviceAdd.consume()
    }

    val openAccountRequestId = AppRequests.openAccount.id
    LaunchedEffect(openAccountRequestId) {
        if (openAccountRequestId <= 0) return@LaunchedEffect
        navigate(Page.Account)
        AppRequests.openAccount.consume()
    }

    // 切换账号：链接里可能是序号（accounts/2），也可能是 uid / 手机号（accounts/{id}）。
    // 序号越界或与当前账号相同时 viewModel 内部会直接返回，不需要在这里重复校验。
    val switchAccountTarget = AppRequests.switchAccount.value
    LaunchedEffect(switchAccountTarget) {
        val target = switchAccountTarget ?: return@LaunchedEffect
        val list = viewModel.state.accountList
        val index =
            target.toIntOrNull()?.takeIf { it in list.indices }
                ?: list
                    .indexOfFirst { entry ->
                        entry.account.uid == target || entry.account.phone == target
                    }.takeIf { it >= 0 }
        if (index != null) viewModel.switchAccount(index)
        AppRequests.switchAccount.consume()
    }

    // 添加账号：打开手机号登录页
    val accountAddRequestId = AppRequests.accountAdd.id
    LaunchedEffect(accountAddRequestId) {
        if (accountAddRequestId <= 0) return@LaunchedEffect
        clearToRoot()
        mainPagerState.animateToPage(2)
        navigate(Page.Login(switchAccount = true))
        AppRequests.accountAdd.consume()
    }

    // 添加账号并预填手机号
    val accountAddPhonePrefill = AppRequests.accountAddPhone.value
    LaunchedEffect(accountAddPhonePrefill) {
        val phone = accountAddPhonePrefill ?: return@LaunchedEffect
        clearToRoot()
        mainPagerState.animateToPage(2)
        navigate(Page.Login(switchAccount = true, prefillPhone = phone))
        AppRequests.accountAddPhone.consume()
    }

    // 添加账号并预填凭据（设备控制 / 积分任务两个槽位）；bg=true 时静默导入不跳转
    val accountAddTokenPrefill = AppRequests.accountAddToken.value
    LaunchedEffect(accountAddTokenPrefill) {
        val prefill = accountAddTokenPrefill ?: return@LaunchedEffect
        if (prefill.bg) {
            viewModel.loginWithCredentials(prefill.appToken, prefill.token)
        } else {
            clearToRoot()
            mainPagerState.animateToPage(2)
            navigate(Page.TokenLogin(initialAppToken = prefill.appToken, initialToken = prefill.token))
        }
        AppRequests.accountAddToken.consume()
    }

    val openMeRequestId = AppRequests.openMe.id
    LaunchedEffect(openMeRequestId) {
        if (openMeRequestId <= 0) return@LaunchedEffect
        clearToRoot()
        mainPagerState.animateToPage(2)
        AppRequests.openMe.consume()
    }

    val openBackupRequestId = AppRequests.openBackup.id
    LaunchedEffect(openBackupRequestId) {
        if (openBackupRequestId <= 0) return@LaunchedEffect
        navigate(Page.Backup)
        AppRequests.openBackup.consume()
    }

    val openScheduleRequestId = AppRequests.openSchedule.id
    LaunchedEffect(openScheduleRequestId) {
        if (openScheduleRequestId <= 0) return@LaunchedEffect
        navigate(Page.TaskSchedule)
        AppRequests.openSchedule.consume()
    }

    // 刷新：与下拉刷新、回到前台时做的事保持一致（账号资产 + 任务状态）。
    val refreshRequestId = AppRequests.refresh.id
    LaunchedEffect(refreshRequestId) {
        if (refreshRequestId <= 0) return@LaunchedEffect
        viewModel.refreshAllAccountAssets()
        viewModel.reloadTaskState()
        AppRequests.refresh.consume()
    }

    // 快捷指令「依次运行账号」：request.uids 为 null 时表示全部已登录账号。
    val runBatchRequest = AppRequests.runBatch.value
    LaunchedEffect(runBatchRequest) {
        val request = runBatchRequest ?: return@LaunchedEffect
        if (!request.bg) {
            clearToRoot()
            mainPagerState.animateToPage(1)
        }
        viewModel.startBatchTasks(request.uids)
        AppRequests.runBatch.consume()
    }

    // 快捷指令「依次运行全部账号」：等价于任务页的批量按钮（不传 uid 即全部）
    val runAllTasksBg = AppRequests.runAllTasks.value
    LaunchedEffect(runAllTasksBg) {
        val bg = runAllTasksBg ?: return@LaunchedEffect
        if (!bg) {
            clearToRoot()
            mainPagerState.animateToPage(1)
        }
        viewModel.startBatchTasks(null)
        AppRequests.runAllTasks.consume()
    }
}
