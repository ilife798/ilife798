package com.github.ilife798.util

// 解析实体云卡二维码，取出卡号。
//
// 二维码原文格式已由实体卡实证：
//     https://i.hnkzy.com/q/1/123456789012
// 卡号取 URL 最后一段的 12 位数字（示例中为 123456789012）。
//
// 与官方 APK（慧生活798 2.0.16）反编译结果相互印证：
// - ModifyCardActivity 的扫码回调把结果写入「卡号」输入框，不是密钥输入框；
//   扫码与手动输入卡号是同一条绑定路径的两个入口。
// - 卡号校验见 com.ilife.lib.common.util.p0.d：^([a-fA-F0-9]{2})?\d{10}$
//   即「可选 2 位十六进制前缀 + 10 位数字」，共 12 位；官方文案「非12位卡号」
//   与 URL 末段恰好 12 位一致。
// - 绑卡密钥（CardBindParm.token）是 6 位数字，由服务端在首提 ic/bind 回 code=-15
//   时索取，不在二维码内，故不在此解析。
object CardQrParser {
    private val urlish = Regex("^(http|https)://", RegexOption.IGNORE_CASE)

    // 官方卡号规则：可选 2 位 hex 前缀 + 10 位数字（合计 10 或 12 位）。
    private val cardNo = Regex("^([a-fA-F0-9]{2})?\\d{10}$")

    // 二维码内容长度上限，防止误扫长文本被当成卡号提交。
    private const val MAX_CARD_RAW_LENGTH = 512

    data class Result(
        val cardNo: String = "",
    ) {
        val isValid: Boolean get() = cardNo.isNotEmpty()
    }

    // 判断一个字符串是否形如官方认定的卡号。
    fun isValidCardNo(value: String): Boolean = cardNo.matches(value.trim())

    fun parse(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_CARD_RAW_LENGTH) return Result()
        return Result(cardNo = extractCardNo(text))
    }

    private fun extractCardNo(text: String): String {
        if (!urlish.containsMatchIn(text)) {
            // 纯文本：本身就可能就是卡号（手动输入或卡片直接印码）。
            return if (isValidCardNo(text)) text else ""
        }

        // 已知格式：https://i.hnkzy.com/q/1/<卡号>，卡号是路径最后一段。
        lastSegment(text)?.let { segment ->
            if (isValidCardNo(segment)) return segment
        }

        // 兜底：换个域名/路径深度时，逐段找出第一个符合卡号规则的片段。
        // 末段优先已由上面覆盖，这里覆盖卡号不在末段的历史格式。
        return text
            .split('/', '?', '&', '#')
            .asSequence()
            .map { it.substringBefore('=').trim() }
            .firstOrNull { isValidCardNo(it) }
            .orEmpty()
    }

    // 取 URL 路径的最后一段，跳过查询串与锚点（"https://host/a/b" → "b"）。
    private fun lastSegment(text: String): String? =
        text
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
            .takeIf { it.isNotEmpty() }
}
