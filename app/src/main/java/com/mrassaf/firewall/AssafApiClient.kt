package com.mrassaf.firewall

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small native Android client for the ASSAF API.
 * Never store account passwords; only the short-lived bearer token is saved locally.
 */
object AssafApiClient {
    const val BASE_URL = "https://assaf-firewall-1.onrender.com"
    @Volatile private var deviceInstallId: String? = null
    fun configureInstallId(value: String) { deviceInstallId = value }

    private fun request(
        method: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null
    ): String {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            deviceInstallId?.let { setRequestProperty("X-ASSAF-Install-ID", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(response).optString("detail") }.getOrDefault("")
                throw IOException(detail.ifBlank { "HTTP $code: $response" })
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    fun checkHealth(): String = request("GET", "/health")

    fun analyzeLink(url: String): JSONObject = JSONObject(request("POST", "/v1/link-check/analyze", body = JSONObject().put("url", url)))

    fun pingTelemetry(installId: String, appVersion: String) {
        request("POST", "/v1/telemetry/ping", body = JSONObject()
            .put("install_id", installId)
            .put("app_version", appVersion))
    }

    fun accessStatus(): JSONObject = JSONObject(request("GET", "/v1/access/status"))

    fun submitAppeal(installId: String, message: String): JSONObject = JSONObject(request("POST", "/v1/appeals", body = JSONObject()
        .put("install_id", installId)
        .put("message", message.trim())))

    fun sendContactMessage(name: String, message: String) {
        request("POST", "/v1/contact/messages", body = JSONObject()
            .put("name", name.trim())
            .put("message", message.trim()))
    }

    fun authenticate(email: String, password: String, register: Boolean): String {
        val path = if (register) "/v1/auth/register" else "/v1/auth/login"
        val result = JSONObject(request("POST", path, body = JSONObject()
            .put("email", email.trim())
            .put("password", password)))
        return result.getString("access_token")
    }

    fun listSettings(token: String): JSONArray = JSONArray(request("GET", "/v1/settings", token))

    fun saveSetting(token: String, packageName: String, blocked: Boolean) {
        request("PUT", "/v1/settings", token, JSONObject()
            .put("package_name", packageName)
            .put("blocked", blocked))
    }
}
