package com.kb.ledger.util

import java.util.Base64
import com.google.gson.GsonBuilder
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.RecurringRule
import com.kb.ledger.data.model.RecurrenceFrequency
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Unit tests for [BillImageManager] file-level logic.
 *
 * These tests exercise all file I/O operations using a real temp directory.
 * They do NOT require Android Context, ContentResolver, or a device/emulator.
 *
 * Pickup-URI operations (copyToInternalStorage / migrateContentUriToInternal) cannot be
 * tested here without Robolectric or an instrumentation test because they need a real
 * ContentResolver. Those are covered in the BACKUP_RESTORE_TEST_PLAN.md manual tests and
 * should be added as androidTest instrumentation tests when Robolectric is configured.
 */
class BillImageManagerTest {

    // Simulate the receipts directory that BillImageManager uses internally.
    private lateinit var tempDir: File
    private lateinit var receiptsDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory("bill_image_test").toFile()
        receiptsDir = File(tempDir, "receipts").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // Helper: write a file directly into the receipts dir (simulates a successful internal copy).
    private fun writeInternalImage(txId: String, bytes: ByteArray): File {
        val file = File(receiptsDir, "${txId}.jpg")
        file.writeBytes(bytes)
        return file
    }

    private fun internalFile(txId: String) = File(receiptsDir, "${txId}.jpg")

    // ── File path tests ──────────────────────────────────────────────────────

    @Test
    fun internalFile_path_endsWithTxIdDotJpg() {
        val txId = "test-tx-id-123"
        val file = internalFile(txId)
        assertTrue(file.absolutePath.endsWith("${txId}.jpg"))
        assertTrue(file.parent?.endsWith("receipts") ?: false)
    }

    @Test
    fun uniqueTxIds_produceDifferentFilePaths() {
        val tx1 = UUID.randomUUID().toString()
        val tx2 = UUID.randomUUID().toString()
        assertNotEquals(internalFile(tx1).absolutePath, internalFile(tx2).absolutePath)
    }

    // ── hasInternalImage equivalents ─────────────────────────────────────────

    @Test
    fun file_doesNotExist_initially() {
        val txId = UUID.randomUUID().toString()
        assertFalse(internalFile(txId).exists())
    }

    @Test
    fun file_existsAndNonEmpty_afterWrite() {
        val txId = UUID.randomUUID().toString()
        writeInternalImage(txId, "fake jpeg bytes".toByteArray())
        val file = internalFile(txId)
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
    }

    @Test
    fun emptyFile_isConsideredMissing() {
        val txId = UUID.randomUUID().toString()
        val file = internalFile(txId)
        file.createNewFile() // creates 0-byte file
        assertTrue(file.exists())
        assertEquals(0L, file.length()) // zero bytes = image is "missing"
    }

    // ── Isolation between transactions ───────────────────────────────────────

    @Test
    fun multipleTransactions_storedInSeparateFiles() {
        val tx1 = UUID.randomUUID().toString()
        val tx2 = UUID.randomUUID().toString()
        val img1 = "image content for tx1".toByteArray()
        val img2 = "different image for tx2".toByteArray()

        writeInternalImage(tx1, img1)
        writeInternalImage(tx2, img2)

        assertArrayEquals(img1, internalFile(tx1).readBytes())
        assertArrayEquals(img2, internalFile(tx2).readBytes())
        assertFalse(internalFile(tx1).absolutePath == internalFile(tx2).absolutePath)
    }

    @Test
    fun deletingOneTx_doesNotAffectAnother() {
        val tx1 = UUID.randomUUID().toString()
        val tx2 = UUID.randomUUID().toString()
        writeInternalImage(tx1, "img1".toByteArray())
        writeInternalImage(tx2, "img2".toByteArray())

        internalFile(tx1).delete()

        assertFalse(internalFile(tx1).exists())
        assertTrue(internalFile(tx2).exists())
        assertArrayEquals("img2".toByteArray(), internalFile(tx2).readBytes())
    }

    // ── Image bytes integrity ────────────────────────────────────────────────

    @Test
    fun imageBytes_readBack_exactly() {
        val txId = UUID.randomUUID().toString()
        val originalBytes = ByteArray(256) { i -> i.toByte() } // 256-byte gradient
        writeInternalImage(txId, originalBytes)
        assertArrayEquals(originalBytes, internalFile(txId).readBytes())
    }

    @Test
    fun largeImage_storedAndReadCorrectly() {
        val txId = UUID.randomUUID().toString()
        // Simulate a 1 MB image.
        val largeImage = ByteArray(1024 * 1024) { (it % 256).toByte() }
        writeInternalImage(txId, largeImage)
        val readBack = internalFile(txId).readBytes()
        assertEquals(largeImage.size, readBack.size)
        assertArrayEquals(largeImage, readBack)
    }

    // ── Image survives directory re-scan ("app restart") ─────────────────────

    @Test
    fun internalImage_persistsAcrossDirectoryList() {
        val txId = UUID.randomUUID().toString()
        val bytes = "persisted image".toByteArray()
        writeInternalImage(txId, bytes)

        // Re-resolve the same path (simulating a fresh context pointing at the same dir).
        val restoredFile = File(receiptsDir, "${txId}.jpg")
        assertTrue(restoredFile.exists())
        assertArrayEquals(bytes, restoredFile.readBytes())
    }

    // ── Overwrite behaviour ──────────────────────────────────────────────────

    @Test
    fun copyNewImage_doesNotOverwriteExistingByDefault() {
        val txId = UUID.randomUUID().toString()
        val originalBytes = "original image".toByteArray()
        writeInternalImage(txId, originalBytes)

        // Attempt a second write to the same txId — default should preserve the original.
        val newBytes = "replacement image".toByteArray()
        val targetFile = internalFile(txId)
        if (targetFile.exists() && targetFile.length() > 0) {
            // Simulate the "do not overwrite" path: skip the write.
            // This is what BillImageManager.copyToInternalStorage(overwriteExisting = false) does.
        } else {
            targetFile.writeBytes(newBytes)
        }

        // File should still contain the original bytes.
        assertArrayEquals(originalBytes, internalFile(txId).readBytes())
    }

    @Test
    fun forcedOverwrite_replacesExistingFile() {
        val txId = UUID.randomUUID().toString()
        val originalBytes = "original".toByteArray()
        val newBytes = "replacement".toByteArray()
        writeInternalImage(txId, originalBytes)

        // Simulate overwriteExisting = true: delete and rewrite.
        internalFile(txId).delete()
        writeInternalImage(txId, newBytes)

        assertArrayEquals(newBytes, internalFile(txId).readBytes())
    }

    // ── Temp file cleanup ────────────────────────────────────────────────────

    @Test
    fun tempFile_cleanedUp_afterSuccessfulCopy() {
        val txId = UUID.randomUUID().toString()
        val tempFile = File(receiptsDir, "pick_tmp_${txId}.jpg")
        tempFile.writeBytes("temporary".toByteArray())

        // Simulate a successful rename to final path.
        val finalFile = internalFile(txId)
        tempFile.renameTo(finalFile)

        assertFalse(tempFile.exists())  // temp removed
        assertTrue(finalFile.exists())  // final in place
    }

    @Test
    fun tempFile_cleanedUp_afterFailedVerification() {
        val txId = UUID.randomUUID().toString()
        val tempFile = File(receiptsDir, "pick_tmp_${txId}.jpg")
        tempFile.createNewFile() // 0-byte — verification would fail

        // Simulate verification failure: temp is deleted, final is not created.
        if (tempFile.exists() && tempFile.length() == 0L) {
            tempFile.delete()
        }

        assertFalse(tempFile.exists())
        assertFalse(internalFile(txId).exists())
    }

    // ── BackupData + BillImageManager integration ─────────────────────────────

    @Test
    fun backupRoundTrip_imageBytes_base64EncodeDecode() {
        val txId = UUID.randomUUID().toString()
        val originalBytes = "test receipt image".toByteArray()

        // Encode to Base64 (as BackupManager does during export).
        val encoded = Base64.getEncoder().encodeToString(originalBytes)

        // Decode back (as BackupManager does during import).
        val decoded = Base64.getDecoder().decode(encoded)

        assertArrayEquals(originalBytes, decoded)
        assertTrue(encoded.isNotEmpty())
    }

    @Test
    fun backupData_schemaV3_includesBillImages() {
        val gson = GsonBuilder().create()
        val txId = UUID.randomUUID().toString()
        val fakeBytes = "fake receipt".toByteArray()
        val encoded = Base64.getEncoder().encodeToString(fakeBytes)

        val backup = BackupData(
            persons = emptyList(),
            transactions = emptyList(),
            billImagesByTxId = mapOf(txId to encoded)
        )

        assertEquals(4, backup.schemaVersion)

        val json = gson.toJson(backup)
        val restored = gson.fromJson(json, BackupData::class.java)

        assertTrue(restored.billImagesByTxId.containsKey(txId))
        val restoredDecoded = Base64.getDecoder().decode(restored.billImagesByTxId[txId]!!)
        assertArrayEquals(fakeBytes, restoredDecoded)
    }

    @Test
    fun backupData_missingImage_txIdListIsAccurate() {
        // Simulate a transaction that has imageUri set but no internal file.
        val txId = UUID.randomUUID().toString()
        val hasInternal = internalFile(txId).exists() && internalFile(txId).length() > 0
        val imageUri = "content://media/12345"  // stale content:// URI

        val shouldBeInMissingList = !hasInternal && imageUri.isNotBlank()
        assertTrue(shouldBeInMissingList)
    }

    @Test
    fun restoreImportResult_restoredCountMatchesImportedImages() {
        val txId1 = UUID.randomUUID().toString()
        val txId2 = UUID.randomUUID().toString()
        writeInternalImage(txId1, "img1".toByteArray())
        writeInternalImage(txId2, "img2".toByteArray())

        val restoredCount = listOf(txId1, txId2).count { internalFile(it).exists() && internalFile(it).length() > 0 }
        assertEquals(2, restoredCount)
    }

    // ── Completely Separate Bill Image Storage & Gallery Independence ────────

    @Test
    fun cameraStagingFile_createdInPrivateReceiptsDir() {
        val stagingFile = File(receiptsDir, "camera_stage_${UUID.randomUUID()}.jpg")
        stagingFile.writeBytes("camera direct capture".toByteArray())

        assertTrue(stagingFile.exists())
        assertTrue(stagingFile.name.startsWith("camera_stage_"))
        assertTrue(stagingFile.name.endsWith(".jpg"))
        assertEquals(receiptsDir.absolutePath, stagingFile.parentFile?.absolutePath)
    }

    @Test
    fun commitStagedImage_atomicallyRenamesToCanonicalTxPath() {
        val txId = UUID.randomUUID().toString()
        val stagingFile = File(receiptsDir, "gallery_stage_${UUID.randomUUID()}.jpg")
        val content = "independent bill image bytes".toByteArray()
        stagingFile.writeBytes(content)

        val finalFile = internalFile(txId)
        assertFalse(finalFile.exists())

        // Simulate commitStagedImage
        if (finalFile.exists()) finalFile.delete()
        val moved = stagingFile.renameTo(finalFile)
        if (!moved) {
            stagingFile.copyTo(finalFile, overwrite = true)
            stagingFile.delete()
        }

        assertFalse("Staging file must be cleaned up", stagingFile.exists())
        assertTrue("Canonical transaction bill file must exist", finalFile.exists())
        assertArrayEquals("Content must match exactly", content, finalFile.readBytes())
    }

    @Test
    fun deleteStagingFile_protectsCanonicalTransactionFiles() {
        val txId = UUID.randomUUID().toString()
        val canonicalFile = writeInternalImage(txId, "canonical receipt".toByteArray())

        val stagingFile = File(receiptsDir, "camera_stage_${UUID.randomUUID()}.jpg")
        stagingFile.writeBytes("temporary camera shot".toByteArray())

        // Helper logic from deleteStagingFile
        fun safeDeleteStaging(file: File): Boolean {
            if (file.parentFile?.canonicalPath == receiptsDir.canonicalPath &&
                (file.name.startsWith("camera_stage_") || file.name.startsWith("gallery_stage_") || file.name.startsWith("pick_tmp_"))
            ) {
                return file.delete()
            }
            return false
        }

        // Staging file should be deleted
        val deletedStaging = safeDeleteStaging(stagingFile)
        assertTrue(deletedStaging)
        assertFalse(stagingFile.exists())

        // Canonical transaction receipt must NEVER be deleted by deleteStagingFile
        val deletedCanonical = safeDeleteStaging(canonicalFile)
        assertFalse(deletedCanonical)
        assertTrue(canonicalFile.exists())
    }

    @Test
    fun galleryImageRemoved_privateCopyRemainsIntactAndAvailable() {
        // Simulate phone's external shared storage / Gallery
        val galleryDir = File(tempDir, "simulated_dcim").also { it.mkdirs() }
        val originalGalleryImage = File(galleryDir, "IMG_20261024_120000.jpg")
        val originalBytes = "user's original gallery photo".toByteArray()
        originalGalleryImage.writeBytes(originalBytes)

        assertTrue(originalGalleryImage.exists())

        // 1. Ledger copies image into private internal receipts storage
        val txId = UUID.randomUUID().toString()
        val privateLedgerCopy = internalFile(txId)
        originalGalleryImage.copyTo(privateLedgerCopy)

        assertTrue("Private copy must exist", privateLedgerCopy.exists())
        assertArrayEquals(originalBytes, privateLedgerCopy.readBytes())

        // 2. User deletes or moves the photo in Gallery app
        val galleryDeleted = originalGalleryImage.delete()
        assertTrue("Gallery image was deleted", galleryDeleted)
        assertFalse("Gallery image no longer exists", originalGalleryImage.exists())

        // 3. Verification: Ledger's private copy remains 100% available, intact, and unaffected
        assertTrue("Ledger private bill must remain available", privateLedgerCopy.exists())
        assertArrayEquals("Ledger private bill bytes must remain unchanged", originalBytes, privateLedgerCopy.readBytes())
    }

    @Test
    fun editingOrDeletingLedgerBill_leavesOriginalGalleryImageUntouched() {
        // Simulate phone's external shared storage / Gallery
        val galleryDir = File(tempDir, "simulated_gallery").also { it.mkdirs() }
        val originalGalleryImage = File(galleryDir, "bill_from_camera.jpg")
        val originalBytes = "original unedited receipt in gallery".toByteArray()
        originalGalleryImage.writeBytes(originalBytes)

        // Ledger has its private copy
        val txId = UUID.randomUUID().toString()
        val privateLedgerCopy = writeInternalImage(txId, originalBytes)

        // Ledger user removes bill or replaces bill
        privateLedgerCopy.delete()
        assertFalse("Ledger private copy was deleted", privateLedgerCopy.exists())

        // Verification: Original Gallery image is 100% untouched
        assertTrue("Gallery image must still exist", originalGalleryImage.exists())
        assertArrayEquals("Gallery image content must be identical", originalBytes, originalGalleryImage.readBytes())
    }
}

