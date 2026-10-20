package com.github.ilife798.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

// 与 Android OkHttp 实现保持同等语义：同一 JSON 配置、默认请求头与超时。
// Darwin 引擎底层是 NSURLSession，HttpTimeout 插件在该引擎上只支持 request/socket 超时，
// connectTimeoutMillis 会被引擎忽略（连接阶段由系统 NSURLSession 配置接管）。
actual fun createHttpClient(): HttpClient =
    HttpClient(Darwin) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }
        install(HttpTimeout) {
            requestTimeoutMillis = ApiConfig.REQUEST_TIMEOUT_MS
            socketTimeoutMillis = ApiConfig.SOCKET_TIMEOUT_MS
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
            header("Accept-Language", "zh-Hans-CN;q=1")
            header("User-Agent", ApiConfig.USER_AGENT)
            header("VersionCode", ApiConfig.VERSION_CODE)
        }
    }
