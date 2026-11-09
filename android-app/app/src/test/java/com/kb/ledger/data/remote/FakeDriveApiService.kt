package com.kb.ledger.data.remote

import com.kb.ledger.util.AppResult
import java.util.UUID

data class FakeDriveFile(
    val id: String,
    val name: String,
    var content: String,
    var version: Long = 1,
    var modifiedTime: String = "2026-10-20T10:00:00Z"
)

/**
 * In-memory test fake for [DriveApiService] that simulates Google Drive AppDataFolder.
 * Enables detailed tracking of write order, rollbacks, and simulated errors.
 */
class FakeDriveApiService : DriveApiService {

    val files = mutableMapOf<String, FakeDriveFile>()
    val callLog = mutableListOf<String>()

    var listFilesResult: AppResult<List<DriveFileMetadata>>? = null
    var downloadResultOverride: ((fileId: String) -> AppResult<String>?)? = null
    var createResultOverride: ((name: String) -> AppResult<DriveFileMetadata>?)? = null
    var createBinaryResultOverride: ((name: String) -> AppResult<DriveFileMetadata>?)? = null
    var updateResultOverride: ((fileId: String) -> AppResult<DriveFileMetadata>?)? = null
    var deleteResultOverride: ((fileId: String) -> AppResult<Unit>?)? = null

    fun addFile(name: String, content: String, fileId: String = UUID.randomUUID().toString(), version: Long = 1): FakeDriveFile {
        val file = FakeDriveFile(id = fileId, name = name, content = content, version = version)
        files[fileId] = file
        return file
    }

    override suspend fun listAppDataFiles(accessToken: String): AppResult<List<DriveFileMetadata>> {
        callLog.add("listAppDataFiles")
        listFilesResult?.let { return it }

        val metadataList = files.values.map {
            DriveFileMetadata(
                id = it.id,
                name = it.name,
                mimeType = "application/json",
                modifiedTime = it.modifiedTime,
                version = it.version,
                size = it.content.length.toLong()
            )
        }
        return AppResult.Success(metadataList)
    }

    override suspend fun downloadFileContent(accessToken: String, fileId: String): AppResult<String> {
        callLog.add("downloadFileContent:$fileId")
        downloadResultOverride?.invoke(fileId)?.let { return it }

        val file = files[fileId] ?: return AppResult.Error("FILE_NOT_FOUND (HTTP 404): File $fileId not found")
        return AppResult.Success(file.content)
    }

    override suspend fun downloadBinaryFile(accessToken: String, fileId: String): AppResult<ByteArray> {
        callLog.add("downloadBinaryFile:$fileId")
        val file = files[fileId] ?: return AppResult.Error("FILE_NOT_FOUND (HTTP 404): File $fileId not found")
        return AppResult.Success(file.content.toByteArray(Charsets.UTF_8))
    }

    override suspend fun createAppDataFile(
        accessToken: String,
        name: String,
        content: String,
        mimeType: String
    ): AppResult<DriveFileMetadata> {
        callLog.add("createAppDataFile:$name")
        createResultOverride?.invoke(name)?.let { return it }

        val id = UUID.randomUUID().toString()
        val file = FakeDriveFile(id = id, name = name, content = content, version = 1)
        files[id] = file

        return AppResult.Success(
            DriveFileMetadata(
                id = id,
                name = name,
                mimeType = mimeType,
                modifiedTime = file.modifiedTime,
                version = 1,
                size = content.length.toLong()
            )
        )
    }

    override suspend fun createAppDataBinaryFile(
        accessToken: String,
        name: String,
        bytes: ByteArray,
        mimeType: String
    ): AppResult<DriveFileMetadata> {
        callLog.add("createAppDataBinaryFile:$name")
        createBinaryResultOverride?.invoke(name)?.let { return it }

        val id = UUID.randomUUID().toString()
        val file = FakeDriveFile(id = id, name = name, content = "BINARY[${bytes.size} bytes]", version = 1)
        files[id] = file

        return AppResult.Success(
            DriveFileMetadata(
                id = id,
                name = name,
                mimeType = mimeType,
                modifiedTime = file.modifiedTime,
                version = 1,
                size = bytes.size.toLong()
            )
        )
    }

    override suspend fun updateFileContent(
        accessToken: String,
        fileId: String,
        content: String,
        mimeType: String
    ): AppResult<DriveFileMetadata> {
        callLog.add("updateFileContent:$fileId")
        updateResultOverride?.invoke(fileId)?.let { return it }

        val file = files[fileId] ?: return AppResult.Error("FILE_NOT_FOUND (HTTP 404): File $fileId not found")
        file.content = content
        file.version += 1
        return AppResult.Success(
            DriveFileMetadata(
                id = file.id,
                name = file.name,
                mimeType = mimeType,
                modifiedTime = file.modifiedTime,
                version = file.version,
                size = content.length.toLong()
            )
        )
    }

    override suspend fun deleteFile(accessToken: String, fileId: String): AppResult<Unit> {
        callLog.add("deleteFile:$fileId")
        deleteResultOverride?.invoke(fileId)?.let { return it }

        files.remove(fileId)
        return AppResult.Success(Unit)
    }
}
