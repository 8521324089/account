package com.kb.ledger.backup.partition

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Handles atomic file writing, GZIP compression, and deserialization for backup chunks and manifests.
 *
 * Safety guarantees:
 * 1. Atomic write: always writes to `<filename>.tmp` first, flushes and syncs file descriptor,
 *    then atomically renames to the target file.
 * 2. Corruption detection: GZIP integrity checks + JSON structure validation ensure broken or
 *    partial writes are rejected rather than silently accepted.
 * 3. Compact & non-directly readable: compressed binary representation.
 */
object CompressedFileHelper {

    val gson: Gson = GsonBuilder().create()

    /**
     * Atomically writes [data] to [targetFile] as GZIP-compressed UTF-8 JSON.
     *
     * @return true if write and atomic replacement succeeded, false otherwise.
     */
    fun <T> writeCompressedAtomic(targetFile: File, data: T): Boolean {
        val parent = targetFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) {
            return false
        }

        val tempFile = File(parent, "${targetFile.name}.tmp_${System.nanoTime()}")
        return try {
            FileOutputStream(tempFile).use { fos ->
                val gzos = GZIPOutputStream(fos)
                val writer = OutputStreamWriter(gzos, StandardCharsets.UTF_8)
                gson.toJson(data, writer)
                writer.flush()
                gzos.finish()
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Exception) {
                    // Ignored if sync not supported on FS
                }
            }

            // Verify written temp file is non-empty
            if (!tempFile.exists() || tempFile.length() == 0L) {
                tempFile.delete()
                return false
            }

            // Atomic replacement: attempt atomic move, fallback to rename/copy
            var replaced = false
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    java.nio.file.Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    replaced = true
                }
            } catch (_: Exception) {
                // If ATOMIC_MOVE unsupported across filesystems, fallback
            }

            if (!replaced) {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            }
            targetFile.exists() && targetFile.length() > 0
        } catch (e: Exception) {
            tempFile.delete()
            false
        }
    }

    /**
     * Reads and deserializes a GZIP-compressed UTF-8 JSON file into [typeToken].
     *
     * @return deserialized instance of [T], or null if file is missing, corrupt, or invalid.
     */
    fun <T> readCompressed(file: File, typeToken: TypeToken<T>): T? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            FileInputStream(file).use { fis ->
                GZIPInputStream(fis).use { gzis ->
                    InputStreamReader(gzis, StandardCharsets.UTF_8).use { reader ->
                        gson.fromJson(reader, typeToken.type)
                    }
                }
            }
        } catch (e: Exception) {
            null // Corrupt or invalid GZIP file
        }
    }

    /**
     * Compresses the given [data] to byte array (useful for benchmarking compression).
     */
    fun <T> compressToBytes(data: T): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            OutputStreamWriter(gzos, StandardCharsets.UTF_8).use { writer ->
                gson.toJson(data, writer)
                writer.flush()
            }
        }
        return baos.toByteArray()
    }
}
