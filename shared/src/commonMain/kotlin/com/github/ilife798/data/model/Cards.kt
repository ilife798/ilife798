package com.github.ilife798.data.model

// 云卡：学校/单位发的实体卡在账号内绑定后的形态，可刷 "慧生活798" 设备。
// 服务端返回的 CardInfo 里 holder/ep 是完整对象，这里只保留界面需要的字段。
data class CloudCard(
    val id: String,
    // 卡的显示名，绑定或设置时由用户填写
    val name: String = "",
    // 持卡人姓名，取 CardInfo.holder.name
    val holderName: String = "",
    // 发卡商名称，取 CardInfo.ep.name
    val enterpriseName: String = "",
    // 发卡商简称，取 CardInfo.ep.abbr
    val enterpriseAbbr: String = "",
    // 额度，取 CardInfo.limit
    val limit: Double = 0.0,
    // 上次激活时间戳，取 CardInfo.atime
    val activatedAt: Long = 0L,
    // 激活有效期时间戳，取 CardInfo.validTime
    val validTime: Long = 0L,
    // 是否可在全国设备使用，取 CardInfo.otherEp
    val otherEp: Int = 0,
    val token: String = "",
) {
    val displayName: String
        get() = name.ifEmpty { enterpriseAbbr.ifEmpty { enterpriseName.ifEmpty { id } } }

    // 卡片标题：以持卡人（卡名称）为主，缺失时逐级回退，保证标题永不为空
    val cardTitle: String
        get() = holderName.ifEmpty { displayName }
}

// 云卡需要每 12 小时激活一次，超过该间隔即视为待激活。
const val CARD_ACTIVATE_INTERVAL_MS: Long = 12L * 60L * 60L * 1000L

// 判断一张卡是否已过激活时限。validTime 为服务端给出的有效期终点，
// 缺失（<=0）时退回用 activatedAt + 12 小时推算；两者都缺失时视为需激活。
fun CloudCard.needsActivation(now: Long): Boolean {
    val deadline = if (validTime > 0L) validTime else activatedAt.takeIf { it > 0L }?.plus(CARD_ACTIVATE_INTERVAL_MS)
    if (deadline == null) return true
    return now >= deadline
}
