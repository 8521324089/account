package com.kb.ledger.domain.repository

import com.kb.ledger.util.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Sync state observable and trigger contract.
 */
interface SyncRepository {

    /** Observe the current sync state for UI status indicators. */
    fun observeSyncState(): Flow<SyncState>

    /**
     * Trigger an immediate foreground sync cycle.
     * Typically called after a user write operation.
     */
    suspend fun triggerSync(): AppResult<Unit>

    /** Returns true if there are any locally pending operations not yet pushed to Drive. */
    suspend fun hasPendingOperations(): Boolean
}

sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()
    data class Success(val timestamp: Long) : SyncState()
    data class Error(val message: String) : SyncState()
    object Offline : SyncState()
    object NotAuthenticated : SyncState()
}
