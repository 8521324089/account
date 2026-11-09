package com.kb.ledger.data.remote

import com.kb.ledger.util.AppResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [OkHttpDriveApiService] using mocked HTTP responses via [MockDriveInterceptor].
 * Validates Google Drive REST API request construction, response parsing, pagination, and error classification.
 */
class DriveApiServiceTest {

    private lateinit var mockInterceptor: MockDriveInterceptor
    private lateinit var driveService: DriveApiService

    @Before
    fun setUp() {
        mockInterceptor = MockDriveInterceptor()
        val client = OkHttpClient.Builder()
            .addInterceptor(mockInterceptor)
            .build()
        driveService = OkHttpDriveApiService(client = client)
    }

    // ── listAppDataFiles ──────────────────────────────────────────────────────

    @Test
    fun listAppDataFiles_singlePage_success() = runBlocking {
        mockInterceptor.handle { request ->
            assertEquals("Bearer test-token-123", request.header("Authorization"))
            assertEquals("appDataFolder", request.url.queryParameter("spaces"))
            assertTrue(request.url.queryParameter("q")!!.contains("appDataFolder"))

            val responseJson = """
                {
                    "files": [
                        {
                            "id": "file-1",
                            "name": "manifest.json",
                            "mimeType": "application/json",
                            "modifiedTime": "2026-10-20T10:00:00Z",
                            "version": 1,
                            "size": 150
                        },
                        {
                            "id": "file-2",
                            "name": "persons.json",
                            "mimeType": "application/json",
                            "modifiedTime": "2026-10-20T10:01:00Z",
                            "version": 2,
                            "size": 500
                        }
                    ]
                }
            """.trimIndent()
            MockDriveInterceptor.createResponse(request, 200, responseJson)
        }

        val result = driveService.listAppDataFiles("test-token-123")
        assertTrue(result is AppResult.Success)
        val files = (result as AppResult.Success).data
        assertEquals(2, files.size)
        assertEquals("manifest.json", files[0].name)
        assertEquals("file-1", files[0].id)
        assertEquals("persons.json", files[1].name)
        assertEquals(2L, files[1].version)
    }

    @Test
    fun listAppDataFiles_multiPagePagination_aggregatesAllPages() = runBlocking {
        mockInterceptor.handle { request ->
            val pageToken = request.url.queryParameter("pageToken")
            if (pageToken == null) {
                // First page
                val page1Json = """
                    {
                        "files": [{"id": "p1-file", "name": "page1.json"}],
                        "nextPageToken": "token-for-page-2"
                    }
                """.trimIndent()
                MockDriveInterceptor.createResponse(request, 200, page1Json)
            } else if (pageToken == "token-for-page-2") {
                // Second page
                val page2Json = """
                    {
                        "files": [{"id": "p2-file", "name": "page2.json"}],
                        "nextPageToken": null
                    }
                """.trimIndent()
                MockDriveInterceptor.createResponse(request, 200, page2Json)
            } else {
                MockDriveInterceptor.createResponse(request, 400, "Unexpected page token")
            }
        }

        val result = driveService.listAppDataFiles("test-token-123")
        assertTrue(result is AppResult.Success)
        val files = (result as AppResult.Success).data
        assertEquals(2, files.size)
        assertEquals("page1.json", files[0].name)
        assertEquals("page2.json", files[1].name)
        assertEquals(2, mockInterceptor.interceptedRequests.size)
    }

    @Test
    fun listAppDataFiles_http401_classifiedAsAuthTokenExpired() = runBlocking {
        mockInterceptor.handle { request ->
            val errorJson = """
                {
                    "error": {
                        "code": 401,
                        "message": "Invalid Credentials"
                    }
                }
            """.trimIndent()
            MockDriveInterceptor.createResponse(request, 401, errorJson)
        }

        val result = driveService.listAppDataFiles("expired-token")
        assertTrue(result is AppResult.Error)
        val error = result as AppResult.Error
        assertTrue(error.message.contains("AUTH_TOKEN_EXPIRED"))
        assertTrue(error.message.contains("Invalid Credentials"))
    }

    @Test
    fun listAppDataFiles_http403_classifiedAsAuthPermissionDenied() = runBlocking {
        mockInterceptor.handle { request ->
            val errorJson = """{"error": {"code": 403, "message": "Insufficient Permission"}}"""
            MockDriveInterceptor.createResponse(request, 403, errorJson)
        }

        val result = driveService.listAppDataFiles("bad-scope-token")
        assertTrue(result is AppResult.Error)
        val error = result as AppResult.Error
        assertTrue(error.message.contains("AUTH_PERMISSION_DENIED"))
    }

    @Test
    fun listAppDataFiles_http500_classifiedAsDriveServerError() = runBlocking {
        mockInterceptor.handle { request ->
            val errorJson = """{"error": {"code": 500, "message": "Backend Error"}}"""
            MockDriveInterceptor.createResponse(request, 500, errorJson)
        }

        val result = driveService.listAppDataFiles("token")
        assertTrue(result is AppResult.Error)
        val error = result as AppResult.Error
        assertTrue(error.message.contains("DRIVE_SERVER_ERROR"))
    }

    // ── downloadFileContent ───────────────────────────────────────────────────

    @Test
    fun downloadFileContent_success_returnsUtf8Content() = runBlocking {
        val expectedPayload = """{"schemaVersion": 1, "ownerId": "test@gmail.com"}"""

        mockInterceptor.handle { request ->
            assertEquals("Bearer token-abc", request.header("Authorization"))
            assertTrue(request.url.toString().contains("/files/test-file-id?alt=media"))
            MockDriveInterceptor.createResponse(request, 200, expectedPayload)
        }

        val result = driveService.downloadFileContent("token-abc", "test-file-id")
        assertTrue(result is AppResult.Success)
        assertEquals(expectedPayload, (result as AppResult.Success).data)
    }

    @Test
    fun downloadFileContent_http404_classifiedAsFileNotFound() = runBlocking {
        mockInterceptor.handle { request ->
            val errorJson = """{"error": {"code": 404, "message": "File not found: missing-id"}}"""
            MockDriveInterceptor.createResponse(request, 404, errorJson)
        }

        val result = driveService.downloadFileContent("token-abc", "missing-id")
        assertTrue(result is AppResult.Error)
        val error = result as AppResult.Error
        assertTrue(error.message.contains("FILE_NOT_FOUND"))
    }

    // ── createAppDataFile ─────────────────────────────────────────────────────

    @Test
    fun createAppDataFile_success_createsMultipartAndReturnsMetadata() = runBlocking {
        val content = """[{"cloudId": "person-uuid-1", "name": "Alice"}]"""

        mockInterceptor.handle { request ->
            assertEquals("POST", request.method)
            assertEquals("Bearer test-token", request.header("Authorization"))
            assertTrue(request.url.toString().contains("uploadType=multipart"))

            val responseJson = """
                {
                    "id": "new-file-id-789",
                    "name": "persons.json",
                    "mimeType": "application/json",
                    "modifiedTime": "2026-10-20T11:00:00Z",
                    "version": 1,
                    "size": 45
                }
            """.trimIndent()
            MockDriveInterceptor.createResponse(request, 200, responseJson)
        }

        val result = driveService.createAppDataFile(
            accessToken = "test-token",
            name = "persons.json",
            content = content
        )

        assertTrue(result is AppResult.Success)
        val meta = (result as AppResult.Success).data
        assertEquals("new-file-id-789", meta.id)
        assertEquals("persons.json", meta.name)
        assertEquals(1L, meta.version)
    }

    // ── updateFileContent ─────────────────────────────────────────────────────

    @Test
    fun updateFileContent_success_sendsPatchMediaAndReturnsUpdatedMetadata() = runBlocking {
        val updatedContent = """[{"cloudId": "person-uuid-1", "name": "Alice Updated"}]"""

        mockInterceptor.handle { request ->
            assertEquals("PATCH", request.method)
            assertEquals("Bearer test-token", request.header("Authorization"))
            assertTrue(request.url.toString().contains("/files/existing-file-id?uploadType=media"))

            val responseJson = """
                {
                    "id": "existing-file-id",
                    "name": "persons.json",
                    "version": 2
                }
            """.trimIndent()
            MockDriveInterceptor.createResponse(request, 200, responseJson)
        }

        val result = driveService.updateFileContent(
            accessToken = "test-token",
            fileId = "existing-file-id",
            content = updatedContent
        )

        assertTrue(result is AppResult.Success)
        val meta = (result as AppResult.Success).data
        assertEquals("existing-file-id", meta.id)
        assertEquals(2L, meta.version)
    }

    // ── deleteFile ────────────────────────────────────────────────────────────

    @Test
    fun deleteFile_success_returnsUnit() = runBlocking {
        mockInterceptor.handle { request ->
            assertEquals("DELETE", request.method)
            assertTrue(request.url.toString().contains("/files/file-to-delete"))
            MockDriveInterceptor.createResponse(request, 204, "")
        }

        val result = driveService.deleteFile("test-token", "file-to-delete")
        assertTrue(result is AppResult.Success)
    }

    @Test
    fun deleteFile_http404_treatedAsSuccess() = runBlocking {
        // Deleting a non-existent file is idempotent (already gone)
        mockInterceptor.handle { request ->
            MockDriveInterceptor.createResponse(request, 404, "File not found")
        }

        val result = driveService.deleteFile("test-token", "already-deleted-file")
        assertTrue(result is AppResult.Success)
    }

    @Test
    fun deleteFile_http403_returnsError() = runBlocking {
        mockInterceptor.handle { request ->
            MockDriveInterceptor.createResponse(request, 403, "Permission Denied")
        }

        val result = driveService.deleteFile("test-token", "protected-file")
        assertTrue(result is AppResult.Error)
    }
}
