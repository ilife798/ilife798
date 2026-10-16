package com.github.ilife798.util

// 赞助支持链接（设置页同名入口）。
const val SPONSOR_URL = "https://afdian.com/a/jursin"

// 打开赞助页面，返回是否成功发起跳转（用于区分“确实跳出了外部应用”与“弹窗被取消”）。
expect fun openSponsorPage(url: String): Boolean
