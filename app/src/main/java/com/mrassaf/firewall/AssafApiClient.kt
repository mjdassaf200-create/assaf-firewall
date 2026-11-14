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

    fun analyzeLink(url: String, token: String? = null): JSONObject =
        JSONObject(request("POST", "/v1/link-check/analyze", token, JSONObject().put("url", url)))

    fun getMe(token: String): JSONObject = JSONObject(request("GET", "/v1/me", token))

    fun submitContact(message: String, token: String? = null, guestToken: String? = null): JSONObject {
        val body = JSONObject().put("message", message)
        if (!guestToken.isNullOrBlank()) body.put("guest_token", guestToken)
        return JSONObject(request("POST", "/v1/support/messages", token, body))
    }

    fun submitAppeal(token: String, message: String): JSONObject =
        JSONObject(request("POST", "/v1/appeals", token, JSONObject().put("message", message)))

    fun listSupportMessages(token: String? = null, guestToken: String? = null): JSONArray {
        val path = if (!guestToken.isNullOrBlank()) "/v1/support/messages?guest_token=$guestToken" else "/v1/support/messages"
        return JSONArray(request("GET", path, token))
    }

    fun replyToSupport(token: String?, messageId: String, message: String, guestToken: String? = null): JSONObject {
        val body = JSONObject().put("message", message)
        if (!guestToken.isNullOrBlank()) body.put("guest_token", guestToken)
        return JSONObject(request("POST", "/v1/support/$messageId/replies", token, body))
    }

    fun pingTelemetry(installId: String, appVersion: String) {
        request("POST", "/v1/telemetry/ping", body = JSONObject()
            .put("install_id", installId)
            .put("app_version", appVersion))
    }

    private fun signedDeviceProof(email: String, deviceId: String, purpose: String): JSONObject {
        val challengeResponse = JSONObject(request(
            "POST",
            "/v1/auth/challenge",
            body = JSONObject()
                .put("email", email.trim())
                .put("device_id", deviceId)
                .put("purpose", purpose)
        ))
        val challenge = challengeResponse.getString("challenge")
        return JSONObject()
            .put("device_public_key", DeviceIdentity.publicKeyBase64())
            .put("challenge", challenge)
            .put("signature", DeviceIdentity.signChallenge(challenge))
    }

    fun authenticate(email: String, password: String, register: Boolean, fullName: String = "", whatsappNumber: String = "", deviceId: String): String {
        val path = if (register) "/v1/auth/register" else "/v1/auth/login"
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .put("device_id", deviceId)
        val proof = signedDeviceProof(email, deviceId, "user")
        val proofKeys = proof.keys()
        while (proofKeys.hasNext()) {
            val key = proofKeys.next()
            body.put(key, proof.get(key))
        }
        if (register) {
            body.put("full_name", fullName.trim())
            body.put("whatsapp_number", whatsappNumber.trim())
        }
        val result = JSONObject(request("POST", path, body = body))
        return result.getString("access_token")
    }

    fun listSettings(token: String): JSONArray = JSONArray(request("GET", "/v1/settings", token))

    fun saveSetting(token: String, packageName: String, blocked: Boolean) {
        request("PUT", "/v1/settings", token, JSONObject()
            .put("package_name", packageName)
            .put("blocked", blocked))
    }

    fun getWallet(token: String): JSONObject =
        JSONObject(request("GET", "/v1/wallet", token))

    fun getWalletTransactions(token: String, limit: Int = 50): JSONArray =
        JSONArray(request("GET", "/v1/wallet/transactions?limit=" + limit.coerceIn(1, 100), token))

    fun requestPayout(
        token: String,
        amountCents: Long,
        method: String,
        destination: String,
        idempotencyKey: String
    ): JSONObject = JSONObject(request(
        "POST",
        "/v1/wallet/payouts",
        token,
        JSONObject()
            .put("amount_cents", amountCents)
            .put("method", method)
            .put("destination", destination)
            .put("idempotency_key", idempotencyKey)
    ))

    fun authenticateOwner(email: String, password: String, deviceId: String): JSONObject {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .put("device_id", deviceId)
        val proof = signedDeviceProof(email, deviceId, "owner")
        val proofKeys = proof.keys()
        while (proofKeys.hasNext()) {
            val key = proofKeys.next()
            body.put(key, proof.get(key))
        }
        return JSONObject(request("POST", "/v1/admin/login", body = body))
    }

    fun getAdminWallets(token: String): JSONArray =
        JSONArray(request("GET", "/v1/admin/wallets", token))

    fun getAdminPayouts(token: String): JSONArray =
        JSONArray(request("GET", "/v1/admin/payouts?payout_status=pending", token))

    fun getAdminUsers(token: String): JSONArray =
        JSONArray(request("GET", "/v1/admin/users", token))

    fun getAdminUserDetail(token: String, userId: String): JSONObject =
        JSONObject(request("GET", "/v1/admin/users/" + java.net.URLEncoder.encode(userId, "UTF-8"), token))

    fun setAdminUserBan(token: String, userId: String, banned: Boolean, reason: String = ""): JSONObject =
        JSONObject(request("PATCH", "/v1/admin/users/" + java.net.URLEncoder.encode(userId, "UTF-8") + "/ban", token,
            JSONObject().put("banned", banned).put("reason", reason)))

    fun addAdminEarning(token: String, userId: String, amountCents: Long, description: String, source: String, externalRef: String): JSONObject =
        JSONObject(request("POST", "/v1/admin/wallets/$userId/earnings", token,
            JSONObject().put("amount_cents", amountCents).put("description", description)
                .put("source", source).put("external_ref", externalRef)))

    fun decideAdminPayout(token: String, payoutId: String, status: String): JSONObject =
        JSONObject(request("PATCH", "/v1/admin/payouts/$payoutId", token,
            JSONObject().put("status", status)))

}
