package com.example.tv_caller_app.data.remote

import android.util.Log
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.datasource.BackendNotConfiguredException
import com.example.tv_caller_app.data.datasource.BackendRequestException
import com.example.tv_caller_app.data.datasource.ProfileNotLinkedException
import com.example.tv_caller_app.data.datasource.SessionExpiredException
import com.example.tv_caller_app.data.repository.AuthRepository
import io.github.jan.supabase.gotrue.auth
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders

/** Shared authenticated transport for the patient-facing .NET API. */
object AuthenticatedBackendClient {
    private const val TAG = "AuthenticatedBackend"

    suspend fun get(
        path: String,
        sessionManager: SessionManager,
        authRepository: AuthRepository
    ): String = request(path, null, sessionManager, authRepository, allowRetry = true)

    suspend fun postJson(
        path: String,
        jsonBody: String,
        sessionManager: SessionManager,
        authRepository: AuthRepository
    ): String = request(path, jsonBody, sessionManager, authRepository, allowRetry = true)

    private suspend fun request(
        path: String,
        jsonBody: String?,
        sessionManager: SessionManager,
        authRepository: AuthRepository,
        allowRetry: Boolean
    ): String {
        if (!BackendApiClient.isConfigured) throw BackendNotConfiguredException()
        val token = currentToken(sessionManager) ?: throw SessionExpiredException()

        val response = if (jsonBody == null) {
            BackendApiClient.client.get(BackendApiClient.url(path)) {
                header(HttpHeaders.Authorization, "Bearer $token")
                accept(ContentType.Application.Json)
            }
        } else {
            BackendApiClient.client.post(BackendApiClient.url(path)) {
                header(HttpHeaders.Authorization, "Bearer $token")
                accept(ContentType.Application.Json)
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(jsonBody)
            }
        }

        return processResponse(
            response,
            path,
            jsonBody,
            sessionManager,
            authRepository,
            allowRetry
        )
    }

    private suspend fun processResponse(
        response: HttpResponse,
        path: String,
        jsonBody: String?,
        sessionManager: SessionManager,
        authRepository: AuthRepository,
        allowRetry: Boolean
    ): String {
        val status = response.status.value
        if (status in 200..299) return response.bodyAsText()

        if (status == 401) {
            if (!allowRetry) throw SessionExpiredException()
            Log.w(TAG, "Backend returned 401; refreshing the Supabase session once")
            authRepository.refreshSession().getOrElse { throw SessionExpiredException() }
            return request(path, jsonBody, sessionManager, authRepository, allowRetry = false)
        }

        if (status == 404 &&
            (path.endsWith("/mine") || path.startsWith("api/tv/check-ins/"))) {
            throw ProfileNotLinkedException()
        }

        val detail = response.bodyAsText().trim()
        throw BackendRequestException(
            detail.takeIf { it.isNotBlank() } ?: "Backend request failed (HTTP $status)"
        )
    }

    private fun currentToken(sessionManager: SessionManager): String? =
        SupabaseClient.client.auth.currentSessionOrNull()?.accessToken
            ?: sessionManager.getAccessToken()
}
