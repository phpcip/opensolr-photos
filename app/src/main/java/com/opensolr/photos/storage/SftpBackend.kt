package com.opensolr.photos.storage

import com.opensolr.photos.data.StorageConfig
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.security.Security
import java.util.EnumSet

/**
 * The owner's own server over SFTP: host, port, user, a private key (OpenSSH or PEM text) or a password.
 * The host key is remembered at the first connection (Test) and must match afterwards.
 */
class SftpBackend(private val config: StorageConfig, private val onHostKey: ((String) -> Unit)?) : Backend {

    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null

    override fun connect() {
        provider()
        val host = config.field("host").ifBlank { throw BackendException("No host") }
        val port = config.field("port").toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
        val user = config.field("user").ifBlank { throw BackendException("No user") }
        val known = config.field("host_key")
        val client = SSHClient(DefaultConfig())
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.timeout = IO_TIMEOUT_MS
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, p: Int, key: PublicKey): Boolean {
                val seen = fingerprint(key)
                if (known.isBlank()) {
                    onHostKey?.invoke(seen)
                    return onHostKey != null
                }
                return seen == known
            }
            override fun findExistingAlgorithms(hostname: String, p: Int): List<String> = emptyList()
        })
        try {
            client.connect(host, port)
            val key = config.field("private_key")
            if (key.isNotBlank()) {
                val passphrase = config.field("passphrase")
                val provider = client.loadKeys(key, null, if (passphrase.isEmpty()) null else PasswordUtils.createOneOff(passphrase.toCharArray()))
                client.authPublickey(user, provider)
            } else {
                client.authPassword(user, config.field("password"))
            }
            sftp = client.newSFTPClient()
            ssh = client
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw BackendException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun client(): SFTPClient = sftp ?: throw BackendException("Not connected")

    override fun mkdirs(path: String) {
        wrap { client().mkdirs(path) }
    }

    override fun put(path: String, size: Long, source: () -> InputStream) {
        wrap {
            val parent = path.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) client().mkdirs(parent)
            val tmp = "$path.part"
            val file = client().open(tmp, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
            try {
                file.RemoteFileOutputStream(0, WRITE_AHEAD).use { out ->
                    source().use { it.copyTo(out, BUFFER) }
                }
            } finally {
                file.close()
            }
            runCatching { client().rm(path) }
            client().rename(tmp, path)
        }
    }

    override fun get(path: String, sink: OutputStream): Long = wrap {
        val file = try {
            client().open(path, EnumSet.of(OpenMode.READ))
        } catch (e: SFTPException) {
            if (e.statusCode == net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE) throw NotFoundException(path)
            throw e
        }
        try {
            file.ReadAheadRemoteFileInputStream(READ_AHEAD).use { it.copyTo(sink, BUFFER) }
        } finally {
            file.close()
        }
    }

    override fun stat(path: String): Long? = wrap { client().statExistence(path)?.size }

    override fun list(path: String): List<String> = wrap {
        try {
            client().ls(path).map { it.name }
        } catch (e: SFTPException) {
            if (e.statusCode == net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE) emptyList() else throw e
        }
    }

    override fun delete(path: String) {
        wrap {
            try {
                client().rm(path)
            } catch (e: SFTPException) {
                if (e.statusCode != net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE) throw e
            }
        }
    }

    override fun deleteTree(path: String) {
        wrap {
            val entries = try {
                client().ls(path)
            } catch (e: SFTPException) {
                if (e.statusCode == net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE) return@wrap
                throw e
            }
            for (entry in entries) {
                if (entry.isDirectory) deleteTree(entry.path) else client().rm(entry.path)
            }
            client().rmdir(path)
        }
    }

    override fun close() {
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        sftp = null
        ssh = null
    }

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: NotFoundException) {
        throw e
    } catch (e: BackendException) {
        throw e
    } catch (e: Exception) {
        throw BackendException(e.message ?: e.javaClass.simpleName, e)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val IO_TIMEOUT_MS = 90_000
        private const val WRITE_AHEAD = 16
        private const val READ_AHEAD = 16
        private const val BUFFER = 64 * 1024

        @Volatile private var providerSet = false

        /** Android ships a cut-down BouncyCastle under the same name; the full one takes its place, once. */
        private fun provider() {
            if (providerSet) return
            synchronized(this) {
                if (providerSet) return
                runCatching {
                    Security.removeProvider("BC")
                    Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
                }
                providerSet = true
            }
        }

        /** "SHA256:…" of the host key, as OpenSSH prints it. */
        fun fingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }
    }
}
