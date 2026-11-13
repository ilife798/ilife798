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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonColors
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
    // 多账号批量运行：勾选账号后依次切号执行，跑完恢复原账号。
    // 具体流程在 TaskBatchController 里，定时任务走的是同一份实现。
    val batching = viewModel.batching
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
                            // 单账号运行：通知正文不带账号前缀
                            if (isLoading) showStopDialog = true else viewModel.runSingleAccountTasks()
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
                    // 运行期间禁止改选账号。批量循环会主动切号，此时改选会让 uid 快照与实际账号错位；
                    // 单账号运行同理，切号会让正在发出的请求落到另一个账号上。
                    val selectionLocked = isLoading || batching
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            SectionHeader("账号")
                            // 全选行：三态复选框，部分选中显示半选
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = selectable.isNotEmpty() && !selectionLocked) {
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
                                    enabled = selectable.isNotEmpty() && !selectionLocked,
                                )
                            }
                            accountRows.forEachIndexed { index, entry ->
                                if (!entry.account.hasAnyToken) return@forEachIndexed
                                val checked = index in selectedAccounts
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !selectionLocked) {
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
                                        enabled = !selectionLocked,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            // TextButton 只有 text: String 重载，塞不进图标；它本身就是
                            // Button + 固定文案 + 颜色映射，这里改用 Button 走等价配色，
                            // 渲染结果与原来一致，同时能容纳左侧的加载图标。
                            // 运行中从主色换成常规色：此时按钮语义已变成「停止」，
                            // 继续用主色会让人以为还能再发起一轮。
                            val textButtonColors =
                                if (isLoading || batching) {
                                    ButtonDefaults.textButtonColors()
                                } else {
                                    ButtonDefaults.textButtonColorsPrimary()
                                }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    // 运行中（含批量切号的间隙）点击都是「停止」，
                                    // 不能只判 isLoading：批量在两个账号之间时它短暂为 false
                                    if (isLoading || batching) {
                                        showStopDialog = true
                                        return@Button
                                    }
                                    // 只把选中的 uid 交出去：切号、等待、刷新都在 TaskBatchController
                                    // 里完成，定时任务调用的是同一份实现
                                    val targets = selectedAccounts.sorted().map { index -> uidOf(index) }
                                    if (targets.isEmpty()) return@Button
                                    viewModel.startBatchTasks(targets)
                                },
                                enabled = batching || isLoading || selectedAccounts.isNotEmpty(),
                                colors =
                                    ButtonColors(
                                        color = textButtonColors.color,
                                        disabledColor = textButtonColors.disabledColor,
                                        contentColor = textButtonColors.textColor,
                                        disabledContentColor = textButtonColors.disabledTextColor,
                                    ),
                            ) {
                                if (isLoading || batching) {
                                    LoadingSpinner()
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    text =
                                        when {
                                            isLoading || batching -> "运行中，点击停止"
                                            else -> "运行选中账号（${selectedAccounts.size}）"
                                        },
                                    style = MiuixTheme.textStyles.button,
                                )
                            }
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
                // stopTasks 同时停掉当前账号这一轮与整批循环，
                // 否则批量协程会在 isLoading 归 false 后继续切下一个账号接着刷
                viewModel.stopTasks()
                showStopDialog = false
            },
            onDismiss = { showStopDialog = false },
        )
    }
}
