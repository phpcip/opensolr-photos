package com.opensolr.photos.storage

import com.opensolr.photos.data.StorageConfig
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

class BackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NotFoundException(path: String) : Exception(path)

/** One place files are kept: the owner's SFTP server, S3 bucket or WebDAV share. Paths are full remote paths. */
interface Backend : Closeable {

    /** Opens the connection; the host key or the first answer is checked here. */
    fun connect()

    /** The directories of [path] (itself a directory), created where missing. */
    fun mkdirs(path: String)

    /** [size] bytes from [source] written to [path], its directories created on the way. */
    fun put(path: String, size: Long, source: () -> InputStream)

    /** [path] copied into [sink]; the bytes written. Throws [NotFoundException]. */
    fun get(path: String, sink: OutputStream): Long

    /** The size of [path], or null when there is nothing there. */
    fun stat(path: String): Long?

    /** The names (files and directories) directly inside [path]; empty when it does not exist. */
    fun list(path: String): List<String>

    /** [path] removed; nothing happens when it is already gone. */
    fun delete(path: String)

    /** Everything under [path] removed, then [path] itself. */
    fun deleteTree(path: String)

    companion object {

        fun open(config: StorageConfig, onHostKey: ((String) -> Unit)? = null): Backend = when (config.kind) {
            StorageConfig.KIND_SFTP -> SftpBackend(config, onHostKey)
            StorageConfig.KIND_S3 -> S3Backend(config)
            StorageConfig.KIND_WEBDAV -> WebDavBackend(config)
            else -> throw BackendException("Unknown storage kind")
        }
    }
}

/** Where the files of one index live inside the backend. */
class Layout(config: StorageConfig, private val indexName: String) {

    private val root: String = when (config.kind) {
        StorageConfig.KIND_SFTP -> config.field("path").trimEnd('/').ifBlank { "." }
        StorageConfig.KIND_S3 -> config.field("prefix").trim('/')
        else -> config.field("url").trimEnd('/')
    }

    private fun join(vararg parts: String): String = parts.filter { it.isNotEmpty() }.joinToString("/")

    /** The index's own folder: everything the app writes is inside it. */
    val home: String get() = join(root, indexName)

    /** The folder of the full-size copies, then the photo's own folder and name as on the phone. */
    fun main(folder: String, fileName: String): String = join(home, "o", folder.trim('/'), fileName)

    /** The 720 px copy restore brings to a new phone. */
    fun restoreCopy(id: String): String = join(home, "r", "$id.jpg")

    fun manifest(deviceId: String): String = join(home, "manifest-$deviceId.jsonl")

    val mainRoot: String get() = join(home, "o")
    val restoreRoot: String get() = join(home, "r")

    /** The file written and removed by Test. */
    val probe: String get() = join(home, ".opensolr-photos-test")
}
