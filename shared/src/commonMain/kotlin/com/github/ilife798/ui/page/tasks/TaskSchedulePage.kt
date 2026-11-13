package com.github.ilife798.ui.page.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.ilife798.TaskScheduler
import com.github.ilife798.data.model.TaskSchedule
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.ui.component.BlurredTopAppBar
import com.github.ilife798.ui.component.PageScrollColumn
import com.github.ilife798.ui.component.SwitchPreference
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import com.github.ilife798.util.isIgnoringBatteryOptimizations
import com.github.ilife798.util.openBatteryOptimizationSettings
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「定时任务」页面。
 *
 * 只配置「什么时候跑」：到点后依次执行全部已登录账号的积分任务，与任务页的批量执行等价。
 * 触发由系统的闹钟完成，因此页面额外提示电池优化与「应用被强制停止后需重新打开一次」。
 */
@Composable
fun TaskSchedulePage(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberAppBlurBackdrop(viewModel.state.appBlur)
    val schedule = viewModel.state.taskSchedule
    val supported = TaskScheduler.supported
    var hour by remember(schedule.hour) { mutableIntStateOf(schedule.hour) }
    var minute by remember(schedule.minute) { mutableIntStateOf(schedule.minute) }

    fun update(transform: (TaskSchedule) -> TaskSchedule) {
        viewModel.setTaskSchedule(transform(viewModel.state.taskSchedule))
    }

    // 拨盘滚动会连续回调，等停手再落盘：写入偏好与重排闹钟都不适合逐帧触发。
    LaunchedEffect(hour, minute) {
        if (hour == schedule.hour && minute == schedule.minute) return@LaunchedEffect
        delay(TIME_PICK_DEBOUNCE_MS)
        update { it.copy(hour = hour, minute = minute) }
    }

    Scaffold(
        topBar = { BlurredTopAppBar("定时任务", blurBackdrop, scrollBehavior, onBack = onBack) },
    ) { paddingValues ->
        PageScrollColumn(
            blurBackdrop = blurBackdrop,
            scrollBehavior = scrollBehavior,
            contentPadding = paddingValues,
        ) {
            Text(
                text = "到设定时间后，依次执行所有已登录账号的积分任务，执行过程与手动按下「运行选中账号」完全一致，进度显示在通知栏。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )

            if (!supported) {
                UnsupportedCard()
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = "定时执行积分任务",
                        summary = if (schedule.enabled) "${schedule.timeText} · ${schedule.weekdayText}" else "未启用",
                        checked = schedule.enabled,
                        onCheckedChange = { enabled -> update { it.copy(enabled = enabled) } },
                        enabled = supported,
                    )
                }
            }

            SmallTitle(text = "执行时间", insideMargin = PaddingValues(4.dp, 8.dp, 4.dp, 0.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NumberPicker(
                            value = hour,
                            onValueChange = { hour = it },
                            modifier = Modifier.weight(1f),
                            enabled = supported,
                            range = 0..23,
                            label = { it.toString().padStart(2, '0') },
                        )
                        Text(
                            text = ":",
                            style = MiuixTheme.textStyles.title1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        NumberPicker(
                            value = minute,
                            onValueChange = { minute = it },
                            modifier = Modifier.weight(1f),
                            enabled = supported,
                            range = 0..59,
                            label = { it.toString().padStart(2, '0') },
                        )
                    }
                    Text(
                        text = "时间以设备本地时区为准。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    )
                }
            }

            SmallTitle(text = "重复", insideMargin = PaddingValues(4.dp, 8.dp, 4.dp, 0.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TaskSchedule.WEEKDAY_SHORT_NAMES.forEachIndexed { index, shortName ->
                            val dayOfWeek = index + 1
                            WeekdayChip(
                                label = shortName,
                                selected = dayOfWeek in schedule.weekdays,
                                enabled = supported,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    update { current ->
                                        val next =
                                            if (dayOfWeek in current.weekdays) {
                                                current.weekdays - dayOfWeek
                                            } else {
                                                current.weekdays + dayOfWeek
                                            }
                                        current.copy(weekdays = next)
                                    }
                                },
                            )
                        }
                    }
                    Text(
                        text = "全部未选等同于每天执行。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    )
                }
            }

            // 到点后是拉起应用执行，还是留在后台静默跑完
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    OverlayDropdownPreference(
                        title = "运行方式",
                        items = TaskSchedule.RUN_MODES,
                        selectedIndex = if (schedule.runInBackground) 1 else 0,
                        enabled = supported,
                        onSelectedIndexChange = { index ->
                            update { it.copy(runInBackground = index == 1) }
                        },
                    )
                    Text(
                        text =
                            if (schedule.runInBackground) {
                                "到点后在后台静默执行，不会打断你正在做的事；系统可能因省电策略延后或限制执行。"
                            } else {
                                "到点后打开应用并执行，执行过程与手动按下「运行选中账号」完全一致。"
                            },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    )
                }
            }

            val nextRun = viewModel.taskScheduleNextRunText()
            if (schedule.enabled && nextRun != null) {
                Text(
                    text = "下次执行：$nextRun",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (supported && !TaskScheduler.exactAlarmGranted) {
                Spacer(Modifier.height(4.dp))
                ExactAlarmCard()
            }

            if (supported && !isIgnoringBatteryOptimizations()) {
                Spacer(Modifier.height(4.dp))
                BatteryCard()
            }

            Text(
                text = "定时触发依赖系统闹钟，省电策略可能把执行推迟数分钟；应用被强制停止或重装后，需要重新打开一次应用才会恢复定时。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WeekdayChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background =
        when {
            selected -> MiuixTheme.colorScheme.primary
            else -> MiuixTheme.colorScheme.surfaceContainerHigh
        }
    val contentColor =
        when {
            selected -> MiuixTheme.colorScheme.onPrimary
            else -> MiuixTheme.colorScheme.onSurface
        }
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .background(background, CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            color = contentColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun UnsupportedCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "当前平台暂不支持定时执行，请使用 Android 版本。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ExactAlarmCard() {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { TaskScheduler.openExactAlarmSettings() },
        colors =
            CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                contentColor = MiuixTheme.colorScheme.onSurface,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "开启「闹钟和提醒」权限",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "未开启时系统会把执行推迟到省电时段的空档，可能晚上数小时。点击前往授权。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun BatteryCard() {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { openBatteryOptimizationSettings() },
        colors =
            CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                contentColor = MiuixTheme.colorScheme.onSurface,
            ),
    ) {
        Text(
            text = "为保证到点能稳定执行，请允许本应用忽略电池优化。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** 拨盘停止滚动后才落盘的等待时间。 */
private const val TIME_PICK_DEBOUNCE_MS = 400L
