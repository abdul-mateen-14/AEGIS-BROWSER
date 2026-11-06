// Copyright 2026 The Aegis Browser Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

/*
 * Aegis Browser - client-side crypto primitives (pure JVM, zero dependencies).
 *
 * JAVA PORT of AegisCrypto.kt. Both implementations are byte-exact mirrors of
 * the crypto in aegis-sync/client-example/aegis-client.mjs (WebCrypto) and are
 * validated against the SAME generated vectors (see AegisTestVectors.java and
 * the .kt twin in android-sync/test/). The Java variant exists because
 * Chromium's chrome_java targets are Java-first: adding Kotlin to the browser
 * tree would also require new BUILD.gn wiring, while a plain .java file
 * integrates with a one-line patch.
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
 *   the JS reference does - accounts registered from one client could not log
 *   in from the other. RFC 2898 over raw bytes removes both the ambiguity and
 *   the provider dependency. HKDF is hand-rolled for the same reason: Android
 *   ships no public HKDF API. Both fit in ~25 lines and are validated against
 *   three independent implementations by the vector test.
 *
 * Failure model: operational failures (bad tag = wrong key/nonce/tampered
 * AAD, missing algorithm) are reported as the unchecked
 * AegisCryptoException - callers cannot meaningfully recover from a corrupted
 * vault, and checked exceptions in the browser UI would be noise. The cause
 * field preserves the original AEADBadTagException for diagnostics.
 *
 * Performance: the full 600k-iteration chain runs in ~1-2 s on current
 * phones (one HmacSHA256 Mac instance is reused across all iterations).
 */
package org.chromium.chrome.browser.aegis.sync;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AegisCrypto {

    public static final int DEFAULT_ITERATIONS = 600_000;
    public static final int VERIFIER_ITERATIONS = 100_000;
    public static final String VERIFIER_INFO = "aegis-verifier-v1";
    public static final String AUTH_INFO = "aegis-auth-v1";
    public static final String KEK_INFO = "aegis-kek-v1";
    public static final String KDF_ALGO = "PBKDF2-SHA256";

    private static final SecureRandom RNG = new SecureRandom();
    private static final int HLEN = 32; // SHA-256 output length

    private AegisCrypto() {} // static utility class

    /** Unchecked wrapper for crypto failures that callers cannot fix at runtime. */
    public static final class AegisCryptoException extends RuntimeException {
        public AegisCryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------- encoding

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    /** Standard padded Base64 - same alphabet as JS btoa/atob. */
    public static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] unb64(String s) {
        return Base64.getDecoder().decode(s);
    }

    public static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // --------------------------------------------------------------- PBKDF2

    /**
     * RFC 2898 PBKDF2-HMAC-SHA256 with a RAW-BYTE password (WebCrypto
     * semantics). Returns dkLen bytes (the protocol uses 32 = 256 bits).
     */
    public static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int dkLen) {
        if (iterations < 1) throw new IllegalArgumentException("iterations must be >= 1");
        if (dkLen < 1) throw new IllegalArgumentException("dkLen must be >= 1");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password, "HmacSHA256"));
            byte[] out = new byte[dkLen];
            int copied = 0;
            int block = 1;
            while (copied < dkLen) {
                byte[] bi = new byte[] {
                    (byte) ((block >>> 24) & 0xFF),
                    (byte) ((block >>> 16) & 0xFF),
                    (byte) ((block >>> 8) & 0xFF),
                    (byte) (block & 0xFF),
                };
                byte[] u = mac.doFinal(concat(salt, bi));
                byte[] t = u.clone();
                for (int i = 2; i <= iterations; i++) {
                    u = mac.doFinal(u);
                    for (int j = 0; j < t.length; j++) t[j] ^= u[j];
                }
                int n = Math.min(HLEN, dkLen - copied);
                System.arraycopy(t, 0, out, copied, n);
                copied += n;
                block++;
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new AegisCryptoException("HmacSHA256 unavailable on this JVM/Android", e);
        }
    }

    public static byte[] pbkdf2(byte[] password, byte[] salt, int iterations) {
        return pbkdf2(password, salt, iterations, 32);
    }

    /** masterKey = PBKDF2-SHA256(utf8(password), kdf_salt, iterations) -> 32 B */
    public static byte[] deriveMasterKey(String password, String kdfSaltB64, int iterations) {
        return pbkdf2(utf8(password), unb64(kdfSaltB64), iterations);
    }

    // ----------------------------------------------------------------- HKDF

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new AegisCryptoException("HmacSHA256 unavailable on this JVM/Android", e);
        }
    }

    /**
     * RFC 5869 HKDF-SHA256 (extract + expand). The JS reference uses an EMPTY
     * salt; HMAC zero-pads any key to 64 bytes, so an empty salt and a
     * 32-byte zero-filled salt produce identical output - the zero-filled
     * default here is byte-equivalent to the WebCrypto call.
     */
    public static byte[] hkdf(byte[] ikm, byte[] info, byte[] salt, int length) {
        if (length < 1) throw new IllegalArgumentException("length must be >= 1");
        byte[] prk = hmacSha256(salt, ikm);
        byte[] t = new byte[0];
        java.util.List<byte[]> chunks = new java.util.ArrayList<>();
        int i = 1;
        int total = 0;
        while (total < length) {
            t = hmacSha256(prk, concat(concat(t, info), new byte[] {(byte) i}));
            chunks.add(t);
            total += t.length;
            i++;
        }
        byte[] out = new byte[length];
        int off = 0;
        for (byte[] c : chunks) {
            int n = Math.min(c.length, length - off);
            System.arraycopy(c, 0, out, off, n);
            off += n;
        }
        return out;
    }

    public static byte[] hkdf(byte[] ikm, byte[] info) {
        return hkdf(ikm, info, new byte[32], 32);
    }

    // ------------------------------------------------------------- verifier

    /** verifier = PBKDF2-SHA256(authKey as RAW bytes, "aegis-verifier-v1", 100k) */
    public static byte[] computeVerifier(byte[] authKey) {
        return pbkdf2(authKey, utf8(VERIFIER_INFO), VERIFIER_ITERATIONS);
    }

    /**
     * password -> (verifier, kek, masterKey); mirrors deriveCredentials() in
     * the JS client.
     */
    public static Creds deriveCredentials(String password, String kdfSaltB64, int iterations) {
        byte[] masterKey = deriveMasterKey(password, kdfSaltB64, iterations);
        byte[] verifier = computeVerifier(hkdf(masterKey, utf8(AUTH_INFO)));
        byte[] kek = hkdf(masterKey, utf8(KEK_INFO));
        return new Creds(verifier, kek, masterKey);
    }

    public static Creds deriveCredentials(String password, String kdfSaltB64) {
        return deriveCredentials(password, kdfSaltB64, DEFAULT_ITERATIONS);
    }

    /** Result triple of deriveCredentials (Java has no built-in Triple). */
    public static final class Creds {
        public final byte[] verifier;
        public final byte[] kek;
        public final byte[] masterKey;

        public Creds(byte[] verifier, byte[] kek, byte[] masterKey) {
            this.verifier = verifier;
            this.kek = kek;
            this.masterKey = masterKey;
        }
    }

    // ------------------------------------------------------------ vault key

    public static byte[] generateVaultKey() {
        return randomBytes(32);
    }

    // -------------------------------------------------------------- AES-GCM

    /** Result of an AES-GCM encryption: the nonce used and ciphertext||tag. */
    public static final class GcmResult {
        public final byte[] nonce;
        public final byte[] ciphertext; // includes the 128-bit tag, WebCrypto layout

        public GcmResult(byte[] nonce, byte[] ciphertext) {
            this.nonce = nonce;
            this.ciphertext = ciphertext;
        }
    }

    /**
     * AES-256-GCM. Returns the nonce and ciphertext||tag - exactly the
     * layout WebCrypto produces (128-bit tag appended to the ciphertext).
     */
    public static GcmResult aesGcmEncrypt(byte[] key, byte[] plaintext, byte[] aad, byte[] nonce) {
        if (key.length != 32) throw new IllegalArgumentException("AES-256 key must be 32 bytes");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            if (aad != null) cipher.updateAAD(aad);
            return new GcmResult(nonce, cipher.doFinal(plaintext));
        } catch (GeneralSecurityException e) {
            throw new AegisCryptoException("AES-GCM encryption failed", e);
        }
    }

    public static GcmResult aesGcmEncrypt(byte[] key, byte[] plaintext, byte[] aad) {
        return aesGcmEncrypt(key, plaintext, aad, randomBytes(12));
    }

    public static GcmResult aesGcmEncrypt(byte[] key, byte[] plaintext) {
        return aesGcmEncrypt(key, plaintext, null, randomBytes(12));
    }

    /**
     * AES-256-GCM decrypt. Throws AegisCryptoException (cause
     * AEADBadTagException) on a wrong key, nonce, or tampered AAD - the same
     * failure WebCrypto reports.
     */
    public static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) {
        if (key.length != 32) throw new IllegalArgumentException("AES-256 key must be 32 bytes");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            if (aad != null) cipher.updateAAD(aad);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new AegisCryptoException(
                    "AES-GCM decryption failed (wrong key, nonce, or tampered AAD)", e);
        }
    }

    public static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext) {
        return aesGcmDecrypt(key, nonce, ciphertext, null);
    }

    // -------------------------------------------------------------- helpers

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
