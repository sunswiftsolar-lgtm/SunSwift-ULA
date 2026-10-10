package com.sunswift.ula

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class UlaViewModel(application: Application) : AndroidViewModel(application) {
    private val auth = FirebaseAuth.getInstance()
    private val api = UlaApi()
    private val preferences = application.getSharedPreferences("ula_native_session", 0)
    private var expiryJob: Job? = null

    var state by mutableStateOf(UlaAppState())
        private set

    init {
        restoreSession()
    }

    suspend fun checkLoginMobile(mobile: String): UlaLoginAccess = try {
        api.checkLoginMobile("+91${mobile.filter(Char::isDigit).takeLast(10)}")
    } catch (error: Exception) {
        throw UlaApiException(friendlyError(error))
    }

    private fun restoreSession() {
        if (auth.currentUser == null) {
            state = state.copy(checkingSession = false)
            return
        }
        viewModelScope.launch {
            state = state.copy(checkingSession = true, error = null)
            val storedToken = preferences.getString("session_token", "").orEmpty()
            val storedExpiry = preferences.getLong("session_expires_at", 0L)
            if (storedToken.isBlank() || storedExpiry <= System.currentTimeMillis()) {
                requireFreshOtp("Your session has expired. Request a new OTP to continue.")
                return@launch
            }
            try {
                acceptSession(api.resumeSession(storedToken))
            } catch (error: Exception) {
                requireFreshOtp("Your saved session is no longer valid. Request a new OTP to continue.")
            }
        }
    }

    fun authorizeSignedInUser() {
        val firebaseUser = auth.currentUser ?: run {
            state = state.copy(checkingSession = false, error = "Firebase sign-in was not completed.")
            return
        }
        viewModelScope.launch {
            state = state.copy(checkingSession = true, error = null)
            try {
                acceptSession(api.startSession(firebaseUser.awaitIdToken()))
            } catch (error: Exception) {
                rejectSession(error)
            }
        }
    }

    private suspend fun acceptSession(session: UlaSession) {
        preferences.edit()
            .putString("session_token", session.token)
            .putLong("session_expires_at", session.expiresAt)
            .apply()
        state = state.copy(
            checkingSession = false,
            user = session.user,
            sessionToken = session.token,
            sessionExpiresAt = session.expiresAt,
            error = null
        )
        scheduleSessionExpiry(session.expiresAt)
        refreshRecords()
    }

    private fun rejectSession(error: Exception) {
        expiryJob?.cancel()
        preferences.edit().clear().apply()
        auth.signOut()
        state = UlaAppState(
            checkingSession = false,
            error = friendlyError(error)
        )
    }

    private fun scheduleSessionExpiry(expiresAt: Long) {
        expiryJob?.cancel()
        expiryJob = viewModelScope.launch {
            val remaining = expiresAt - System.currentTimeMillis()
            if (remaining > 0L) delay(remaining)
            requireFreshOtp("Your session has expired. Request a new OTP to continue.")
        }
    }

    private fun requireFreshOtp(message: String) {
        expiryJob?.cancel()
        preferences.edit().clear().apply()
        auth.signOut()
        state = UlaAppState(checkingSession = false, error = message)
    }

    fun refreshRecords() {
        val token = state.sessionToken
        if (token.isBlank()) return
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            try {
                val payload = api.listRecords(token)
                val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                state = state.copy(
                    loading = false,
                    records = payload.records,
                    installers = payload.installers,
                    lastSync = time
                )
            } catch (error: Exception) {
                state = state.copy(loading = false, error = friendlyError(error))
            }
        }
    }

    fun refreshActivity() {
        val token = state.sessionToken
        if (token.isBlank() || state.user?.canAdmin != true) return
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            try {
                state = state.copy(
                    loading = false,
                    activity = api.listActivity(token)
                )
            } catch (error: Exception) {
                state = state.copy(loading = false, error = friendlyError(error))
            }
        }
    }

    fun refreshAdminData() {
        val token = state.sessionToken
        if (token.isBlank() || state.user?.canAdmin != true) return
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            try {
                val data = api.listUsers(token)
                state = state.copy(
                    loading = false,
                    authorizedUsers = data.users,
                    installers = data.installers
                )
            } catch (error: Exception) {
                state = state.copy(loading = false, error = friendlyError(error))
            }
        }
    }

    fun saveAuthorizedUser(name: String, mobile: String, role: String) = runAdminChange {
        api.upsertUser(state.sessionToken, name.trim(), mobile.trim(), role)
    }

    fun setAuthorizedUserActive(mobile: String, active: Boolean) = runAdminChange {
        api.setUserActive(state.sessionToken, mobile, active)
    }

    fun setAuthorizedUserRole(mobile: String, role: String) = runAdminChange {
        api.setUserRole(state.sessionToken, mobile, role)
    }

    fun removeAuthorizedUser(mobile: String) = runAdminChange {
        api.removeUser(state.sessionToken, mobile)
    }

    fun addInstaller(name: String) = runAdminChange {
        api.upsertInstaller(state.sessionToken, name.trim())
    }

    fun removeInstaller(name: String) = runAdminChange {
        api.removeInstaller(state.sessionToken, name)
    }

    private fun runAdminChange(change: suspend () -> Unit) {
        if (state.sessionToken.isBlank() || state.user?.canAdmin != true) return
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            try {
                change()
                val data = api.listUsers(state.sessionToken)
                state = state.copy(
                    loading = false,
                    authorizedUsers = data.users,
                    installers = data.installers
                )
            } catch (error: Exception) {
                state = state.copy(loading = false, error = friendlyError(error))
            }
        }
    }

    fun openPage(page: AppPage) {
        state = when (page) {
            AppPage.RecordForm -> state.copy(page = page, selectedRecord = null, error = null)
            AppPage.Activity -> {
                if (state.user?.canAdmin == true) {
                    refreshActivity()
                    state.copy(page = page, error = null)
                } else state
            }
            AppPage.Access -> {
                if (state.user?.canAdmin == true) {
                    refreshAdminData()
                    state.copy(page = page, error = null)
                } else state
            }
            else -> state.copy(page = page, error = null)
        }
    }

    fun openRecord(record: UlaRecord) {
        state = state.copy(page = AppPage.RecordForm, selectedRecord = record, error = null)
    }

    fun saveRecord(values: Map<String, String>) {
        val token = state.sessionToken
        if (token.isBlank() || state.user?.canEdit != true) return
        val existing = state.selectedRecord
        val payload = JSONObject().apply {
            values.forEach { (key, value) -> put(key, value.trim()) }
            if (existing != null) {
                put("id", existing.id)
                put("version", existing.version)
                put("createdAt", existing.values["createdAt"].orEmpty())
            }
        }
        viewModelScope.launch {
            state = state.copy(saving = true, error = null)
            try {
                val saved = api.saveRecord(token, payload)
                val updated = state.records.toMutableList()
                val index = updated.indexOfFirst { it.id == saved.id }
                if (index >= 0) updated[index] = saved else updated.add(0, saved)
                state = state.copy(
                    saving = false,
                    records = updated,
                    selectedRecord = null,
                    page = AppPage.Records
                )
                if (state.user?.canAdmin == true) refreshActivity()
            } catch (error: Exception) {
                state = state.copy(saving = false, error = friendlyError(error))
            }
        }
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun logout() {
        val token = state.sessionToken
        expiryJob?.cancel()
        state = UlaAppState(checkingSession = false)
        preferences.edit().clear().apply()
        auth.signOut()
        viewModelScope.launch {
            runCatching { api.endSession(token) }
        }
    }

    private fun friendlyError(error: Exception): String {
        val message = error.localizedMessage.orEmpty()
        return message
            .removePrefix("VALIDATION: ")
            .removePrefix("CONFLICT: ")
            .ifBlank { "Unable to connect to the ULA cloud." }
    }
}

private suspend fun FirebaseUser.awaitIdToken(): String = suspendCancellableCoroutine { continuation ->
    getIdToken(false).addOnCompleteListener { task ->
        if (task.isSuccessful) {
            val token = task.result?.token
            if (token.isNullOrBlank()) {
                continuation.resumeWithException(UlaApiException("Firebase token was not returned."))
            } else {
                continuation.resume(token)
            }
        } else {
            continuation.resumeWithException(
                task.exception ?: UlaApiException("Firebase token could not be refreshed.")
            )
        }
    }
}
