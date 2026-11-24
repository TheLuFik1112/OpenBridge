package app.openbridge.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Идентичность устройства.
 *
 * Ключ EC P-256 генерируется в софте, но на диске лежит зашифрованным AES-GCM-ключом
 * из AndroidKeyStore — иначе TLS-сервер на части прошивок не поднимается с Keystore-ключом.
 * Сертификат самоподписанный; DeviceId = SHA-256(SPKI).
 */
class IdentityStore(private val context: Context) {

    @Serializable
    private data class StoredIdentity(val keyB64: String, val certB64: String)

    class Identity(val key: PrivateKey, val cert: X509Certificate) {
        val id: String get() = fingerprintOf(cert)
        val fingerprint: String get() = fingerprintOf(cert)
        val publicKeyB64: String get() = Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
        val certB64: String get() = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
    }

    private val json = Json { ignoreUnknownKeys = true }

    val identity: Identity by lazy { loadOrCreate() }

    // ── подпись / проверка ────────────────────────────────────────────────

    fun sign(data: ByteArray): String {
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(identity.key)
            update(data)
            sign()
        }
        return Base64.encodeToString(sig, Base64.NO_WRAP)
    }

    // ── TLS ──────────────────────────────────────────────────────────────

    /** KeyStore для Ktor sslConnector. */
    fun serverKeyStore(): Pair<KeyStore, CharArray> {
        val pass = "openbridge".toCharArray()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("identity", identity.key, pass, arrayOf(identity.cert))
        return ks to pass
    }

    fun serverSslContext(): SSLContext {
        val (ks, pass) = serverKeyStore()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pass)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    /**
     * Клиент: пиннинг сертификата сервера по отпечатку.
     * expected == null → первый контакт, шифрование без пиннинга (подлинность подтвердит подпись offer).
     */
    fun clientSsl(expectedFingerprint: String?): Pair<SSLSocketFactory, X509TrustManager> {
        val tm = PinnedTrustManager(expectedFingerprint)
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(tm), SecureRandom())
        }
        return ctx.socketFactory to tm
    }

    // ── создание / хранение ───────────────────────────────────────────────

    private fun loadOrCreate(): Identity {
        val file = File(context.filesDir, "identity.enc")
        if (file.exists()) {
            runCatching { return decode(file.readBytes()) }
        }
        val created = generate()
        runCatching { file.writeBytes(encode(created)) }
        return created
    }

    private fun generate(): Identity {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()
        return Identity(keyPair.private, selfSigned(keyPair))
    }

    private fun selfSigned(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val name = X500Name("CN=OpenBridge-${fingerprintOf(keyPair.public.encoded).take(12)},O=OpenBridge")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(96, SecureRandom()),
            Date(now - 86_400_000L),
            Date(now + 10L * 365 * 86_400_000L),
            name,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun encode(identity: Identity): ByteArray {
        val stored = StoredIdentity(
            keyB64 = Base64.encodeToString(identity.key.encoded, Base64.NO_WRAP),
            certB64 = Base64.encodeToString(identity.cert.encoded, Base64.NO_WRAP),
        )
        val plain = json.encodeToString(StoredIdentity.serializer(), stored).encodeToByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + cipherText
    }

    private fun decode(bytes: ByteArray): Identity {
        val ivLength = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val cipherText = bytes.copyOfRange(1 + ivLength, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(cipherText)
        val stored = json.decodeFromString(StoredIdentity.serializer(), String(plain))

        val cert = certFromB64(stored.certB64)
        val key = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(stored.keyB64, Base64.NO_WRAP)))
        return Identity(key, cert)
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(MASTER_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val MASTER_ALIAS = "openbridge.master.v1"

        fun fingerprintOf(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun fingerprintOf(cert: X509Certificate): String = fingerprintOf(cert.publicKey.encoded)

        fun sha256Hex(bytes: ByteArray): String = fingerprintOf(bytes)

        fun certFromB64(b64: String): X509Certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(Base64.decode(b64, Base64.NO_WRAP))) as X509Certificate

        fun verify(publicKeyB64: String, data: ByteArray, signatureB64: String): Boolean = runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(
                java.security.spec.X509EncodedKeySpec(Base64.decode(publicKeyB64, Base64.NO_WRAP))
            )
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(data)
                verify(Base64.decode(signatureB64, Base64.NO_WRAP))
            }
        }.getOrDefault(false)

        /** id == отпечаток ключа из сертификата, и открытый ключ совпадает с сертификатом. */
        fun isSelfConsistent(peer: PeerInfo): Boolean = runCatching {
            if (peer.certB64.isBlank() || peer.publicKeyB64.isBlank()) return false
            val cert = certFromB64(peer.certB64)
            if (peer.id != fingerprintOf(cert)) return false
            val key = KeyFactory.getInstance("EC").generatePublic(
                java.security.spec.X509EncodedKeySpec(Base64.decode(peer.publicKeyB64, Base64.NO_WRAP))
            )
            key.encoded.contentEquals(cert.publicKey.encoded)
        }.getOrDefault(false)
    }
}

class PinnedTrustManager(private val expectedFingerprint: String?) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("empty chain")
        val actual = IdentityStore.fingerprintOf(chain[0])
        if (expectedFingerprint.isNullOrBlank()) return  // первый контакт: только шифрование
        if (!actual.equals(expectedFingerprint, ignoreCase = true)) {
            throw CertificateException("certificate pin mismatch: $actual")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
