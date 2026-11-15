package com.github.ilife798.util

// 用系统默认处理器打开一个链接（http(s)、shortcuts:// 等）；返回是否成功发起跳转。
expect fun openExternalUrl(url: String): Boolean
