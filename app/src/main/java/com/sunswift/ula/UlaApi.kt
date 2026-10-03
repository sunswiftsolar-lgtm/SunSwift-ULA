package com.sunswift.ula

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

private const val ULA_WEB_APP_URL =
    "https://script.google.com/macros/s/AKfycbzEKG3_WJM2oFr2XjE9PXpsOx5HD6YRgSKVN8MLGP4EsxFmB4hXfuv_wp-zsRf3UBRM/exec"

class UlaApiException(message: String) : Exception(message)

class UlaApi {
    suspend fun webCall(sessionToken: String, method: String, args: JSONArray): JSONObject =
        post("webCall", JSONObject().put("sessionToken", sessionToken).put("method", method).put("args", args))

    suspend fun checkLoginMobile(mobile: String): UlaLoginAccess {
        val result = post(
            "checkLoginMobile",
            JSONObject().put("mobile", mobile)
        )
        val quota = result.optJSONObject("otpQuota") ?: JSONObject()
        return UlaLoginAccess(
            configured = result.optBoolean("configured", false),
            authorized = result.optBoolean("authorized", false),
            quota = UlaOtpQuota(
                remaining = quota.optInt("remaining", 0),
                limit = quota.optInt("limit", 10),
                projectRemaining = quota.optInt("projectRemaining", quota.optInt("remaining", 0)),
                projectLimit = quota.optInt("projectLimit", 100),
                cooldownSeconds = quota.optInt("cooldownSeconds", 60)
            )
        )
    }

    suspend fun startSession(firebaseIdToken: String): UlaSession {
        val result = post(
            "startSession",
            JSONObject().put("firebaseIdToken", firebaseIdToken)
        )
        return sessionFromJson(result, result.optString("sessionToken"))
    }

    suspend fun resumeSession(sessionToken: String): UlaSession {
        val result = post(
            "resumeSession",
            JSONObject().put("sessionToken", sessionToken)
        )
        return sessionFromJson(result, sessionToken)
    }

    suspend fun endSession(sessionToken: String) {
        if (sessionToken.isNotBlank()) {
            post("endSession", JSONObject().put("sessionToken", sessionToken))
        }
    }

    suspend fun listRecords(sessionToken: String): UlaRecordsPayload {
        val result = post(
            "listRecords",
            JSONObject().put("sessionToken", sessionToken)
        )
        return UlaRecordsPayload(
            records = result.optJSONArray("records").asObjects().map(UlaRecord::fromJson),
            installers = result.optJSONArray("installers").asStrings()
        )
    }

    suspend fun listActivity(sessionToken: String, limit: Int = 100): List<UlaActivity> {
        val result = post(
            "listActivity",
            JSONObject()
                .put("sessionToken", sessionToken)
                .put("limit", limit)
        )
        return result.optJSONArray("activity").asObjects().map(UlaActivity::fromJson)
    }

    suspend fun saveRecord(sessionToken: String, record: JSONObject): UlaRecord {
        val result = post(
            "saveRecord",
            JSONObject()
                .put("sessionToken", sessionToken)
                .put("record", record)
        )
        return UlaRecord.fromJson(result)
    }

    suspend fun listUsers(sessionToken: String): UlaAdminData {
        val result = post("listUsers", JSONObject().put("sessionToken", sessionToken))
        return UlaAdminData(
            users = result.optJSONArray("users").asObjects().map(UlaAuthorizedUser::fromJson),
            installers = result.optJSONArray("installers").asStrings()
        )
    }

    suspend fun upsertUser(
        sessionToken: String,
        name: String,
        mobile: String,
        role: String
    ) {
        post(
            "upsertUser",
            JSONObject()
                .put("sessionToken", sessionToken)
                .put(
                    "user",
                    JSONObject()
                        .put("name", name)
                        .put("mobile", mobile)
                        .put("role", role)
                        .put("active", true)
                )
        )
    }

    suspend fun setUserActive(sessionToken: String, mobile: String, active: Boolean) {
        post(
            "setUserActive",
            JSONObject()
                .put("sessionToken", sessionToken)
                .put("mobile", mobile)
                .put("active", active)
        )
    }

    suspend fun setUserRole(sessionToken: String, mobile: String, role: String) {
        post(
            "setUserRole",
            JSONObject()
                .put("sessionToken", sessionToken)
                .put("mobile", mobile)
                .put("role", role)
        )
    }

    suspend fun removeUser(sessionToken: String, mobile: String) {
        post(
            "removeUser",
            JSONObject().put("sessionToken", sessionToken).put("mobile", mobile)
        )
    }

    suspend fun upsertInstaller(sessionToken: String, name: String) {
        post(
            "upsertInstaller",
            JSONObject().put("sessionToken", sessionToken).put("name", name)
        )
    }

    suspend fun removeInstaller(sessionToken: String, name: String) {
        post(
            "removeInstaller",
            JSONObject().put("sessionToken", sessionToken).put("name", name)
        )
    }

    private fun sessionFromJson(json: JSONObject, fallbackToken: String): UlaSession {
        val token = json.optString("sessionToken", fallbackToken)
        if (token.isBlank()) throw UlaApiException("ULA session token was not returned.")
        val user = json.optJSONObject("user")
            ?: throw UlaApiException("ULA user role was not returned.")
        return UlaSession(
            token = token,
            expiresAt = json.optLong("expiresAt", 0L),
            user = UlaUser.fromJson(user)
        )
    }

    private suspend fun post(action: String, payload: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val request = JSONObject()
                .put("action", action)
                .put("payload", payload)
                .toString()
            val connection = (URL(ULA_WEB_APP_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "SunSwift-ULA-Android/1.5-web-v45.7")
            }

            try {
                connection.outputStream.use { output ->
                    output.write(request.toByteArray(Charsets.UTF_8))
                }
                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                } ?: throw UlaApiException("ULA cloud returned no response.")
                val body = stream.bufferedReader().use(BufferedReader::readText)
                val envelope = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    throw UlaApiException("ULA cloud returned an invalid response.")
                }
                if (!envelope.optBoolean("ok", false)) {
                    throw UlaApiException(envelope.optString("error", "ULA cloud request failed."))
                }
                envelope.optJSONObject("result") ?: JSONObject()
            } finally {
                connection.disconnect()
            }
        }
}

private fun JSONArray?.asObjects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(::add)
        }
    }
}

private fun JSONArray?.asStrings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }
}
