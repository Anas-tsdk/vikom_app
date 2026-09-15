package com.example.tv_caller_app.data.remote

import com.example.tv_caller_app.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json

object BackendApiClient {
    val baseUrl: String = BuildConfig.BACKEND_BASE_URL.trim()
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    fun url(path: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')
}

