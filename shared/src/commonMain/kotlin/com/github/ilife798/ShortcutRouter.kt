package com.github.ilife798

import com.github.ilife798.data.model.ShortcutAction
import com.github.ilife798.data.model.ShortcutRequest
import com.github.ilife798.data.model.parseShortcutUrl

/**
 * 把外部链接（iOS 快捷指令的「打开 URL」、浏览器、其他应用）翻译成 [AppRequests] 信号。
 *
 * 这里刻意只写信号、不碰任何界面状态：真正的导航与业务动作由
 * [com.github.ilife798.ui.navigation.AppIntentEffects] 在 Compose 环境里消费，
 * 与 Android 的 `MainActivity.handleIntent` 完全同一条路径。
 * 这样 iOS 的 Swift 侧只需要把 URL 传进来，不需要持有 ViewModel。
 */
object ShortcutRouter {
    /**
     * 处理一个链接，返回是否识别成功。
     *
     * 无法识别时返回 false 且不产生任何副作用——链接可能来自任意应用，
     * 静默丢弃比弹错误提示更合适。
     */
    fun handleUrl(url: String?): Boolean {
        val request = parseShortcutUrl(url.orEmpty()) ?: return false
        return dispatch(request)
    }

    private fun dispatch(request: ShortcutRequest): Boolean {
        // 只有 ?bg=true 才后台执行；未指定时按动作缺省（批量运行缺省后台，其余缺省进应用内）
        val bg = request.bg ?: (request.action.bgDefault == true)
        when (request.action) {
            ShortcutAction.Home -> {
                AppRequests.openHome.request()
            }

            ShortcutAction.Tasks -> {
                AppRequests.openTasks.request()
            }

            ShortcutAction.RunTasks -> {
                AppRequests.runTasks.request(bg)
            }

            ShortcutAction.RunBatch -> {
                AppRequests.runBatch.request(BatchRunRequest(request.uidList(), bg))
            }

            ShortcutAction.RunAllTasks -> {
                AppRequests.runAllTasks.request(bg)
            }

            ShortcutAction.StopTasks -> {
                AppRequests.stopTasks.request(bg)
            }

            ShortcutAction.Score -> {
                AppRequests.openScore.request()
            }

            ShortcutAction.Bill -> {
                AppRequests.openBill.request()
            }

            ShortcutAction.Cards -> {
                AppRequests.openCards.request()
            }

            ShortcutAction.CardScan -> {
                AppRequests.cardScan.request()
            }

            ShortcutAction.Scan -> {
                AppRequests.scan.request()
            }

            ShortcutAction.DeviceAdd -> {
                // 链接可以带预填编号（devices/new/{id}、devices/add/{id}），也可以不带
                AppRequests.deviceAdd.request(request.arg("id"))
            }

            ShortcutAction.Device -> {
                val id = request.arg("id") ?: return false
                AppRequests.startDevice.request(id)
            }

            ShortcutAction.StopDevice -> {
                val id = request.arg("id") ?: return false
                AppRequests.stopDevice.request(DeviceActionRequest(id, bg))
            }

            ShortcutAction.RunDevice -> {
                val id = request.arg("id") ?: return false
                AppRequests.runDevice.request(DeviceActionRequest(id, bg))
            }

            ShortcutAction.Account -> {
                AppRequests.openAccount.request()
            }

            ShortcutAction.AccountAdd -> {
                AppRequests.accountAdd.request()
            }

            ShortcutAction.AccountAddPhone -> {
                val phone = request.arg("phone") ?: return false
                AppRequests.accountAddPhone.request(phone)
            }

            ShortcutAction.AccountAddToken -> {
                val appToken = request.arg("appToken").orEmpty()
                val token = request.arg("token").orEmpty()
                if (appToken.isEmpty() && token.isEmpty()) return false
                AppRequests.accountAddToken.request(AccountTokenPrefill(appToken, token, bg))
            }

            ShortcutAction.SwitchAccount -> {
                val account = request.arg("account") ?: return false
                AppRequests.switchAccount.request(account)
            }

            ShortcutAction.Me -> {
                AppRequests.openMe.request()
            }

            ShortcutAction.Backup -> {
                AppRequests.openBackup.request()
            }

            ShortcutAction.Schedule -> {
                AppRequests.openSchedule.request()
            }

            ShortcutAction.Refresh -> {
                AppRequests.refresh.request()
            }
        }
        return true
    }
}
