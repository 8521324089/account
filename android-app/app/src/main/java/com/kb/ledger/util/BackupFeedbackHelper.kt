package com.kb.ledger.util

/**
 * Maps backup export and import results to safe, user-friendly, non-PII UI messages.
 *
 * Guarantees:
 * - Zero personal customer data (no customer names, no phone numbers).
 * - Zero financial transaction values or note contents.
 * - Zero internal file paths or directory paths.
 * - Zero raw exception stack traces or system tokens.
 * - Silent handling of user picker cancellation (no error shown).
 */
object BackupFeedbackHelper {

    sealed class ImportUiMessage {
        data class Success(val message: String) : ImportUiMessage()
        data class Warning(val message: String) : ImportUiMessage()
        data class Error(val message: String) : ImportUiMessage()
        object None : ImportUiMessage()
    }

    sealed class ExportUiMessage {
        data class Success(val message: String) : ExportUiMessage()
        data class Warning(val message: String) : ExportUiMessage()
        data class Error(val message: String) : ExportUiMessage()
        object None : ExportUiMessage()
    }

    /**
     * Resolves an [ImportResult] into a user-facing [ImportUiMessage].
     *
     * @param result The result from [BackupManager.importData], or null if cancelled / uninvoked.
     * @param isCancelled True if the document picker was dismissed without selecting a file.
     */
    fun resolveImportMessage(result: ImportResult?, isCancelled: Boolean = false): ImportUiMessage {
        if (isCancelled || result == null) {
            return ImportUiMessage.None
        }

        if (!result.success) {
            val err = result.error.orEmpty()
            val userMsg = when {
                err.contains("corrupt", ignoreCase = true) || err.contains("unreadable", ignoreCase = true) ->
                    "Backup file is corrupt or unreadable. Please choose a valid backup file."
                err.contains("missing", ignoreCase = true) ->
                    "Backup file is incomplete or missing required records."
                err.contains("integrity", ignoreCase = true) || err.contains("unknown person", ignoreCase = true) ->
                    "Backup data integrity check failed. No records were modified."
                else ->
                    "Backup restoration failed. No changes were made to your data."
            }
            return ImportUiMessage.Error(userMsg)
        }

        if (result.missingImageTxIds.isNotEmpty()) {
            val count = result.missingImageTxIds.size
            val imageText = if (count == 1) "1 bill attachment could not be restored." else "$count bill attachments could not be restored."
            return ImportUiMessage.Warning("Backup restored successfully, but $imageText")
        }

        return ImportUiMessage.Success("Backup restored successfully.")
    }

    /**
     * Resolves an [ExportResult] into a user-facing [ExportUiMessage].
     *
     * @param result The result from [BackupManager.exportData], or null if cancelled / uninvoked.
     * @param isCancelled True if the document picker was dismissed without selecting a destination.
     */
    fun resolveExportMessage(result: ExportResult?, isCancelled: Boolean = false): ExportUiMessage {
        if (isCancelled || result == null) {
            return ExportUiMessage.None
        }

        if (!result.success) {
            return ExportUiMessage.Error("Backup export could not be completed.")
        }

        if (result.missingImageTxIds.isNotEmpty()) {
            val count = result.missingImageTxIds.size
            val imageText = if (count == 1) "1 bill attachment was missing from storage." else "$count bill attachments were missing from storage."
            return ExportUiMessage.Warning("Backup exported successfully, but $imageText")
        }

        return ExportUiMessage.Success("Backup exported successfully.")
    }
}
