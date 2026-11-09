package com.kb.ledger.data.remote

import android.content.Context
import android.content.ContextWrapper
import com.kb.ledger.util.AppResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [GoogleAuthManager] verifying in-memory token caching,
 * invalidation on HTTP 401 rejection, sign-out cleanup, and AuthState transitions.
 */
class GoogleAuthManagerTest {

    private lateinit var fakeTokenProvider: FakeOAuthTokenProvider
    private lateinit var authManager: GoogleAuthManager
    private val dummyContext: Context = object : ContextWrapper(null) {}

    private val testEmail = "ledger.test@gmail.com"

    class FakeOAuthTokenProvider : OAuthTokenProvider {
        var tokenToReturn: String = "mock-oauth-access-token"
        var errorToReturn: AppResult.Error? = null
        var callCount: Int = 0
        val clearedTokens = mutableListOf<String>()

        override suspend fun getAccessToken(accountEmail: String, scope: String): AppResult<String> {
            callCount++
            errorToReturn?.let { return it }
            return AppResult.Success(tokenToReturn)
        }

        override suspend fun clearToken(token: String) {
            clearedTokens.add(token)
        }
    }

    @Before
    fun setUp() {
        fakeTokenProvider = FakeOAuthTokenProvider()
        authManager = GoogleAuthManager(
            context = dummyContext,
            tokenProvider = fakeTokenProvider,
            credentialManagerProvider = { throw UnsupportedOperationException("Not needed in token tests") }
        )
    }

    @Test
    fun getDriveAccessToken_firstCall_fetchesFromTokenProviderAndCaches() = runBlocking {
        val result = authManager.getDriveAccessToken(testEmail)
        assertTrue(result is AppResult.Success)
        assertEquals("mock-oauth-access-token", (result as AppResult.Success).data)
        assertEquals(1, fakeTokenProvider.callCount)

        // Auth state updated to SignedIn
        assertTrue(authManager.isSignedIn)
        assertEquals(testEmail, authManager.currentAccountEmail)
    }

    @Test
    fun getDriveAccessToken_secondCall_returnsCachedTokenWithoutProviderCall() = runBlocking {
        val result1 = authManager.getDriveAccessToken(testEmail)
        val result2 = authManager.getDriveAccessToken(testEmail)

        assertTrue(result1 is AppResult.Success)
        assertTrue(result2 is AppResult.Success)
        assertEquals((result1 as AppResult.Success).data, (result2 as AppResult.Success).data)
        // Token provider called ONLY ONCE due to in-memory caching
        assertEquals(1, fakeTokenProvider.callCount)
    }

    @Test
    fun getDriveAccessToken_blankEmail_returnsError() = runBlocking {
        val result = authManager.getDriveAccessToken("   ")
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("EMAIL_REQUIRED"))
        assertEquals(0, fakeTokenProvider.callCount)
    }

    @Test
    fun onTokenRejected_clearsCacheAndTransitionsToTokenExpired() = runBlocking {
        authManager.getDriveAccessToken(testEmail)
        assertEquals(1, fakeTokenProvider.callCount)

        // Simulate 401 invalidation
        authManager.onTokenRejected()

        // Cleared in provider
        assertTrue(fakeTokenProvider.clearedTokens.contains("mock-oauth-access-token"))

        // State is TokenExpired
        val state = authManager.authState.value
        assertTrue(state is AuthState.TokenExpired)
        assertEquals(testEmail, (state as AuthState.TokenExpired).email)

        // Next call fetches fresh token from provider
        fakeTokenProvider.tokenToReturn = "fresh-token-456"
        val freshResult = authManager.getDriveAccessToken(testEmail)
        assertTrue(freshResult is AppResult.Success)
        assertEquals("fresh-token-456", (freshResult as AppResult.Success).data)
        assertEquals(2, fakeTokenProvider.callCount)
    }

    @Test
    fun tokenProviderError_transitionsToTokenExpiredState() = runBlocking {
        fakeTokenProvider.errorToReturn = AppResult.Error("Network error contacting token endpoint")

        val result = authManager.getDriveAccessToken(testEmail)
        assertTrue(result is AppResult.Error)
        assertFalse(authManager.isSignedIn)
        assertTrue(authManager.authState.value is AuthState.TokenExpired)
    }
}
