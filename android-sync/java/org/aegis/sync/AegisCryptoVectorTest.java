/*
 * Aegis Browser - crypto conformance test for the JAVA port (pure JVM, zero
 * dependencies).
 *
 * Java twin of android-sync/test/AegisCryptoVectorTest.kt: the SAME 16 checks
 * against the SAME generated vectors (AegisTestVectors.java), proving that
 * AegisCrypto.java reproduces the JS reference client (WebCrypto)
 * byte-for-byte - exactly like the Kotlin original already does.
 *
 * Run (any JDK 17+, no build system needed):
 *   javac -encoding UTF-8 -d out android-sync/java/org/aegis/sync/*.java
 *   java -cp out org.aegis.sync.AegisCryptoVectorTest
 *
 * Expected output ends with:  ALL 16 CHECKS PASSED
 *
 * The 600k-iteration check is the production KDF and takes a few seconds on
 * a desktop JVM. As a JUnit4 test: wrap runAll() in a @Test method (the
 * checks throw AssertionError on failure).
 */
package org.aegis.sync;

import java.nio.charset.StandardCharsets;

public final class AegisCryptoVectorTest {

    private static int passed = 0;
    private static final StringBuilder FAILURES = new StringBuilder();

    private AegisCryptoVectorTest() {}

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void check(String name, boolean cond) {
        if (!cond) {
            System.out.println("FAIL  " + name);
            FAILURES.append(name).append('\n');
            throw new AssertionError(name);
        }
        passed++;
        System.out.println("ok    " + name);
    }

    private static void checkThrows(String name, ThrowingRunnable block) {
        try {
            block.run();
        } catch (Exception expected) {
            passed++;
            System.out.println("ok    " + name + " (threw as expected)");
            return;
        }
        System.out.println("FAIL  " + name + " (expected an exception, none thrown)");
        FAILURES.append(name).append('\n');
        throw new AssertionError(name);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((Character.digit(s.charAt(i * 2), 16) << 4)
                    + Character.digit(s.charAt(i * 2 + 1), 16));
        }
        return out;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static void runAll() throws Exception {
        // 0. Base64 helpers (standard padded alphabet, same as JS btoa/atob)
        check("b64 standard alphabet",
                AegisCrypto.b64(new byte[] {0, 1, 2}).equals("AAEC"));
        check("unb64 roundtrip",
                hex(AegisCrypto.unb64(AegisCrypto.b64(new byte[] {0, 1, 2}))).equals("000102"));

        // 1. masterKey derivation, ASCII password
        check("masterKey, 600k iterations (the production KDF)",
                hex(AegisCrypto.deriveMasterKey(AegisTestVectors.pwAscii, AegisTestVectors.salt1B64, 600_000))
                        .equals(AegisTestVectors.masterKey600kHex));
        check("masterKey, 1k iterations (WebCrypto + Node cross-checked)",
                hex(AegisCrypto.deriveMasterKey(AegisTestVectors.pwAscii, AegisTestVectors.salt1B64, 1_000))
                        .equals(AegisTestVectors.masterKey1kHex));

        // 2. NON-ASCII password: the regression case for char[]-encoding bugs.
        //    A UTF-8-to-char[] PBKDF2 (the javax default) fails exactly here.
        check("masterKey, UTF-8 password (raw-byte semantics)",
                hex(AegisCrypto.deriveMasterKey(AegisTestVectors.pwUtf8, AegisTestVectors.salt2B64, 1_000))
                        .equals(AegisTestVectors.masterKeyUtf1kHex));

        // 3. HKDF split with the zero-filled default salt
        //    (= WebCrypto's empty salt, since HMAC zero-pads keys to 64 bytes)
        byte[] mk = AegisCrypto.deriveMasterKey(AegisTestVectors.pwAscii, AegisTestVectors.salt1B64, 1_000);
        check("hkdf -> authKey",
                hex(AegisCrypto.hkdf(mk, utf8(AegisCrypto.AUTH_INFO))).equals(AegisTestVectors.hkdfAuthHex));
        check("hkdf -> kek",
                hex(AegisCrypto.hkdf(mk, utf8(AegisCrypto.KEK_INFO))).equals(AegisTestVectors.hkdfKekHex));

        // 4. Login verifier over random authKey bytes (must match the JS client)
        check("verifier, 100k iterations",
                hex(AegisCrypto.computeVerifier(unhex(AegisTestVectors.authKeyHex)))
                        .equals(AegisTestVectors.verifierHex));

        // 5. Full chain (deriveCredentials shape used by register/login)
        AegisCrypto.Creds creds =
                AegisCrypto.deriveCredentials(AegisTestVectors.pwAscii, AegisTestVectors.salt1B64, 1_000);
        check("deriveCredentials -> verifier",
                hex(creds.verifier).equals(AegisTestVectors.chainVerifier1kHex));
        check("deriveCredentials -> kek",
                hex(creds.kek).equals(AegisTestVectors.hkdfKekHex));

        // 6. Vault key wrap / unwrap under the KEK
        byte[] unwrapped = AegisCrypto.aesGcmDecrypt(
                unhex(AegisTestVectors.hkdfKekHex),
                unhex(AegisTestVectors.wrapNonceHex),
                unhex(AegisTestVectors.wrappedVaultKeyHex));
        check("unwrap vault key (WebCrypto-encrypted vector)",
                hex(unwrapped).equals(AegisTestVectors.vaultKeyHex));
        AegisCrypto.GcmResult rewrapped = AegisCrypto.aesGcmEncrypt(unhex(AegisTestVectors.hkdfKekHex), unwrapped);
        check("wrap/unwrap roundtrip (random nonce)",
                hex(AegisCrypto.aesGcmDecrypt(
                        unhex(AegisTestVectors.hkdfKekHex), rewrapped.nonce, rewrapped.ciphertext))
                        .equals(AegisTestVectors.vaultKeyHex));

        // 7. Item encryption with the AAD binding type|key|version
        byte[] aad = utf8(AegisTestVectors.itemType + "|" + AegisTestVectors.itemKey
                + "|" + AegisTestVectors.itemVersion);
        byte[] plain = AegisCrypto.aesGcmDecrypt(
                unhex(AegisTestVectors.vaultKeyHex),
                unhex(AegisTestVectors.itemNonceHex),
                unhex(AegisTestVectors.itemCiphertextHex),
                aad);
        check("item decrypt (AAD-bound WebCrypto vector)",
                new String(plain, StandardCharsets.UTF_8).equals(AegisTestVectors.plaintextJson));
        AegisCrypto.GcmResult reencrypted = AegisCrypto.aesGcmEncrypt(unhex(AegisTestVectors.vaultKeyHex), plain, aad);
        check("item roundtrip (random nonce)",
                new String(AegisCrypto.aesGcmDecrypt(
                        unhex(AegisTestVectors.vaultKeyHex), reencrypted.nonce, reencrypted.ciphertext, aad),
                        StandardCharsets.UTF_8).equals(AegisTestVectors.plaintextJson));

        // 8. Tamper resistance: wrong key and wrong AAD must both fail
        checkThrows("decrypt with wrong key throws", () -> AegisCrypto.aesGcmDecrypt(
                unhex(AegisTestVectors.vaultKeyHex),
                unhex(AegisTestVectors.wrapNonceHex),
                unhex(AegisTestVectors.wrappedVaultKeyHex)));
        checkThrows("decrypt with tampered AAD (version bumped) throws", () -> AegisCrypto.aesGcmDecrypt(
                unhex(AegisTestVectors.vaultKeyHex),
                unhex(AegisTestVectors.itemNonceHex),
                unhex(AegisTestVectors.itemCiphertextHex),
                utf8(AegisTestVectors.itemType + "|" + AegisTestVectors.itemKey
                        + "|" + (AegisTestVectors.itemVersion + 1))));

        System.out.println();
        System.out.println("ALL " + passed + " CHECKS PASSED");
    }

    public static void main(String[] args) {
        try {
            runAll();
        } catch (AssertionError e) {
            System.out.println("FAILED: " + FAILURES.toString().trim());
            System.exit(1);
        } catch (Exception e) {
            System.out.println("UNEXPECTED " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
