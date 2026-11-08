package com.github.ilife798.ui.page.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.ilife798.AppLifecycle
import com.github.ilife798.copyToClipboard
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.ui.component.AppPullToRefresh
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.ConfirmDialog
import com.github.ilife798.ui.component.EmptyStateText
import com.github.ilife798.ui.component.LoadingSpinner
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.component.SectionHeader
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import com.github.ilife798.util.formatMoney
import com.github.ilife798.util.getDayOfWeek
import com.github.ilife798.util.isIgnoringBatteryOptimizations
import com.github.ilife798.util.openBatteryOptimizationSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun TasksPage(
    viewModel: AppViewModel,
    onLoginClick: (isAlipay: Boolean) -> Unit = {},
    bottomPadding: Dp,
) {
    val state = viewModel.state
    val missions = viewModel.missions
    val isLoading = viewModel.isLoading
    // 多账号批量运行：勾选账号后依次切号执行，跑完恢复原账号
    val batchScope = rememberCoroutineScope()
    var batching by remember { mutableStateOf(false) }
    // 批量协程句柄，用于「停止」时整批中断
    var batchJob by remember { mutableStateOf<Job?>(null) }
    val accountRows = viewModel.state.accountList
    var selectedAccounts by remember(accountRows.size) {
        mutableStateOf(accountRows.indices.filter { accountRows[it].account.hasAnyToken }.toSet())
    }

    // 账号下标在批量运行期间可能因增删账号而偏移，改用 uid 按账号顺序锚定身份，
    // 每轮执行前再按 uid 反查当前下标，避免切错账号。
    fun uidOf(index: Int): String =
        viewModel.state.accountList
            .getOrNull(index)
            ?.account
            ?.uid
            .orEmpty()

    fun indexOfUid(uid: String): Int =
        if (uid.isEmpty()) {
            -1
        } else {
            viewModel.state.accountList.indexOfFirst { it.account.uid == uid }
        }
    // 各账号积分直接读设备控制器里的资产快照，与「首页 - 设备详情 - 账号」同一份数据源；
    // 只在内存里，切号或重启后由下面的批量拉取重新获取。
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(state.appBlur)
    val isLoggedIn = state.account.hasAnyToken
    var showStopDialog by remember { mutableStateOf(false) }

    // 已忽略电池优化（设为「无限制」）后不再展示提示卡片，从设置返回后短暂重试
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }
    // 进入任务页就按服务端刷新一次任务进度：列表若停留在几小时前的快照，
    // 会和执行器看到的「已完成/未完成」不一致（表现为状态记录错误）。
    // loadMissions 自带 3 秒冷却，来回切页不会重复请求。
    LaunchedEffect(Unit) { viewModel.loadMissions() }

    // 进入页面时为所有账号各拉一次积分，账号列表因此能显示每个账号的真实积分
    LaunchedEffect(Unit) { viewModel.refreshAllAccountAssets() }

    LaunchedEffect(AppLifecycle.resumeCount) {
        // 回到前台同样刷新：既覆盖跨零点重置（issue #7），也覆盖后台期间在
        // 其它设备上跑过任务的情况，保证列表进度始终是服务端最新值。
        viewModel.reloadTaskState()
        repeat(6) { attempt ->
            batteryUnrestricted = isIgnoringBatteryOptimizations()
            if (batteryUnrestricted || attempt == 5) return@LaunchedEffect
            delay(400.milliseconds)
        }
    }

    Scaffold(
        topBar = { BlurredTopAppBar("积分任务", blurBackdrop, scrollBehavior) },
    ) { paddingValues ->
        AppPullToRefresh(
            viewModel.tasksRefreshing,
            {
                // 下拉刷新同时把所有账号的积分重拉一遍，账号行显示的数字才不会滞留
                viewModel.refreshAllAccountAssets()
                viewModel.refreshTasks()
            },
            scrollBehavior,
            paddingValues,
        ) {
            PageScrollColumn(
                blurBackdrop = blurBackdrop,
                scrollBehavior = scrollBehavior,
                contentPadding = paddingValues,
                bottomPadding = bottomPadding,
            ) {
                if (accountRows.size <= 1) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (isLoading) showStopDialog = true else viewModel.runAllTasks()
                        },
                        enabled = isLoading || (isLoggedIn && !state.taskCompleted),
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        if (isLoading) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                LoadingSpinner()
                                Text(text = "运行中，点击停止")
                            }
                        } else {
                            Text(text = if (state.taskCompleted) "今日任务已完成" else "运行积分任务")
                        }
                    }
                }

                if (accountRows.size > 1) {
                    val selectable = accountRows.indices.filter { accountRows[it].account.hasAnyToken }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            SectionHeader("账号")
                            // 全选行：三态复选框，部分选中显示半选
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = selectable.isNotEmpty()) {
                                            selectedAccounts =
                                                if (selectedAccounts.size == selectable.size) emptySet() else selectable.toSet()
                                        }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "全选",
                                    style = MiuixTheme.textStyles.body1,
                                    modifier = Modifier.weight(1f),
                                )
                                Checkbox(
                                    state =
                                        when {
                                            selectedAccounts.isEmpty() -> ToggleableState.Off
                                            selectedAccounts.containsAll(selectable) -> ToggleableState.On
                                            else -> ToggleableState.Indeterminate
                                        },
                                    onClick = {
                                        selectedAccounts =
                                            if (selectedAccounts.size == selectable.size) emptySet() else selectable.toSet()
                                    },
                                )
                            }
                            accountRows.forEachIndexed { index, entry ->
                                if (!entry.account.hasAnyToken) return@forEachIndexed
                                val checked = index in selectedAccounts
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedAccounts =
                                                    if (checked) {
                                                        selectedAccounts - index
                                                    } else {
                                                        selectedAccounts + index
                                                    }
                                            }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text =
                                                entry.info.name.ifBlank {
                                                    entry.account.phone.ifBlank { entry.account.uid }
                                                },
                                            style = MiuixTheme.textStyles.body1,
                                        )
                                        val points = viewModel.accountAssetsOf(index).points
                                        Text(
                                            text =
                                                if (points != null) {
                                                    val prefix =
                                                        if (index == viewModel.state.currentAccountIndex) "当前 · " else ""
                                                    "${prefix}积分: $points ≈ ¥${formatMoney(points / 1000.0)}"
                                                } else if (index == viewModel.state.currentAccountIndex) {
                                                    "当前账号"
                                                } else {
                                                    "积分查询中…"
                                                },
                                            style = MiuixTheme.textStyles.body2,
                                            color =
                                                if (index == viewModel.state.currentAccountIndex) {
                                                    MiuixTheme.colorScheme.primary
                                                } else {
                                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                                },
                                        )
                                    }
                                    Checkbox(
                                        state = if (checked) ToggleableState.On else ToggleableState.Off,
                                        onClick = {
                                            selectedAccounts =
                                                if (checked) selectedAccounts - index else selectedAccounts + index
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (isLoading) {
                                        showStopDialog = true
                                        return@TextButton
                                    }
                                    if (batching) return@TextButton
                                    // 按账号顺序拍下 (uid, 原下标) 快照，后续一律用 uid 定位。
                                    // uid 异常为空时退回原下标，不至于被静默跳过。
                                    val targets =
                                        selectedAccounts
                                            .sorted()
                                            .map { index -> uidOf(index) to index }
                                    if (targets.isEmpty()) return@TextButton
                                    batching = true
                                    batchJob =
                                        batchScope.launch {
                                            val originUid = viewModel.state.account.uid
                                            val originIndex = viewModel.state.currentAccountIndex
                                            // 无论正常结束、异常还是被取消，都要把 batching 复位并切回原账号，
                                            // 否则按钮会永久卡在「运行中，点击停止」再也点不动
                                            try {
                                                for ((uid, fallbackIndex) in targets) {
                                                    // 每轮重新按 uid 反查下标：期间若删过账号，下标会整体偏移。
                                                    // uid 为空时退回用快照里的原下标。
                                                    val index = indexOfUid(uid).takeIf { it >= 0 } ?: fallbackIndex
                                                    val entry = viewModel.state.accountList.getOrNull(index) ?: continue
                                                    if (!entry.account.hasAnyToken) continue
                                                    if (index != viewModel.state.currentAccountIndex) {
                                                        viewModel.switchAccount(index)
                                                        // 等切号后的数据重载完成再请求，避免用旧 token 发起任务
                                                        delay(1_500)
                                                    }
                                                    viewModel.runAllTasks()
                                                    // 等本轮结束：isLoading 由执行器驱动，最多等 10 分钟
                                                    var waited = 0
                                                    while (viewModel.isLoading && waited < 1_200) {
                                                        delay(500)
                                                        waited++
                                                    }
                                                    // 跑完一个账号就单独刷新它的积分，账号行立刻反映到账结果。
                                                    // 只刷这一个账号，避免一次对全站发起大量 mission-lst 撞 -98。
                                                    // 稍等再拉：让服务端的积分流水先落库。
                                                    delay(1_000)
                                                    val refreshIndex = indexOfUid(uid).takeIf { it >= 0 } ?: fallbackIndex
                                                    viewModel.refreshAccountAssets(refreshIndex)
                                                }
                                            } finally {
                                                // 同样按 uid 找回原账号，它若被删除则退回原下标，仍不存在就保持不动
                                                val back = indexOfUid(originUid).takeIf { it >= 0 } ?: originIndex
                                                if (back in viewModel.state.accountList.indices) {
                                                    viewModel.switchAccount(back)
                                                }
                                                batching = false
                                                batchJob = null
                                            }
                                        }
                                },
                                text =
                                    when {
                                        isLoading || batching -> "运行中，点击停止"
                                        else -> "运行选中账号（${selectedAccounts.size}）"
                                    },
                                enabled = batching || isLoading || selectedAccounts.isNotEmpty(),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }

                if (missions.isNotEmpty()) {
                    val signIn = missions.firstOrNull { it.isDailySignin }
                    val regularMissions = missions.filter { !it.isDailySignin && it.limit > 0 && it.score > 0 }
                    val allMissions = listOfNotNull(signIn) + regularMissions

                    if (allMissions.isNotEmpty()) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                SectionHeader("任务列表 (${allMissions.size})")
                                allMissions.forEach { mission ->
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = mission.name,
                                                style = MiuixTheme.textStyles.body2,
                                                color = MiuixTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text =
                                                    if (mission.isDailySignin) {
                                                        "每次 +${mission.score} 分"
                                                    } else {
                                                        "上限 ${mission.limit} 次 · 每次 +${mission.score} 分"
                                                    },
                                                style = MiuixTheme.textStyles.body2,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                        }
                                        if (mission.isDailySignin) {
                                            val weekDay = getDayOfWeek()
                                            val alreadySigned = (state.weekMask and (1 shl (weekDay - 1))) != 0
                                            if (alreadySigned) {
                                                Text(
                                                    text = "已签到",
                                                    style = MiuixTheme.textStyles.body2,
                                                    color = MiuixTheme.colorScheme.primary,
                                                )
                                            }
                                        } else if (mission.dailyCompleted > 0) {
                                            Text(
                                                text = "已完成（${mission.dailyCompleted}/${mission.limit}）",
                                                style = MiuixTheme.textStyles.body2,
                                                color =
                                                    if (mission.dailyCompleted >= mission.limit) {
                                                        MiuixTheme.colorScheme.primary
                                                    } else {
                                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                                    },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            SectionHeader("任务列表")
                            EmptyStateText(isLoggedIn)
                        }
                    }
                }

                // 2026-11-05 定稿：两种登录创建不同会话（stype:1 设备会话 / stype:5 任务会话），
                // 任务列表互补——积分登录确实能解锁任务侧任务（外卖红包/视频20/猜一猜），横幅保留。
                if (state.account.appToken.isNotEmpty() && !state.account.pointsLoginDone && state.taskLogs.isEmpty()) {
                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onLoginClick(true) },
                        colors =
                            CardDefaults.defaultColors(
                                color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                contentColor = MiuixTheme.colorScheme.onSurface,
                            ),
                    ) {
                        Text(
                            text = "完成积分登录解锁更多任务。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                if (state.taskLogs.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                // 行高由图标按钮撑起，标题与图标中线对齐
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "运行日志",
                                    style = MiuixTheme.textStyles.title3,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                IconButton(
                                    minHeight = 32.dp,
                                    minWidth = 32.dp,
                                    backgroundColor = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                                    onClick = {
                                        copyToClipboard(state.taskLogs.joinToString("\n"), "已复制到剪贴板")
                                    },
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Copy,
                                        contentDescription = "复制日志",
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.7f else 0.9f),
                                    )
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            state.taskLogs.forEach { log ->
                                Text(
                                    text = log,
                                    style = MiuixTheme.textStyles.body2,
                                    color =
                                        if (log.contains("成功")) {
                                            MiuixTheme.colorScheme.primary
                                        } else if (log.contains("失败") || log.contains("异常")) {
                                            MiuixTheme.colorScheme.error
                                        } else {
                                            MiuixTheme.colorScheme.onSurface
                                        },
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }

                if (!batteryUnrestricted) {
                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { openBatteryOptimizationSettings() },
                        colors =
                            CardDefaults.defaultColors(
                                color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                contentColor = MiuixTheme.colorScheme.onSurface,
                            ),
                    ) {
                        Text(
                            text = "为保证切到后台或锁屏后任务仍能稳定运行，请允许本应用忽略电池优化。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    if (showStopDialog) {
        ConfirmDialog(
            title = "停止任务",
            message = "确定要停止正在运行的积分任务吗？",
            appBlur = state.appBlur,
            confirmText = "停止",
            destructive = true,
            onConfirm = {
                viewModel.stopTasks()
                // 批量执行时「停止」的语义是整批停止：先取消批量协程，
                // 否则它会在 isLoading 归 false 后继续切下一个账号接着刷
                batchJob?.cancel()
                batchJob = null
                batching = false
                showStopDialog = false
            },
            onDismiss = { showStopDialog = false },
        )
    }
}
