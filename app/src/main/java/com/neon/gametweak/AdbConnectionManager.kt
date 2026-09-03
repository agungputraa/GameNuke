package com.neon.gametweak

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.util.Date
import java.util.concurrent.TimeUnit

class AdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val privateKeyVal: PrivateKey
    private val certificateVal: Certificate

    init {
        api = Build.VERSION.SDK_INT
        // Prevent an unavailable/stale ADB endpoint from holding the engine lock forever.
        setTimeout(6_000L, TimeUnit.MILLISECONDS)

        val store = KeyStoreHelper.loadOrCreate(context)
        privateKeyVal = store.privateKey
        certificateVal = store.certificate
    }

    override fun getPrivateKey(): PrivateKey = privateKeyVal
    override fun getCertificate(): Certificate = certificateVal
    override fun getDeviceName(): String = "GameNuke"

    companion object {
        @Volatile
        private var instance: AdbConnectionManager? = null

        @Throws(Exception::class)
        fun getInstance(context: Context): AdbConnectionManager =
            instance ?: synchronized(this) {
                instance ?: AdbConnectionManager(context.applicationContext).also { instance = it }
            }

        /** Drop the in-process key holder after an explicit SAF restore so the next connection uses it. */
        fun invalidate() {
            synchronized(this) { instance = null }
        }
    }

    private data class KeyMaterial(val privateKey: PrivateKey, val certificate: Certificate)

    private object KeyStoreHelper {
        private const val KEY_FILE = "adbkey"
        private const val CERT_FILE = "adbcert.pem"
        private const val KEY_SIZE = 2048
        private const val SUBJECT = "CN=GameNuke"
        private const val ALGORITHM = "SHA512withRSA"
        private const val VALIDITY_MS = 10L * 365L * 24L * 60L * 60L * 1000L

        fun loadOrCreate(context: Context): KeyMaterial {
            val dir = File(context.filesDir, "adb_keystore").apply { if (!exists()) mkdirs() }
            val keyFile = File(dir, KEY_FILE)
            val certFile = File(dir, CERT_FILE)

            return runCatching { load(keyFile, certFile) }.getOrNull()
                ?: generate(keyFile, certFile)
        }

        private fun load(keyFile: File, certFile: File): KeyMaterial {
            if (!keyFile.exists() || !certFile.exists()) error("key material missing")
            val keyBytes = keyFile.readBytes()
            val privateKey = java.security.KeyFactory.getInstance("RSA")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))
            val cert = certFile.inputStream().use { input ->
                java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input)
            }
            val rsaPrivate = privateKey as? RSAPrivateCrtKey ?: error("ADB private key is not RSA")
            val rsaPublic = cert.publicKey as? RSAPublicKey ?: error("ADB certificate public key is not RSA")
            require(rsaPrivate.modulus.bitLength() >= KEY_SIZE) { "ADB RSA key is too small" }
            require(rsaPrivate.modulus == rsaPublic.modulus) { "ADB key/certificate identity mismatch" }
            return KeyMaterial(privateKey, cert)
        }

        private fun generate(keyFile: File, certFile: File): KeyMaterial {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(KEY_SIZE, SecureRandom())
            val keyPair: KeyPair = generator.generateKeyPair()
            val publicKey: PublicKey = keyPair.public
            val privateKey: PrivateKey = keyPair.private

            val cert = buildCertificate(publicKey, privateKey)

            runCatching { keyFile.writeBytes(privateKey.encoded) }
            runCatching {
                certFile.outputStream().use { out -> out.write(cert.encoded) }
            }
            return KeyMaterial(privateKey, cert)
        }

        private fun buildCertificate(publicKey: PublicKey, privateKey: PrivateKey): Certificate {
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + VALIDITY_MS)
            val x500Name = X500Name(SUBJECT)
            val serial = BigInteger.valueOf(System.currentTimeMillis())

            val builder = JcaX509v3CertificateBuilder(
                x500Name, serial, notBefore, notAfter, x500Name, publicKey
            )
            val extUtils = JcaX509ExtensionUtils()
            builder.addExtension(
                Extension.subjectKeyIdentifier, false,
                extUtils.createSubjectKeyIdentifier(publicKey)
            )

            val signer = JcaContentSignerBuilder(ALGORITHM).build(privateKey)
            return JcaX509CertificateConverter().getCertificate(builder.build(signer))
        }
    }
}
