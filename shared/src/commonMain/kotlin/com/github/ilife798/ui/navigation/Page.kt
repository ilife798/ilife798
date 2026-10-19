package com.github.ilife798.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed class Page : NavKey {
    @Serializable data object Home : Page()

    @Serializable data class Login(
        val isAlipay: Boolean = false,
        val switchAccount: Boolean = false,
    ) : Page()

    /** 登录方式选择页，从账号管理页的「登录其它账号」进入。 */
    @Serializable data object LoginMethod : Page()

    /** 账户凭证登录页，直接填写设备控制与积分任务的登录信息。 */
    @Serializable data object TokenLogin : Page()

    /** 我的卡包：云卡绑定、挂失与激活。 */
    @Serializable data object CardBag : Page()

    /** 扫码绑定云卡，结果交给 [com.github.ilife798.data.viewmodel.CardController.bindCardFromRaw]。 */
    @Serializable data object CardScan : Page()

    @Serializable data object Score : Page()

    @Serializable data object Account : Page()

    @Serializable data object Bill : Page()

    @Serializable data class BillDetail(
        val billId: String,
    ) : Page()

    @Serializable data object DeviceAdd : Page()

    @Serializable data object DeviceScan : Page()

    @Serializable data class DeviceDetail(
        val deviceId: String,
    ) : Page()

    @Serializable data object License : Page()

    @Serializable data object Backup : Page()

    /**
     * 导入备份页。预览数据无法放进路由参数（含账号凭据，且结构较大），因此只带一个
     * 会话内的令牌，真正的 [com.github.ilife798.data.model.ImportPreview] 由
     * MainScaffold 在发起导入时暂存。
     */
    @Serializable data class BackupImport(
        val token: Long,
    ) : Page()
}
