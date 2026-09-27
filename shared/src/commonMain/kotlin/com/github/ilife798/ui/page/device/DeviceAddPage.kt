package com.github.ilife798.ui.page.device

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.github.ilife798.data.viewmodel.AppViewModel
import com.github.ilife798.showToast
import com.github.ilife798.ui.theme.appBarBlur
import com.github.ilife798.ui.theme.blurAppBarColor
import com.github.ilife798.ui.theme.captureForBlur
import com.github.ilife798.ui.theme.primaryButtonColors
import com.github.ilife798.ui.theme.rememberAppBlurBackdrop
import com.github.ilife798.util.DeviceLinkParser
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun DeviceAddPage(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onScanClick: () -> Unit = {},
    onNavigateToDevice: (String) -> Unit = {},
) {
    var deviceId by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val deviceIdFocusRequester = remember { FocusRequester() }
    val scrollBehavior = MiuixScrollBehavior()

    // 进入页面自动聚焦设备编号输入框并弹出键盘
    LaunchedEffect(Unit) {
        deviceIdFocusRequester.requestFocus()
        keyboard?.show()
    }
    val dynamicColor = viewModel.state.dynamicColor
    val blurBackdrop = rememberAppBlurBackdrop(viewModel.state.appBlur)

    Scaffold(
        topBar = {
            TopAppBar(
                title = "添加设备",
                modifier = Modifier.appBarBlur(blurBackdrop),
                color = blurAppBarColor(blurBackdrop),
                defaultWindowInsetsPadding = false,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .captureForBlur(blurBackdrop)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            focusManager.clearFocus()
                            keyboard?.hide()
                        }
                    }.scrollEndHaptic()
                    .overScrollVertical()
                    .verticalScroll(rememberScrollState())
                    .padding(paddingValues)
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = deviceId,
                        onValueChange = { deviceId = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(deviceIdFocusRequester),
                        label = "设备编号",
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                focusManager.clearFocus()
                                onScanClick()
                            },
                            colors = primaryButtonColors(dynamicColor),
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Scan,
                                contentDescription = "扫描",
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "扫一扫")
                        }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val id = deviceId.trim()
                                if (!DeviceLinkParser.isValidDeviceId(id)) {
                                    showToast("请输入12到16位的字母和数字组成的有效设备编号")
                                } else {
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                    viewModel.addDevice(id)
                                    onNavigateToDevice(id)
                                }
                            },
                            enabled = deviceId.isNotBlank(),
                            colors = primaryButtonColors(dynamicColor),
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Add,
                                contentDescription = "添加",
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "添加")
                        }
                    }
                }
            }
        }
    }
}
