package com.github.ilife798.update

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json

private const val RELEASES_API = "https://api.github.com/repos/ilife798/ilife798/releases/latest"

object AppUpdate {
    // 更新移交回退：未找到本平台安装包时打开发布页。
    const val RELEASES_PAGE_URL = "https://github.com/ilife798/ilife798/releases"

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy { createUpdateHttpClient() }

    suspend fun fetchLatestRelease(): GithubRelease {
        val body =
            client
                .get(RELEASES_API) {
                    header(HttpHeaders.Accept, "application/vnd.github+json")
                    header(HttpHeaders.UserAgent, "ilife798-update")
                }.bodyAsText()
        return json.decodeFromString(body)
    }

    fun selectAsset(
        release: GithubRelease,
        abis: List<String>,
    ): GithubAsset? {
        for (abi in abis) {
            release.assets
                .firstOrNull { it.name.contains(abi, ignoreCase = true) && it.browserDownloadUrl.isNotEmpty() }
                ?.let { return it }
        }
        return null
    }

    // 按 .ipa 选择本平台安装包，忽略大小写且须有下载链接。
    fun selectIpaAsset(release: GithubRelease): GithubAsset? =
        release.assets.firstOrNull {
            it.name.endsWith(".ipa", ignoreCase = true) && it.browserDownloadUrl.isNotEmpty()
        }

    fun compareVersions(
        first: String,
        second: String,
    ): Int {
        val firstParts = first.split('.')
        val secondParts = second.split('.')
        for (index in 0 until maxOf(firstParts.size, secondParts.size)) {
            val a = firstParts.getOrNull(index)?.trim()?.toIntOrNull() ?: 0
            val b = secondParts.getOrNull(index)?.trim()?.toIntOrNull() ?: 0
            if (a != b) return a - b
        }
        return 0
    }

    fun applyProxy(
        url: String,
        proxy: String,
    ): String {
        val trimmed = proxy.trim()
        if (trimmed.isEmpty()) return url
        return trimmed.trimEnd('/') + "/" + url
    }
}
