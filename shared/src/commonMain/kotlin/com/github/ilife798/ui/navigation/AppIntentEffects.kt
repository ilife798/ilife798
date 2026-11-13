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

    // 桌面快捷方式“运行积分任务”：跳转到任务页并开始运行
    val runTasksRequestId = AppRequests.runTasks.id
    LaunchedEffect(runTasksRequestId) {
        if (runTasksRequestId <= 0) return@LaunchedEffect
        clearToRoot()
        mainPagerState.animateToPage(1)
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

    // 点击设备运行通知“停止”按钮：回到首页并停止对应设备
    val stopDeviceId = AppRequests.stopDevice.value
    LaunchedEffect(stopDeviceId) {
        val id = stopDeviceId ?: return@LaunchedEffect
        clearToRoot()
        mainPagerState.animateToPage(0)
        viewModel.stopDevice(id)
        AppRequests.stopDevice.consume()
    }

    // 点击积分任务通知正文：跳转到任务页
    val openTasksRequestId = AppRequests.openTasks.id
    LaunchedEffect(openTasksRequestId) {
        if (openTasksRequestId <= 0) return@LaunchedEffect
        clearToRoot()
        mainPagerState.animateToPage(1)
        AppRequests.openTasks.consume()
    }

    // 点击积分任务通知“停止”按钮：跳转到任务页并停止任务
    val stopTasksRequestId = AppRequests.stopTasks.id
    LaunchedEffect(stopTasksRequestId) {
        if (stopTasksRequestId <= 0) return@LaunchedEffect
        clearToRoot()
        mainPagerState.animateToPage(1)
        viewModel.stopTasks()
        AppRequests.stopTasks.consume()
    }
}
