package com.kb.ledger.data.remote

import com.google.gson.Gson
import com.kb.ledger.util.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Service interface for Google Drive REST API v3 operations targeting [appDataFolder].
 */
interface DriveApiService {

    /**
     * Lists files in the user's private [appDataFolder].
     * Handles pagination automatically.
     */
    suspend fun listAppDataFiles(accessToken: String): AppResult<List<DriveFileMetadata>>

    /**
     * Downloads raw file contents as a UTF-8 string using `alt=media`.
     */
    suspend fun downloadFileContent(accessToken: String, fileId: String): AppResult<String>

    /**
     * Downloads raw binary file contents (such as images) using `alt=media`.
     */
    suspend fun downloadBinaryFile(accessToken: String, fileId: String): AppResult<ByteArray>

    /**
     * Creates a new file in [appDataFolder] using a multipart/related upload.
     */
    suspend fun createAppDataFile(
        accessToken: String,
        name: String,
        content: String,
        mimeType: String = "application/json"
    ): AppResult<DriveFileMetadata>

    /**
     * Updates an existing file's content in place via PATCH with `uploadType=media`.
     */
    suspend fun updateFileContent(
        accessToken: String,
        fileId: String,
        content: String,
        mimeType: String = "application/json"
    ): AppResult<DriveFileMetadata>

    /**
     * Creates a new binary file (such as a JPEG image) in [appDataFolder].
     */
    suspend fun createAppDataBinaryFile(
        accessToken: String,
        name: String,
        bytes: ByteArray,
        mimeType: String = "image/jpeg"
    ): AppResult<DriveFileMetadata>

    /**
     * Deletes a file by ID (used for cleaning up duplicate or stale files).
     */
    suspend fun deleteFile(accessToken: String, fileId: String): AppResult<Unit>
}

/**
 * Production OkHttp implementation of [DriveApiService].
 */
class OkHttpDriveApiService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) : DriveApiService {

    companion object {
        private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    override suspend fun listAppDataFiles(accessToken: String): AppResult<List<DriveFileMetadata>> =
        withContext(Dispatchers.IO) {
            val allFiles = mutableListOf<DriveFileMetadata>()
            var pageToken: String? = null

            do {
                val urlBuilder = "$DRIVE_API_BASE/files".toHttpUrlOrNull()?.newBuilder()
                    ?: return@withContext AppResult.Error("Invalid Drive API base URL")

                urlBuilder.addQueryParameter("spaces", "appDataFolder")
                urlBuilder.addQueryParameter("q", "'appDataFolder' in parents and trashed = false")
                urlBuilder.addQueryParameter("fields", "nextPageToken, files(id, name, mimeType, modifiedTime, version, size)")
                urlBuilder.addQueryParameter("pageSize", "100")
                if (!pageToken.isNullOrBlank()) {
                    urlBuilder.addQueryParameter("pageToken", pageToken)
                }

                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            return@withContext parseHttpError(response)
                        }

                        val bodyString = response.body?.string()
                            ?: return@withContext AppResult.Error("Empty response body from Drive files.list")

                        val listResponse = gson.fromJson(bodyString, DriveFileListResponse::class.java)
                        allFiles.addAll(listResponse.files)
                        pageToken = listResponse.nextPageToken
                    }
                } catch (e: IOException) {
                    return@withContext AppResult.Error("Network error listing Drive files: ${e.message}", e)
                } catch (e: Exception) {
                    return@withContext AppResult.Error("Error parsing Drive files list: ${e.message}", e)
                }
            } while (!pageToken.isNullOrBlank())

            AppResult.Success(allFiles)
        }

    override suspend fun downloadFileContent(accessToken: String, fileId: String): AppResult<String> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$DRIVE_API_BASE/files/$fileId?alt=media")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext parseHttpError(response)
                    }

                    val bodyString = response.body?.string()
                        ?: return@withContext AppResult.Error("Empty file content from Drive for file $fileId")

                    AppResult.Success(bodyString)
                }
            } catch (e: IOException) {
                AppResult.Error("Network error downloading file $fileId: ${e.message}", e)
            } catch (e: Exception) {
                AppResult.Error("Failed to download file $fileId: ${e.message}", e)
            }
        }

    override suspend fun downloadBinaryFile(accessToken: String, fileId: String): AppResult<ByteArray> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$DRIVE_API_BASE/files/$fileId?alt=media")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext parseHttpError(response)
                    }

                    val bytes = response.body?.bytes()
                        ?: return@withContext AppResult.Error("Empty binary file content from Drive for file $fileId")

                    AppResult.Success(bytes)
                }
            } catch (e: IOException) {
                AppResult.Error("Network error downloading binary file $fileId: ${e.message}", e)
            } catch (e: Exception) {
                AppResult.Error("Failed to download binary file $fileId: ${e.message}", e)
            }
        }

    override suspend fun createAppDataFile(
        accessToken: String,
        name: String,
        content: String,
        mimeType: String
    ): AppResult<DriveFileMetadata> = withContext(Dispatchers.IO) {
        val metadataJson = gson.toJson(
            mapOf(
                "name" to name,
                "parents" to listOf("appDataFolder")
            )
        )

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addPart(metadataJson.toRequestBody(JSON_MEDIA_TYPE))
            .addPart(content.toRequestBody(mimeType.toMediaType()))
            .build()

        val request = Request.Builder()
            .url("$DRIVE_UPLOAD_BASE/files?uploadType=multipart&fields=id,name,mimeType,modifiedTime,version,size")
            .header("Authorization", "Bearer $accessToken")
            .post(multipartBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext parseHttpError(response)
                }

                val bodyString = response.body?.string()
                    ?: return@withContext AppResult.Error("Empty response body from Drive file creation for $name")

                val metadata = gson.fromJson(bodyString, DriveFileMetadata::class.java)
                AppResult.Success(metadata)
            }
        } catch (e: IOException) {
            AppResult.Error("Network error creating Drive file $name: ${e.message}", e)
        } catch (e: Exception) {
            AppResult.Error("Failed to create Drive file $name: ${e.message}", e)
        }
    }

    override suspend fun createAppDataBinaryFile(
        accessToken: String,
        name: String,
        bytes: ByteArray,
        mimeType: String
    ): AppResult<DriveFileMetadata> = withContext(Dispatchers.IO) {
        val metadataJson = gson.toJson(
            mapOf(
                "name" to name,
                "parents" to listOf("appDataFolder")
            )
        )

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addPart(metadataJson.toRequestBody(JSON_MEDIA_TYPE))
            .addPart(bytes.toRequestBody(mimeType.toMediaType()))
            .build()

        val request = Request.Builder()
            .url("$DRIVE_UPLOAD_BASE/files?uploadType=multipart&fields=id,name,mimeType,modifiedTime,version,size")
            .header("Authorization", "Bearer $accessToken")
            .post(multipartBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext parseHttpError(response)
                }

                val bodyString = response.body?.string()
                    ?: return@withContext AppResult.Error("Empty response body from Drive file creation for $name")

                val metadata = gson.fromJson(bodyString, DriveFileMetadata::class.java)
                AppResult.Success(metadata)
            }
        } catch (e: IOException) {
            AppResult.Error("Network error creating Drive binary file $name: ${e.message}", e)
        } catch (e: Exception) {
            AppResult.Error("Failed to create Drive binary file $name: ${e.message}", e)
        }
    }

    override suspend fun updateFileContent(
        accessToken: String,
        fileId: String,
        content: String,
        mimeType: String
    ): AppResult<DriveFileMetadata> = withContext(Dispatchers.IO) {
        val requestBody = content.toRequestBody(mimeType.toMediaType())

        val request = Request.Builder()
            .url("$DRIVE_UPLOAD_BASE/files/$fileId?uploadType=media&fields=id,name,mimeType,modifiedTime,version,size")
            .header("Authorization", "Bearer $accessToken")
            .patch(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext parseHttpError(response)
                }

                val bodyString = response.body?.string()
                    ?: return@withContext AppResult.Error("Empty response body updating file $fileId")

                val metadata = gson.fromJson(bodyString, DriveFileMetadata::class.java)
                AppResult.Success(metadata)
            }
        } catch (e: IOException) {
            AppResult.Error("Network error updating file $fileId: ${e.message}", e)
        } catch (e: Exception) {
            AppResult.Error("Failed to update file $fileId: ${e.message}", e)
        }
    }

    override suspend fun deleteFile(accessToken: String, fileId: String): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$DRIVE_API_BASE/files/$fileId")
                .header("Authorization", "Bearer $accessToken")
                .delete()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 404) {
                        return@withContext parseHttpError(response)
                    }
                    AppResult.Success(Unit)
                }
            } catch (e: IOException) {
                AppResult.Error("Network error deleting file $fileId: ${e.message}", e)
            } catch (e: Exception) {
                AppResult.Error("Failed to delete file $fileId: ${e.message}", e)
            }
        }

    private fun <T> parseHttpError(response: Response): AppResult<T> {
        val code = response.code
        val rawBody = try { response.body?.string() } catch (_: Exception) { null }

        val parsedMessage = if (!rawBody.isNullOrBlank()) {
            try {
                val errorResponse = gson.fromJson(rawBody, GoogleApiErrorResponse::class.java)
                errorResponse.error?.message ?: rawBody
            } catch (_: Exception) {
                rawBody
            }
        } else {
            response.message.ifBlank { "HTTP $code" }
        }

        val classifiedError = when (code) {
            401 -> "AUTH_TOKEN_EXPIRED (HTTP 401): $parsedMessage"
            403 -> "AUTH_PERMISSION_DENIED (HTTP 403): $parsedMessage"
            404 -> "FILE_NOT_FOUND (HTTP 404): $parsedMessage"
            429 -> "RATE_LIMIT_EXCEEDED (HTTP 429): $parsedMessage"
            in 500..599 -> "DRIVE_SERVER_ERROR (HTTP $code): $parsedMessage"
            else -> "DRIVE_API_ERROR (HTTP $code): $parsedMessage"
        }

        return AppResult.Error(classifiedError)
    }
}
