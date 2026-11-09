package com.kb.ledger.data.remote

import android.accounts.Account
import android.content.Context
import android.content.Intent
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.kb.ledger.util.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Interface providing OAuth 2.0 access tokens for Google APIs.
 * Decoupled from Google Play Services for JVM testability.
 */
interface OAuthTokenProvider {
    suspend fun getAccessToken(accountEmail: String, scope: String): AppResult<String>
    suspend fun clearToken(token: String)
}

/**
 * Standard implementation using Google Play Services [GoogleAuthUtil].
 */
class PlayServicesOAuthTokenProvider(private val context: Context) : OAuthTokenProvider {
    override suspend fun getAccessToken(accountEmail: String, scope: String): AppResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val account = Account(accountEmail, "com.google")
                val token = GoogleAuthUtil.getToken(context, account, "oauth2:$scope")
                AppResult.Success(token)
            } catch (e: UserRecoverableAuthException) {
                AppResult.Error("USER_RECOVERABLE_AUTH: ${e.message}", e)
            } catch (e: Exception) {
                AppResult.Error("OAUTH_TOKEN_FAILED: ${e.message}", e)
            }
        }

    override suspend fun clearToken(token: String): Unit =
        withContext(Dispatchers.IO) {
            try {
                GoogleAuthUtil.clearToken(context, token)
            } catch (_: Exception) {}
        }
}

/**
 * Manages Google Sign-In and Google Drive OAuth 2.0 authorization.
 *
 * Architecture:
 *  1. Authentication (Sign-In): Uses AndroidX [CredentialManager] to select an account
 *     and retrieve the user's verified Google email address.
 *  2. Authorization (Drive Access): Uses [OAuthTokenProvider] to obtain an OAuth 2.0
 *     access token scoped strictly to [DRIVE_APPDATA_SCOPE].
 *  3. In-memory caching: Access tokens are held in RAM with a 50-minute TTL and are
 *     never written to disk or SharedPreferences.
 *  4. Invalidation: If Drive API returns HTTP 401, [onTokenRejected] purges the token
 *     cache and marks the session as [AuthState.TokenExpired].
 */
class GoogleAuthManager(
    private val context: Context,
    private val tokenProvider: OAuthTokenProvider = PlayServicesOAuthTokenProvider(context),
    private val credentialManagerProvider: (Context) -> CredentialManager = { CredentialManager.create(it) }
) {

    companion object {
        /**
         * Web Client ID from Google Cloud Console.
         * Required for Credential Manager ID token acquisition.
         */
        const val WEB_CLIENT_ID: String = "REPLACE_WITH_YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"

        /** Scope required for Drive AppDataFolder private storage. */
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

        // Cache tokens for 50 minutes (Google access tokens have a 60-minute lifetime)
        const val TOKEN_TTL_MS = 50L * 60 * 1000

        /**
         * Returns true if a valid, non-placeholder Google Web Client ID has been configured.
         */
        val isConfigured: Boolean
            get() = !WEB_CLIENT_ID.startsWith("REPLACE_WITH") && WEB_CLIENT_ID.isNotBlank()
    }

    /** Returns true if Google authentication is configured and active. */
    val isConfigured: Boolean get() = Companion.isConfigured

    private val credentialManager by lazy { credentialManagerProvider(context) }

    // ── Auth state ────────────────────────────────────────────────────────────

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val isSignedIn: Boolean get() = _authState.value is AuthState.SignedIn

    private val prefs by lazy {
        try {
            context.getSharedPreferences("google_auth_prefs", Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
    }

    init {
        try {
            val savedEmail = prefs?.getString("saved_account_email", null)
            if (!savedEmail.isNullOrBlank()) {
                _authState.value = AuthState.SignedIn(email = savedEmail, accessToken = null)
            }
        } catch (_: Exception) {}
    }

    fun setActiveAccount(email: String?) {
        try {
            prefs?.edit()?.apply {
                if (email.isNullOrBlank()) {
                    remove("saved_account_email")
                } else {
                    putString("saved_account_email", email)
                }
                apply()
            }
        } catch (_: Exception) {}

        if (email.isNullOrBlank()) {
            _authState.value = AuthState.SignedOut
        } else {
            _authState.value = AuthState.SignedIn(email = email, accessToken = cachedToken?.accessToken)
        }
    }

    val currentAccountEmail: String?
        get() {
            val stateEmail = when (val state = _authState.value) {
                is AuthState.SignedIn -> state.email
                is AuthState.TokenExpired -> state.email
                is AuthState.NeedsConsent -> state.email
                else -> null
            }
            if (!stateEmail.isNullOrBlank()) return stateEmail
            return try {
                prefs?.getString("saved_account_email", null)
            } catch (_: Exception) {
                null
            }
        }

    private var cachedToken: CachedToken? = null

    // ── Sign-in ───────────────────────────────────────────────────────────────

    /**
     * Signs in using Credential Manager.
     * Tries silent sign-in first, then interactive sign-in.
     */
    suspend fun signIn(activityContext: Context): AppResult<String> {
        val silentResult = attemptSignIn(activityContext, filterByAuthorizedAccounts = true)
        if (silentResult.isSuccess) return silentResult

        return attemptSignIn(activityContext, filterByAuthorizedAccounts = false)
    }

    private suspend fun attemptSignIn(
        activityContext: Context,
        filterByAuthorizedAccounts: Boolean
    ): AppResult<String> {
        if (!isConfigured) {
            return AppResult.Error(
                AuthError.CONFIGURATION_MISSING.code +
                    ": Google Web Client ID is not configured. Google Sign-In is unavailable in this release."
            )
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setServerClientId(WEB_CLIENT_ID)
            .setAutoSelectEnabled(filterByAuthorizedAccounts)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val response = credentialManager.getCredential(activityContext, request)
            val credential = response.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val email = googleIdTokenCredential.id

                setActiveAccount(email)
                AppResult.Success(email)
            } else {
                AppResult.Error("${AuthError.UNEXPECTED_CREDENTIAL_TYPE.code}: ${credential.type}")
            }
        } catch (e: NoCredentialException) {
            AppResult.Error("${AuthError.NO_CREDENTIAL.code}: ${e.message}", e)
        } catch (e: GetCredentialCancellationException) {
            AppResult.Error("${AuthError.CANCELLED.code}: ${e.message}", e)
        } catch (e: GetCredentialException) {
            AppResult.Error("${AuthError.SIGN_IN_FAILED.code}: ${e.message}", e)
        } catch (e: Exception) {
            AppResult.Error("${AuthError.SIGN_IN_FAILED.code}: ${e.message}", e)
        }
    }

    // ── OAuth Access Token ─────────────────────────────────────────────────────

    /**
     * Obtains a valid OAuth 2.0 access token with [DRIVE_APPDATA_SCOPE].
     * Returns from in-memory cache if still valid, otherwise fetches from [tokenProvider].
     */
    suspend fun getDriveAccessToken(email: String): AppResult<String> {
        if (email.isBlank()) {
            return AppResult.Error("EMAIL_REQUIRED: Cannot request token without an account email")
        }

        // Check in-memory cache
        val cached = cachedToken
        if (cached != null && cached.email == email && !cached.isExpired()) {
            return AppResult.Success(cached.accessToken)
        }

        // Fetch fresh access token via token provider
        val tokenResult = tokenProvider.getAccessToken(email, DRIVE_APPDATA_SCOPE)
        return when (tokenResult) {
            is AppResult.Success -> {
                val token = tokenResult.data
                cachedToken = CachedToken(
                    email = email,
                    accessToken = token,
                    expiresAtMillis = System.currentTimeMillis() + TOKEN_TTL_MS
                )
                _authState.value = AuthState.SignedIn(email = email, accessToken = token)
                AppResult.Success(token)
            }
            is AppResult.Error -> {
                val cause = tokenResult.cause
                if (cause is UserRecoverableAuthException) {
                    val recoveryIntent = cause.intent
                    _authState.value = AuthState.NeedsConsent(email = email, intent = recoveryIntent)
                } else {
                    _authState.value = AuthState.TokenExpired(email = email)
                }
                tokenResult
            }
        }
    }

    /**
     * Called when the Drive API returns HTTP 401 Unauthorized, confirming the token is invalid.
     */
    suspend fun onTokenRejected() {
        val oldToken = cachedToken?.accessToken
        val email = cachedToken?.email ?: currentAccountEmail

        cachedToken = null

        if (oldToken != null) {
            tokenProvider.clearToken(oldToken)
        }

        if (email != null) {
            _authState.value = AuthState.TokenExpired(email = email)
        } else {
            _authState.value = AuthState.SignedOut
        }
    }

    // ── Sign-out ──────────────────────────────────────────────────────────────

    /**
     * Signs out, clears credentials and in-memory token cache.
     * Does NOT delete local Room data.
     */
    suspend fun signOut(): AppResult<Unit> {
        val oldToken = cachedToken?.accessToken
        cachedToken = null

        if (oldToken != null) {
            tokenProvider.clearToken(oldToken)
        }

        setActiveAccount(null)

        if (!isConfigured) {
            return AppResult.Success(Unit)
        }

        return try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Error("Sign-out partial error (local state cleared): ${e.message}", e)
        }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private data class CachedToken(
        val email: String,
        val accessToken: String,
        val expiresAtMillis: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAtMillis
    }
}

/**
 * Authentication states for Google Identity & Drive authorization.
 */
sealed class AuthState {
    object Unknown : AuthState()
    object SignedOut : AuthState()
    data class SignedIn(val email: String, val accessToken: String?) : AuthState()
    data class TokenExpired(val email: String) : AuthState()
    data class NeedsConsent(val email: String, val intent: Intent?) : AuthState()
}

enum class AuthError(val code: String) {
    CONFIGURATION_MISSING("AUTH_CONFIG_MISSING"),
    NO_CREDENTIAL("AUTH_NO_CREDENTIAL"),
    CANCELLED("AUTH_CANCELLED"),
    SIGN_IN_FAILED("AUTH_SIGN_IN_FAILED"),
    UNEXPECTED_CREDENTIAL_TYPE("AUTH_BAD_CREDENTIAL_TYPE"),
    TOKEN_EXPIRED("AUTH_TOKEN_EXPIRED")
}
