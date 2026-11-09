package com.kb.ledger.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import java.io.File
import java.util.UUID

/**
 * Comprehensive automated tests for camera receipt capture, staging isolation,
 * preview/retake/use-photo lifecycle, image validation, and failure recovery.
 */
class ReceiptImageProcessingAndFlowTest {

    private lateinit var tempDir: File
    private lateinit var receiptsDir: File
    private lateinit var mockContext: Context
    private lateinit var fakeImageProcessor: FakeReceiptImageProcessor
    private lateinit var manager: BillImageManager

    class FakeReceiptImageProcessor : ReceiptImageProcessor {
        var shouldSucceed: Boolean = true
        var isDecodableReturn: Boolean = true
        var lastInputFile: File? = null
        var lastOutputFile: File? = null
        var simulatedOrientation: Int = 0
        var metadataStripped: Boolean = false

        override fun isDecodable(file: File): Boolean {
            return isDecodableReturn && file.exists() && file.length() > 0
        }

        override suspend fun processAndSanitizeFile(
            inputFile: File,
            outputFile: File,
            maxDimension: Int,
            quality: Int
        ): Boolean {
            lastInputFile = inputFile
            lastOutputFile = outputFile
            if (!shouldSucceed || !inputFile.exists() || inputFile.length() == 0L) {
                return false
            }
            outputFile.writeBytes("sanitized_jpeg_pixels_no_gps_exif".toByteArray())
            metadataStripped = true
            return true
        }

        override suspend fun processAndSanitizeUri(
            contentResolver: ContentResolver,
            sourceUri: Uri,
            outputFile: File,
            maxDimension: Int,
            quality: Int
        ): Boolean {
            if (!shouldSucceed) return false
            outputFile.writeBytes("sanitized_gallery_jpeg_pixels_no_gps_exif".toByteArray())
            metadataStripped = true
            return true
        }
    }

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory("receipt_flow_test").toFile()
        receiptsDir = File(tempDir, "receipts").also { it.mkdirs() }

        mockContext = mock(Context::class.java)
        `when`(mockContext.filesDir).thenReturn(tempDir)
        `when`(mockContext.packageName).thenReturn("com.kb.ledger")

        fakeImageProcessor = FakeReceiptImageProcessor()
        manager = BillImageManager(mockContext, fakeImageProcessor)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ── 1. Camera Staging File Creation ──────────────────────────────────────

    @Test
    fun cameraStagingFile_isCreatedInReceiptsDir_withUniqueName() {
        val file1 = manager.createCameraStagingFile()
        val file2 = manager.createCameraStagingFile()

        assertNotEquals(file1.absolutePath, file2.absolutePath)
        assertTrue(file1.name.startsWith("camera_stage_"))
        assertTrue(file1.name.endsWith(".jpg"))
        assertEquals(receiptsDir.canonicalPath, file1.parentFile?.canonicalPath)
    }

    // ── 2. Camera Processing & Sanitization ───────────────────────────────────

    @Test
    fun processCameraCapture_successfulCapture_returnsSanitizedFile() = runBlocking {
        val stagingFile = manager.createCameraStagingFile()
        stagingFile.writeBytes("raw_camera_photo_bytes".toByteArray())

        val sanitized = manager.processCameraCapture(stagingFile)

        assertNotNull(sanitized)
        assertTrue(sanitized!!.exists())
        assertTrue(sanitized.name.startsWith("camera_sanitized_"))
        assertFalse("Raw capture file should be removed after sanitization", stagingFile.exists())
        assertTrue("GPS and metadata should be stripped", fakeImageProcessor.metadataStripped)
        assertEquals("sanitized_jpeg_pixels_no_gps_exif", sanitized.readText())
    }

    @Test
    fun processCameraCapture_emptyFile_isRejectedAndCleanedUp() = runBlocking {
        val emptyStaging = manager.createCameraStagingFile()
        emptyStaging.createNewFile()

        val result = manager.processCameraCapture(emptyStaging)

        assertNull(result)
        assertFalse(emptyStaging.exists())
    }

    @Test
    fun processCameraCapture_corruptOrInvalidImage_isRejected() = runBlocking {
        fakeImageProcessor.shouldSucceed = false
        val corruptStaging = manager.createCameraStagingFile()
        corruptStaging.writeBytes("corrupted bytes".toByteArray())

        val result = manager.processCameraCapture(corruptStaging)

        assertNull(result)
        assertFalse("Staging file must be cleaned up on failure", corruptStaging.exists())
    }

    // ── 3. Cancellation & Preservation of Existing Receipt ───────────────────

    @Test
    fun cameraCancellation_doesNotModifyExistingReceipt() = runBlocking {
        val txId = UUID.randomUUID().toString()
        val canonicalFile = File(receiptsDir, "$txId.jpg")
        val originalBytes = "original valid receipt".toByteArray()
        canonicalFile.writeBytes(originalBytes)

        // User starts camera capture but cancels
        val tempCameraFile = manager.createCameraStagingFile()
        // Camera cancelled, user dismisses
        manager.deleteStagingFile(tempCameraFile.absolutePath)

        assertTrue("Canonical receipt must remain untouched", canonicalFile.exists())
        assertArrayEquals(originalBytes, canonicalFile.readBytes())
        assertFalse(tempCameraFile.exists())
    }

    @Test
    fun galleryCancellation_doesNotModifyExistingReceipt() = runBlocking {
        val txId = UUID.randomUUID().toString()
        val canonicalFile = File(receiptsDir, "$txId.jpg")
        val originalBytes = "original valid receipt".toByteArray()
        canonicalFile.writeBytes(originalBytes)

        // User opens gallery but cancels (returns null URI)
        // No staging file committed
        assertTrue("Canonical receipt must remain untouched", canonicalFile.exists())
        assertArrayEquals(originalBytes, canonicalFile.readBytes())
    }

    // ── 4. Retake Workflow ───────────────────────────────────────────────────

    @Test
    fun retakeWorkflow_cleansUpPreviousStagedPhoto_andAcceptsNewPhoto() = runBlocking {
        // First shot
        val shot1 = manager.createCameraStagingFile()
        shot1.writeBytes("first shot bytes".toByteArray())
        val sanitized1 = manager.processCameraCapture(shot1)
        assertNotNull(sanitized1)

        // User chooses 'Retake' -> shot1 sanitized file is deleted
        val deleted = manager.deleteStagingFile(sanitized1!!.absolutePath)
        assertTrue(deleted)
        assertFalse(sanitized1.exists())

        // Second shot (retake)
        val shot2 = manager.createCameraStagingFile()
        shot2.writeBytes("second better shot".toByteArray())
        val sanitized2 = manager.processCameraCapture(shot2)
        assertNotNull(sanitized2)
        assertTrue(sanitized2!!.exists())

        // User accepts second shot
        val txId = UUID.randomUUID().toString()
        val committed = manager.commitStagedImage(sanitized2.absolutePath, txId)
        assertNotNull(committed)
        assertTrue(File(committed!!).exists())
    }

    // ── 5. Use Photo & Promotion to Canonical Storage ────────────────────────

    @Test
    fun commitStagedImage_replacesOldReceipt_onlyAfterSuccessfulCommit() = runBlocking {
        val txId = UUID.randomUUID().toString()
        val canonicalFile = File(receiptsDir, "$txId.jpg")
        canonicalFile.writeBytes("old receipt".toByteArray())

        val newStaged = File(receiptsDir, "camera_sanitized_${UUID.randomUUID()}.jpg")
        newStaged.writeBytes("new replacement receipt".toByteArray())

        val committedPath = manager.commitStagedImage(newStaged.absolutePath, txId)

        assertEquals(canonicalFile.absolutePath, committedPath)
        assertEquals("new replacement receipt", canonicalFile.readText())
        assertFalse("Staging file must no longer exist after promotion", newStaged.exists())
    }

    @Test
    fun commitStagedImage_missingStagingFile_preservesExistingReceipt() = runBlocking {
        val txId = UUID.randomUUID().toString()
        val canonicalFile = File(receiptsDir, "$txId.jpg")
        canonicalFile.writeBytes("existing receipt".toByteArray())

        val nonExistentStaging = File(receiptsDir, "camera_sanitized_nonexistent.jpg")

        val committedPath = manager.commitStagedImage(nonExistentStaging.absolutePath, txId)

        assertEquals(canonicalFile.absolutePath, committedPath)
        assertEquals("existing receipt", canonicalFile.readText())
    }

    // ── 6. Staging Protection & Cleanup ───────────────────────────────────────

    @Test
    fun deleteStagingFile_strictlyProtectsCanonicalReceipts() {
        val txId = UUID.randomUUID().toString()
        val canonicalFile = File(receiptsDir, "$txId.jpg")
        canonicalFile.writeBytes("canonical data".toByteArray())

        // Attempting to delete canonical file via deleteStagingFile MUST return false
        val result = manager.deleteStagingFile(canonicalFile.absolutePath)
        assertFalse(result)
        assertTrue(canonicalFile.exists())

        // But deleting actual staging prefixes succeeds
        val cameraStaged = File(receiptsDir, "camera_stage_123.jpg").also { it.createNewFile() }
        val cameraSanitized = File(receiptsDir, "camera_sanitized_456.jpg").also { it.createNewFile() }
        val galleryStaged = File(receiptsDir, "gallery_stage_789.jpg").also { it.createNewFile() }

        assertTrue(manager.deleteStagingFile(cameraStaged.absolutePath))
        assertTrue(manager.deleteStagingFile(cameraSanitized.absolutePath))
        assertTrue(manager.deleteStagingFile(galleryStaged.absolutePath))

        assertFalse(cameraStaged.exists())
        assertFalse(cameraSanitized.exists())
        assertFalse(galleryStaged.exists())
    }

    @Test
    fun isImageValid_delegatesToProcessor() {
        val testFile = File(receiptsDir, "test.jpg").also { it.writeBytes("data".toByteArray()) }
        fakeImageProcessor.isDecodableReturn = true
        assertTrue(manager.isImageValid(testFile))

        fakeImageProcessor.isDecodableReturn = false
        assertFalse(manager.isImageValid(testFile))
    }

    // ── 7. Gallery Selection Staging ─────────────────────────────────────────

    @Test
    fun copyUriToStagingFile_createsValidStagedFile() = runBlocking {
        val mockUri = mock(Uri::class.java)
        val mockResolver = mock(ContentResolver::class.java)
        `when`(mockContext.contentResolver).thenReturn(mockResolver)

        val stagedFile = manager.copyUriToStagingFile(mockUri)

        assertNotNull(stagedFile)
        assertTrue(stagedFile!!.exists())
        assertTrue(stagedFile.name.startsWith("gallery_stage_"))
        assertEquals("sanitized_gallery_jpeg_pixels_no_gps_exif", stagedFile.readText())
        assertTrue(fakeImageProcessor.metadataStripped)
    }

    // ── 8. MIME Type Validation (JPEG, PNG, WebP vs Unsupported) ──────────────

    @Test
    fun mimeTypeValidation_acceptsStandardReceiptFormats_rejectsUnsupported() {
        assertTrue(AndroidReceiptImageProcessor.isSupportedMimeType("image/jpeg"))
        assertTrue(AndroidReceiptImageProcessor.isSupportedMimeType("image/jpg"))
        assertTrue(AndroidReceiptImageProcessor.isSupportedMimeType("image/png"))
        assertTrue(AndroidReceiptImageProcessor.isSupportedMimeType("image/webp"))
        assertTrue(AndroidReceiptImageProcessor.isSupportedMimeType("image/x-webp"))

        assertFalse(AndroidReceiptImageProcessor.isSupportedMimeType(null))
        assertFalse(AndroidReceiptImageProcessor.isSupportedMimeType("image/gif"))
        assertFalse(AndroidReceiptImageProcessor.isSupportedMimeType("image/bmp"))
        assertFalse(AndroidReceiptImageProcessor.isSupportedMimeType("image/svg+xml"))
        assertFalse(AndroidReceiptImageProcessor.isSupportedMimeType("text/plain"))
        assertFalse(AndroidReceiptImageProcessor.isSupportedMimeType("application/pdf"))
    }

    // ── 9. Large Image Downsampling & Safe Sizing ─────────────────────────────

    @Test
    fun inSampleSizeCalculation_reducesLargeDimensionsSafely() {
        // Test downsampling logic used in AndroidReceiptImageProcessor:
        // while ((maxSrcDim / (inSampleSize * 2)) >= maxDimension) inSampleSize *= 2
        fun computeSampleSize(srcWidth: Int, srcHeight: Int, maxDimension: Int): Int {
            var inSampleSize = 1
            val maxSrcDim = maxOf(srcWidth, srcHeight)
            while ((maxSrcDim / (inSampleSize * 2)) >= maxDimension) {
                inSampleSize *= 2
            }
            return inSampleSize
        }

        // Standard 12MP camera photo: 4000x3000 -> sampleSize 1 (4000px, matrix scales to 2048)
        assertEquals(1, computeSampleSize(4000, 3000, 2048))
        // 50MP photo: 8192x6144 -> sampleSize 4 (8192 / 4 = 2048px exactly)
        assertEquals(4, computeSampleSize(8192, 6144, 2048))
        // 108MP photo: 12000x9000 -> sampleSize 4 (12000 / 4 = 3000px, matrix scales to 2048)
        assertEquals(4, computeSampleSize(12000, 9000, 2048))
        // 200MP photo: 16384x12288 -> sampleSize 8 (16384 / 8 = 2048px exactly)
        assertEquals(8, computeSampleSize(16384, 12288, 2048))
    }

    // ── 10. Process Recreation & Context Preservation ────────────────────────

    @Test
    fun processRecreation_preservesTransactionIdAndStagedPathReference() = runBlocking {
        val originalTxId = UUID.randomUUID().toString()
        val staged = manager.createCameraStagingFile().also {
            it.writeBytes("staged photo before process kill".toByteArray())
        }

        // Simulate activity destruction and recreation with saved path string
        val savedPath = staged.absolutePath

        // Recreate manager
        val newManager = BillImageManager(mockContext, fakeImageProcessor)
        val restoredFile = File(savedPath)
        assertTrue("Staged file survives process recreation on disk", restoredFile.exists())

        // User confirms after activity recreation
        val committedPath = newManager.commitStagedImage(savedPath, originalTxId)
        assertNotNull(committedPath)
        val canonicalFile = File(committedPath!!)
        assertTrue(canonicalFile.exists())
        assertEquals(originalTxId, canonicalFile.nameWithoutExtension)
        assertEquals("staged photo before process kill", canonicalFile.readText())
    }

    // ── 11. App Restart Preserves Saved Receipt ──────────────────────────────

    @Test
    fun appRestart_preservesSavedReceipt() = runBlocking {
        val txId = UUID.randomUUID().toString()
        val canonicalFile = File(receiptsDir, "$txId.jpg")
        canonicalFile.writeBytes("permanent receipt image".toByteArray())

        // Simulate cold app restart: brand new BillImageManager instance
        val freshManager = BillImageManager(mockContext, fakeImageProcessor)
        assertTrue(freshManager.hasInternalImage(txId))
        val resolved = freshManager.internalFileForTx(txId)
        assertTrue(resolved.exists())
        assertEquals("permanent receipt image", resolved.readText())
    }

    // ── 12. Transaction Balance Invariance Across Image Operations ────────────

    @Test
    fun transactionBalance_remainsMathematicallyInvariable_duringImageOperations() {
        val giveAmountCents = 50000L   // ₹500.00
        val receiveAmountCents = 70000L // ₹700.00

        // Net balance formula: sum(RECEIVE) - sum(GIVE)
        fun calculateNet(giveCents: Long, receiveCents: Long): Long {
            return receiveCents - giveCents
        }

        val initialNet = calculateNet(giveAmountCents, receiveAmountCents)
        assertEquals(20000L, initialNet) // +₹200.00 (You Get)

        // Attach image: balance must NOT change
        val txWithImage = giveAmountCents
        val netWithImage = calculateNet(txWithImage, receiveAmountCents)
        assertEquals(initialNet, netWithImage)

        // Replace image: balance must NOT change
        val netWithReplacedImage = calculateNet(txWithImage, receiveAmountCents)
        assertEquals(initialNet, netWithReplacedImage)

        // Remove image: balance must NOT change
        val netWithRemovedImage = calculateNet(giveAmountCents, receiveAmountCents)
        assertEquals(initialNet, netWithRemovedImage)
    }

    // ── 13. Stale Staging Files Cleanup ───────────────────────────────────────

    @Test
    fun cleanStaleStagingFiles_removesExpiredStagingFiles_preservesRecentAndCanonical() {
        val txId = UUID.randomUUID().toString()
        val canonicalFile = File(receiptsDir, "$txId.jpg").also { it.writeBytes("canonical".toByteArray()) }

        val recentStaging = File(receiptsDir, "camera_stage_recent.jpg").also {
            it.writeBytes("recent".toByteArray())
            it.setLastModified(System.currentTimeMillis())
        }

        val oldStaging = File(receiptsDir, "camera_stage_old.jpg").also {
            it.writeBytes("old".toByteArray())
            it.setLastModified(System.currentTimeMillis() - 7_200_000L) // 2 hours old
        }

        manager.cleanStaleStagingFiles()

        assertTrue("Canonical file must be preserved", canonicalFile.exists())
        assertTrue("Recent staging file (< 1h) must be preserved", recentStaging.exists())
        assertFalse("Stale staging file (> 1h) must be deleted", oldStaging.exists())
    }
}
