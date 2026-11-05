package com.github.ilife798.ui.page.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.github.ilife798.util.getDayOfWeek
import com.github.ilife798.util.isIgnoringBatteryOptimizations
import com.github.ilife798.util.openBatteryOptimizationSettings
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
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
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(state.appBlur)
    val isLoggedIn = state.account.hasAnyToken
    var showStopDialog by remember { mutableStateOf(false) }

    // 已忽略电池优化（设为「无限制」）后不再展示提示卡片，从设置返回后短暂重试
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }
    LaunchedEffect(AppLifecycle.resumeCount) {
        // 回到前台时若当前显示「今日任务已完成」，重新拉一次服务端状态：
        // App 挂在前台跨过零点时服务端已重置任务，本地完成态必须跟着恢复（issue #7）。
        // 未显示已完成时不额外请求。
        if (state.taskCompleted) viewModel.reloadTaskState()
        repeat(6) { attempt ->
            batteryUnrestricted = isIgnoringBatteryOptimizations()
            if (batteryUnrestricted || attempt == 5) return@LaunchedEffect
            delay(400.milliseconds)
        }
    }

    Scaffold(
        topBar = { BlurredTopAppBar("积分任务", blurBackdrop, scrollBehavior) },
    ) { paddingValues ->
        AppPullToRefresh(viewModel.tasksRefreshing, { viewModel.refreshTasks() }, scrollBehavior, paddingValues) {
            PageScrollColumn(
                blurBackdrop = blurBackdrop,
                scrollBehavior = scrollBehavior,
                contentPadding = paddingValues,
                bottomPadding = bottomPadding,
            ) {
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
                showStopDialog = false
            },
            onDismiss = { showStopDialog = false },
        )
    }
}
