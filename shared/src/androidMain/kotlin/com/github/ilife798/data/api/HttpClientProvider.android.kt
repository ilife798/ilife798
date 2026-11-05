package com.github.ilife798.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

actual fun createHttpClient(): HttpClient = createAndroidHttpClient()

internal fun createAndroidHttpClient(
    allowConnectionRetry: Boolean = true,
    pool: okhttp3.ConnectionPool? = null,
): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config {
                retryOnConnectionFailure(allowConnectionRetry)
                pool?.let { connectionPool(it) }
            }
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }
        install(HttpTimeout) {
            connectTimeoutMillis = ApiConfig.CONNECT_TIMEOUT_MS
            requestTimeoutMillis = ApiConfig.REQUEST_TIMEOUT_MS
            socketTimeoutMillis = ApiConfig.SOCKET_TIMEOUT_MS
        }
        if (ApiConfig.debugLogging) {
            install(Logging) {
                logger =
                    object : Logger {
                        override fun log(message: String) {
                            android.util.Log.i("ILIFE798-API", message)
                        }
                    }
                level = LogLevel.ALL
            }
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
            header("Accept-Language", "zh-Hans-CN;q=1")
            header("User-Agent", ApiConfig.USER_AGENT)
            if (headers["VersionCode"] == null) header("VersionCode", ApiConfig.VERSION_CODE)
        }
    }
