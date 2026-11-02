/*
 * Aegis Browser - client-side crypto primitives (pure JVM, zero dependencies).
 *
 * Byte-exact mirror of the crypto in aegis-sync/client-example/aegis-client.mjs
 * (WebCrypto). Conformance is enforced by android-sync/test/: the generated
 * vectors are cross-checked at generation time against WebCrypto AND Node's
 * native PBKDF2 (scripts/gen-crypto-vectors.mjs), and the JVM test in this
 * folder proves the Kotlin code reproduces them byte-for-byte.
 *
 * KEY HIERARCHY (identical to the JS reference):
 *   password + kdf_salt --PBKDF2-SHA256 x600k--> masterKey (32 B)   [never leaves device]
 *   masterKey --HKDF-SHA256 info="aegis-auth-v1"--> authKey         [never leaves device]
 *   authKey  --PBKDF2-SHA256 "aegis-verifier-v1" x100k--> verifier  [sent over TLS at login only]
 *   masterKey --HKDF-SHA256 info="aegis-kek-v1"--> kek              [never leaves device]
 *   vaultKey (random 32 B) --AES-256-GCM(kek)--> wrapped_vault_key  [stored on server]
 *   every item --AES-256-GCM(vaultKey, aad="type|key|version")--> ciphertext
 *
 * WHY HAND-ROLLED PBKDF2/HKDF (instead of javax "PBKDF2WithHmacSHA256")
 *   WebCrypto PBKDF2 takes the password as RAW BYTES. javax.crypto's
 *   "PBKDF2WithHmacSHA256" takes a char[] and every provider converts those
 *   chars to bytes with UTF-8, which is NOT the identity map for bytes
 *   >= 0x80. The login verifier is PBKDF2 over 32 random authKey bytes, so a
 *   char[]-based implementation derives a DIFFERENT verifier on Android than
 *   the JS reference does - accounts registered from one client could not
 *   log in from the other. RFC 2898 over raw bytes removes both the
 *   ambiguity and the provider dependency (Android's bundled BouncyCastle
 *   has no raw-byte ("And8BIT") SHA-256 PBKDF2). HKDF is hand-rolled for the
 *   same reason: Android ships no public HKDF API. Both fit in ~25 lines and
 *   are validated against three independent implementations by the vector
 *   test in android-sync/test/.
 *
 * Performance: the full 600k-iteration chain runs in ~1-2 s on current
 * phones (one HmacSHA256 Mac instance is reused across all iterations).
 */
package org.aegis.sync

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AegisCrypto {
    const val DEFAULT_ITERATIONS = 600_000
    const val VERIFIER_ITERATIONS = 100_000
    const val VERIFIER_INFO = "aegis-verifier-v1"
    const val AUTH_INFO = "aegis-auth-v1"
    const val KEK_INFO = "aegis-kek-v1"
    const val KDF_ALGO = "PBKDF2-SHA256"

    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    // -------------------------------------------------------------- PBKDF2

    /**
     * RFC 2898 PBKDF2-HMAC-SHA256 with a RAW-BYTE password (WebCrypto
     * semantics). Returns dkLen bytes (default 32 = 256 bits).
     */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int = 32): ByteArray {
        require(iterations >= 1) { "iterations must be >= 1" }
        require(dkLen >= 1) { "dkLen must be >= 1" }
        val hLen = 32
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val out = ByteArray(dkLen)
        var copied = 0
        var block = 1
        while (copied < dkLen) {
            val bi = byteArrayOf(
                ((block ushr 24) and 0xFF).toByte(),
                ((block ushr 16) and 0xFF).toByte(),
                ((block ushr 8) and 0xFF).toByte(),
                (block and 0xFF).toByte(),
            )
            var u = mac.doFinal(salt + bi)
            val t = u.copyOf()
            for (i in 2..iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(hLen, dkLen - copied)
            System.arraycopy(t, 0, out, copied, n)
            copied += n
            block++
        }
        return out
    }

    /** masterKey = PBKDF2-SHA256(utf8(password), kdf_salt, iterations) -> 32 B */
    fun deriveMasterKey(password: String, kdfSaltB64: String, iterations: Int = DEFAULT_ITERATIONS): ByteArray =
        pbkdf2(utf8(password), unb64(kdfSaltB64), iterations)

    // --------------------------------------------------------------- HKDF

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * RFC 5869 HKDF-SHA256 (extract + expand). The JS reference uses an EMPTY
     * salt; HMAC zero-pads any key to 64 bytes, so an empty salt and a
     * 32-byte zero-filled salt produce identical output - the zero-filled
     * default here is byte-equivalent to the WebCrypto call.
     */
    fun hkdf(ikm: ByteArray, info: ByteArray, salt: ByteArray = ByteArray(32), length: Int = 32): ByteArray {
        require(length >= 1) { "length must be >= 1" }
        val prk = hmacSha256(salt, ikm)
        var t = ByteArray(0)
        val chunks = ArrayList<ByteArray>()
        var i = 1
        while (chunks.sumOf { it.size } < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
            chunks.add(t)
            i++
        }
        return chunks.reduce { a, b -> a + b }.copyOf(length)
    }

    // ------------------------------------------------------------ verifier

    /** verifier = PBKDF2-SHA256(authKey as RAW bytes, "aegis-verifier-v1", 100k) */
    fun computeVerifier(authKey: ByteArray): ByteArray =
        pbkdf2(authKey, utf8(VERIFIER_INFO), VERIFIER_ITERATIONS)

    /** password -> (verifier, kek, masterKey); mirrors deriveCredentials() in the JS client. */
    fun deriveCredentials(
        password: String,
        kdfSaltB64: String,
        iterations: Int = DEFAULT_ITERATIONS,
    ): Triple<ByteArray, ByteArray, ByteArray> {
        val masterKey = deriveMasterKey(password, kdfSaltB64, iterations)
        val verifier = computeVerifier(hkdf(masterKey, utf8(AUTH_INFO)))
        val kek = hkdf(masterKey, utf8(KEK_INFO))
        return Triple(verifier, kek, masterKey)
    }

    // ----------------------------------------------------------- vault key

    fun generateVaultKey(): ByteArray = randomBytes(32)

    // ------------------------------------------------------------ AES-GCM

    /**
     * AES-256-GCM. Returns (nonce, ciphertext||tag) - exactly the layout
     * WebCrypto produces (128-bit tag appended to the ciphertext).
     */
    fun aesGcmEncrypt(
        key: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray? = null,
        nonce: ByteArray = randomBytes(12),
    ): Pair<ByteArray, ByteArray> {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad != null) cipher.updateAAD(aad)
        return nonce to cipher.doFinal(plaintext)
    }

    /**
     * AES-256-GCM decrypt. Throws (AEADBadTagException) on a wrong key,
     * nonce, or tampered AAD - the same failure WebCrypto reports.
     */
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}
