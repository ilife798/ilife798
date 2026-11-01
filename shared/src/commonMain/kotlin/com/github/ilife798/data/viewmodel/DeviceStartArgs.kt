package com.github.ilife798.data.viewmodel

import com.github.ilife798.data.model.DeviceGoods

// 通道设备固定 {pos,mode}，其余有模式走 {mode,ext}，仅通道走 {pos,mode:null}；
// washingFallbackMode：洗衣机非海尔网关未选模式时发 {"mode":-1,"ext":[...]}
fun buildDeviceDetailStartArgs(
    goods: List<DeviceGoods>,
    partMode: Int?,
    channelIndex: Int,
    passageDevice: Boolean,
    washingFallbackMode: Boolean = false,
): String {
    val ext = goods.joinToString(",") { """{"pos":${it.pos},"out":${it.out}}""" }
    if (passageDevice) {
        if (channelIndex < 0) return ""
        return """{"pos":$channelIndex,"mode":${partMode ?: "null"}}"""
    }
    if (partMode != null) {
        return """{"mode":$partMode,"ext":[$ext]}"""
    }
    if (channelIndex >= 0) {
        return """{"pos":$channelIndex,"mode":null}"""
    }
    if (washingFallbackMode) {
        return """{"mode":-1,"ext":[$ext]}"""
    }
    return ""
}
