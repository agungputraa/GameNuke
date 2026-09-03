package com.neon.gametweak

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Explicit SAF backup/restore of the trusted device-control identity. The user chooses the destination file. */
object AdbIdentityBackup {
    private const val KEY_FILE = "adbkey"
    private const val CERT_FILE = "adbcert.pem"
    private const val META_FILE = "identity.txt"
    private const val META = "GAME_NUKE_ADB_IDENTITY_V1"
    private const val MAX_ENTRY = 128 * 1024

    data class Result(val success: Boolean, val message: String)

    fun exportTo(context: Context, uri: Uri): Result = runCatching {
        val dir = File(context.filesDir, "adb_keystore")
        val key = File(dir, KEY_FILE)
        val cert = File(dir, CERT_FILE)
        if (!key.isFile || !cert.isFile) return Result(false, "No saved device trust exists yet. Connect Device Control first.")
        validate(key.readBytes(), cert.readBytes())
        val out = context.contentResolver.openOutputStream(uri, "w")
            ?: return Result(false, "Unable to open selected backup destination.")
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(META_FILE, META.toByteArray(Charsets.UTF_8))
            put(KEY_FILE, key.readBytes())
            put(CERT_FILE, cert.readBytes())
        }
        Result(true, "Device trust backup created. Keep this file private; it can restore the same trusted connection.")
    }.getOrElse { Result(false, "Backup failed: ${it.message ?: it.javaClass.simpleName}") }

    fun importFrom(context: Context, uri: Uri): Result = runCatching {
        val input = context.contentResolver.openInputStream(uri)
            ?: return Result(false, "Unable to open selected backup.")
        var keyBytes: ByteArray? = null
        var certBytes: ByteArray? = null
        var meta: String? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                val bytes = readBounded(zip, MAX_ENTRY)
                when (name) {
                    META_FILE -> meta = bytes.toString(Charsets.UTF_8).trim()
                    KEY_FILE -> keyBytes = bytes
                    CERT_FILE -> certBytes = bytes
                }
                zip.closeEntry()
            }
        }
        if (meta != META) return Result(false, "This is not a valid Game Nuke device trust backup.")
        val key = keyBytes ?: return Result(false, "Backup is missing its private trust key.")
        val cert = certBytes ?: return Result(false, "Backup is missing its trust certificate.")
        validate(key, cert)

        val dir = File(context.filesDir, "adb_keystore").apply { mkdirs() }
        val keyFile = File(dir, KEY_FILE)
        val certFile = File(dir, CERT_FILE)
        val keyTmp = File(dir, "$KEY_FILE.tmp")
        val certTmp = File(dir, "$CERT_FILE.tmp")
        keyTmp.writeBytes(key)
        certTmp.writeBytes(cert)
        if (!keyTmp.renameTo(keyFile)) { keyFile.writeBytes(key); keyTmp.delete() }
        if (!certTmp.renameTo(certFile)) { certFile.writeBytes(cert); certTmp.delete() }
        AdbConnectionManager.invalidate()
        AdbManager.getInstance(context).clearAuthorizationRevokedHint()
        Result(true, "Device trust restored. Game Nuke will try to reconnect using the same trusted identity.")
    }.getOrElse { Result(false, "Restore failed: ${it.message ?: it.javaClass.simpleName}") }

    private fun validate(key: ByteArray, cert: ByteArray) {
        require(key.isNotEmpty() && key.size <= MAX_ENTRY) { "Invalid key size" }
        require(cert.isNotEmpty() && cert.size <= MAX_ENTRY) { "Invalid certificate size" }
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(key))
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream())
        val rsaPrivate = privateKey as? RSAPrivateCrtKey ?: error("ADB key is not an RSA CRT key")
        val rsaPublic = certificate.publicKey as? RSAPublicKey ?: error("ADB certificate does not contain an RSA public key")
        require(rsaPrivate.modulus == rsaPublic.modulus) { "ADB private key and certificate do not match" }
        require(rsaPublic.modulus.bitLength() >= 2048) { "ADB RSA key is too small" }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            require(total <= limit) { "Backup entry too large" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
