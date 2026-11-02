/*
 * Aegis Browser - crypto conformance test (pure JVM, zero dependencies).
 *
 * Proves that AegisCrypto.kt reproduces the JS reference client (WebCrypto)
 * byte-for-byte, using the generated vectors in AegisCryptoVectors.kt.
 * Those vectors were cross-checked at generation time against THREE
 * independent implementations: WebCrypto, Node's native PBKDF2, and a
 * literal mirror of the hand-rolled RFC 2898 loop in AegisCrypto.kt.
 *
 * Run (any JDK, no build system needed):
 *   kotlinc android-sync/AegisCrypto.kt \
 *           android-sync/test/AegisCryptoVectors.kt \
 *           android-sync/test/AegisCryptoVectorTest.kt \
 *           -include-runtime -d aegis-crypto-test.jar
 *   java -jar aegis-crypto-test.jar
 *
 * Expected output ends with:  ALL <N> CHECKS PASSED
 *
 * As a JUnit4 test: wrap runAll() in a @Test method (the checks throw
 * AssertionError on failure). The 600k-iteration check is the production
 * KDF and takes a few seconds on a desktop JVM.
 */
package org.aegis.sync

import kotlin.system.exitProcess

private var passed = 0

private fun check(name: String, cond: Boolean) {
    if (!cond) {
        println("FAIL  $name")
        throw AssertionError(name)
    }
    passed++
    println("ok    $name")
}

private fun checkThrows(name: String, block: () -> Unit) {
    try {
        block()
    } catch (_: Exception) {
        passed++
        println("ok    $name (threw as expected)")
        return
    }
    println("FAIL  $name (expected an exception, none thrown)")
    throw AssertionError(name)
}

private fun hex(b: ByteArray): String =
    b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

private fun unhex(s: String): ByteArray =
    ByteArray(s.length / 2) { i ->
        ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
    }

fun runAll() {
    val v = AegisTestVectors

    // 0. Base64 helpers (standard padded alphabet, same as JS btoa/atob)
    check("b64 standard alphabet", AegisCrypto.b64(byteArrayOf(0, 1, 2)) == "AAEC")
    check("unb64 roundtrip", hex(AegisCrypto.unb64(AegisCrypto.b64(byteArrayOf(0, 1, 2)))) == "000102")

    // 1. masterKey derivation, ASCII password
    check(
        "masterKey, 600k iterations (the production KDF)",
        hex(AegisCrypto.deriveMasterKey(v.pwAscii, v.salt1B64, 600_000)) == v.masterKey600kHex
    )
    check(
        "masterKey, 1k iterations (WebCrypto + Node cross-checked)",
        hex(AegisCrypto.deriveMasterKey(v.pwAscii, v.salt1B64, 1_000)) == v.masterKey1kHex
    )

    // 2. NON-ASCII password: the regression case for char[]-encoding bugs.
    //    A UTF-8-to-char[] PBKDF2 (the javax default) fails exactly here.
    check(
        "masterKey, UTF-8 password (raw-byte semantics)",
        hex(AegisCrypto.deriveMasterKey(v.pwUtf8, v.salt2B64, 1_000)) == v.masterKeyUtf1kHex
    )

    // 3. HKDF split with the zero-filled default salt
    //    (= WebCrypto's empty salt, since HMAC zero-pads keys to 64 bytes)
    val mk = AegisCrypto.deriveMasterKey(v.pwAscii, v.salt1B64, 1_000)
    check("hkdf -> authKey", hex(AegisCrypto.hkdf(mk, AegisCrypto.AUTH_INFO.toByteArray())) == v.hkdfAuthHex)
    check("hkdf -> kek", hex(AegisCrypto.hkdf(mk, AegisCrypto.KEK_INFO.toByteArray())) == v.hkdfKekHex)

    // 4. Login verifier over random authKey bytes (must match the JS client)
    check(
        "verifier, 100k iterations",
        hex(AegisCrypto.computeVerifier(unhex(v.authKeyHex))) == v.verifierHex
    )

    // 5. Full chain (deriveCredentials shape used by register/login)
    val (verifier, kek, _) = AegisCrypto.deriveCredentials(v.pwAscii, v.salt1B64, 1_000)
    check("deriveCredentials -> verifier", hex(verifier) == v.chainVerifier1kHex)
    check("deriveCredentials -> kek", hex(kek) == v.hkdfKekHex)

    // 6. Vault key wrap / unwrap under the KEK
    val unwrapped = AegisCrypto.aesGcmDecrypt(
        unhex(v.hkdfKekHex), unhex(v.wrapNonceHex), unhex(v.wrappedVaultKeyHex)
    )
    check("unwrap vault key (WebCrypto-encrypted vector)", hex(unwrapped) == v.vaultKeyHex)
    val (nonce2, wrapped2) = AegisCrypto.aesGcmEncrypt(unhex(v.hkdfKekHex), unwrapped)
    check(
        "wrap/unwrap roundtrip (random nonce)",
        hex(AegisCrypto.aesGcmDecrypt(unhex(v.hkdfKekHex), nonce2, wrapped2)) == v.vaultKeyHex
    )

    // 7. Item encryption with the AAD binding type|key|version
    val aad = "${v.itemType}|${v.itemKey}|${v.itemVersion}".toByteArray()
    val plain = AegisCrypto.aesGcmDecrypt(
        unhex(v.vaultKeyHex), unhex(v.itemNonceHex), unhex(v.itemCiphertextHex), aad
    )
    check("item decrypt (AAD-bound WebCrypto vector)", String(plain, Charsets.UTF_8) == v.plaintextJson)
    val (nonce3, ct3) = AegisCrypto.aesGcmEncrypt(unhex(v.vaultKeyHex), plain, aad)
    check(
        "item roundtrip (random nonce)",
        String(AegisCrypto.aesGcmDecrypt(unhex(v.vaultKeyHex), nonce3, ct3, aad), Charsets.UTF_8) == v.plaintextJson
    )

    // 8. Tamper resistance: wrong key and wrong AAD must both fail
    checkThrows("decrypt with wrong key throws") {
        AegisCrypto.aesGcmDecrypt(unhex(v.vaultKeyHex), unhex(v.wrapNonceHex), unhex(v.wrappedVaultKeyHex))
    }
    checkThrows("decrypt with tampered AAD (version bumped) throws") {
        AegisCrypto.aesGcmDecrypt(
            unhex(v.vaultKeyHex), unhex(v.itemNonceHex), unhex(v.itemCiphertextHex),
            "${v.itemType}|${v.itemKey}|${v.itemVersion + 1}".toByteArray()
        )
    }

    println()
    println("ALL $passed CHECKS PASSED")
}

fun main() {
    try {
        runAll()
    } catch (_: AssertionError) {
        exitProcess(1)
    }
}
