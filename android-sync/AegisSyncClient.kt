/*
 * Aegis Browser - Android sync client (protocol layer).
 *
 * Faithful Kotlin port of the API half of
 * aegis-sync/client-example/aegis-client.mjs. Endpoints, request/response
 * field names, refresh-token retry and the vault-key unwrap path were
 * verified 1:1 against aegis-sync/src/auth.ts and src/sync.ts.
 *
 * Crypto lives in AegisCrypto.kt (pure JVM, same package) and is
 * conformance-tested against generated vectors cross-checked with WebCrypto
 * and Node:  android-sync/test/AegisCryptoVectors.kt + AegisCryptoVectorTest.kt
 *
 * STATUS: crypto = vector-tested; protocol = mirrors the 55-test-passing JS
 * client but has not yet run against a deployed worker (Phase 3 of the free
 * build plan, section 6.2, describes the live E2E test sequence).
 *
 * Dependencies: org.json only (bundled with Android). To compile/test this
 * file on a desktop JVM add org.json:json to the classpath; AegisCrypto.kt
 * alone has zero dependencies.
 */
package org.aegis.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

// ---------------------------------------------------------------------------
// API client (mirror of class AegisClient in aegis-client.mjs)
// ---------------------------------------------------------------------------

class AegisApiException(val status: Int, val code: String?, message: String) : Exception(message)

/** One local change queued for push. */
data class Change(
    val type: String,          // e.g. "bookmark", "tab", "setting", "password", "autofill"
    val key: String,           // stable item key
    val baseVersion: Long,
    val deleted: Boolean = false,
    val plaintext: JSONObject? = null
)

/** One pulled item (already decrypted). */
data class SyncItem(
    val type: String,
    val key: String,
    val version: Long,
    val deleted: Boolean,
    val plaintext: JSONObject?
)

class AegisSyncClient(
    baseUrl: String,
    private val deviceId: String,      // stable UUID generated once per install
    private val deviceName: String,
    private val devicePlatform: String  // e.g. "android"
) {
    private val base = baseUrl.trimEnd('/')
    var accessToken: String? = null; private set
    var refreshToken: String? = null; private set
    var vaultKey: ByteArray? = null; private set
    var user: JSONObject? = null; private set

    // ------------------------------------------------------------------ HTTP

    private fun http(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        auth: Boolean = false,
        retry: Boolean = true
    ): JSONObject {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            if (auth) accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
            doOutput = body != null
        }
        body?.let { conn.outputStream.use { it.write(body.toString().toByteArray()) } }
        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.let { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } ?: ""
        conn.disconnect()

        val data = if (text.isBlank()) JSONObject() else JSONObject(text)

        if (status == 401 && auth && retry && refreshToken != null) {
            if (refreshTokens()) {
                return http(path, method, body, auth, retry = false)
            }
        }
        if (status !in 200..299) {
            throw AegisApiException(
                status,
                data.optJSONObject("error")?.optString("code"),
                data.optJSONObject("error")?.optString("message") ?: "HTTP $status"
            )
        }
        return data
    }

    private fun refreshTokens(): Boolean {
        val rt = refreshToken ?: return false
        return try {
            val data = http("/v1/auth/refresh", "POST", JSONObject().put("refresh_token", rt))
            accessToken = data.getString("access_token")
            refreshToken = data.getString("refresh_token")
            true
        } catch (_: Exception) {
            accessToken = null; refreshToken = null; false
        }
    }

    // ----------------------------------------------------------------- Auth

    /** Creates a new account. KDF salt + vault key are generated on-device. */
    fun register(email: String, password: String, inviteCode: String? = null): JSONObject {
        val kdfSaltB64 = AegisCrypto.b64(AegisCrypto.randomBytes(16))
        val (verifier, kek, _) = AegisCrypto.deriveCredentials(password, kdfSaltB64)
        val vaultKeyLocal = AegisCrypto.generateVaultKey()
        val (nonce, wrapped) = AegisCrypto.aesGcmEncrypt(kek, vaultKeyLocal)

        val body = JSONObject()
            .put("email", email)
            .put("kdf_salt_b64", kdfSaltB64)
            .put("kdf", JSONObject().put("algo", AegisCrypto.KDF_ALGO).put("iterations", AegisCrypto.DEFAULT_ITERATIONS))
            .put("verifier_b64", AegisCrypto.b64(verifier))
            .put("wrapped_vault_key_b64", AegisCrypto.b64(wrapped))
            .put("vault_nonce_b64", AegisCrypto.b64(nonce))
        inviteCode?.let { body.put("invite_code", it) }
        return http("/v1/auth/register", "POST", body)
    }

    /**
     * Logs in, unlocks the vault key in memory, registers/updates this device.
     * Two steps: fetch public KDF params (salt), derive the key chain ONCE,
     * then send the verifier.
     */
    fun login(email: String, password: String): JSONObject {
        val params = http("/v1/auth/kdf-params?email=${java.net.URLEncoder.encode(email, "UTF-8")}")
        val kdf = params.getJSONObject("kdf")
        val (verifier, kek, _) = AegisCrypto.deriveCredentials(password, params.getString("kdf_salt_b64"), kdf.getInt("iterations"))

        val data = http(
            "/v1/auth/login", "POST",
            JSONObject()
                .put("email", email)
                .put("verifier_b64", AegisCrypto.b64(verifier))
                .put("device", JSONObject()
                    .put("id", deviceId)
                    .put("name", deviceName)
                    .put("platform", devicePlatform))
        )
        accessToken = data.getString("access_token")
        refreshToken = data.getString("refresh_token")
        user = data.getJSONObject("user")

        // Server-confirmed params must match what we derived with; if the
        // account was migrated to new KDF params, re-derive before unwrapping.
        val u = user!!
        val userKdf = u.getJSONObject("kdf")
        val vaultKeyLocal: ByteArray
        if (userKdf.optString("algo") != kdf.optString("algo") ||
            userKdf.getInt("iterations") != kdf.getInt("iterations")
        ) {
            val (_, kek2, _) = AegisCrypto.deriveCredentials(
                password, u.getString("kdf_salt_b64"), userKdf.getInt("iterations")
            )
            vaultKeyLocal = AegisCrypto.aesGcmDecrypt(
                kek2,
                AegisCrypto.unb64(u.getString("vault_nonce_b64")),
                AegisCrypto.unb64(u.getString("wrapped_vault_key_b64"))
            )
        } else {
            vaultKeyLocal = AegisCrypto.aesGcmDecrypt(
                kek,
                AegisCrypto.unb64(u.getString("vault_nonce_b64")),
                AegisCrypto.unb64(u.getString("wrapped_vault_key_b64"))
            )
        }
        vaultKey = vaultKeyLocal
        return u
    }

    /** Rotates the password: re-derives keys, re-wraps the SAME vault key. */
    fun changePassword(oldPassword: String, newPassword: String): JSONObject {
        val u = user ?: throw IllegalStateException("not logged in")
        val oldSalt = u.getString("kdf_salt_b64")
        val oldIterations = u.getJSONObject("kdf").getInt("iterations")
        val (oldVerifier, _, _) = AegisCrypto.deriveCredentials(oldPassword, oldSalt, oldIterations)

        val newSaltB64 = AegisCrypto.b64(AegisCrypto.randomBytes(16))
        val (newVerifier, newKek, _) = AegisCrypto.deriveCredentials(newPassword, newSaltB64)
        val (nonce, wrapped) = AegisCrypto.aesGcmEncrypt(newKek, vaultKey!!)

        val out = http(
            "/v1/auth/change-password", "POST",
            JSONObject()
                .put("old_verifier_b64", AegisCrypto.b64(oldVerifier))
                .put("kdf_salt_b64", newSaltB64)
                .put("kdf", JSONObject().put("algo", AegisCrypto.KDF_ALGO).put("iterations", AegisCrypto.DEFAULT_ITERATIONS))
                .put("new_verifier_b64", AegisCrypto.b64(newVerifier))
                .put("wrapped_vault_key_b64", AegisCrypto.b64(wrapped))
                .put("vault_nonce_b64", AegisCrypto.b64(nonce)),
            auth = true
        )
        user = u.put("kdf_salt_b64", newSaltB64)
            .put("kdf", JSONObject().put("algo", AegisCrypto.KDF_ALGO).put("iterations", AegisCrypto.DEFAULT_ITERATIONS))
            .put("wrapped_vault_key_b64", AegisCrypto.b64(wrapped))
            .put("vault_nonce_b64", AegisCrypto.b64(nonce))
        return out
    }

    fun logout() {
        try {
            http("/v1/auth/logout", "POST", null, auth = true)
        } finally {
            accessToken = null; refreshToken = null; vaultKey = null
        }
    }

    fun deleteAccount(password: String): JSONObject {
        val u = user ?: throw IllegalStateException("not logged in")
        val (verifier, _, _) = AegisCrypto.deriveCredentials(
            password, u.getString("kdf_salt_b64"), u.getJSONObject("kdf").getInt("iterations")
        )
        return http(
            "/v1/auth/account", "DELETE",
            JSONObject().put("verifier_b64", AegisCrypto.b64(verifier)),
            auth = true
        )
    }

    // ----------------------------------------------------------------- Sync

    /** Push items. Auto-encrypts with the vault key; base version = clientVersionSeen. */
    fun push(changes: List<Change>): JSONObject {
        val vk = vaultKey ?: throw IllegalStateException("not logged in")
        val items = JSONArray()
        for (c in changes) {
            val item = JSONObject()
                .put("item_type", c.type)
                .put("item_key", c.key)
                .put("base_version", c.baseVersion)
                .put("deleted", c.deleted)
            if (!c.deleted) {
                val newVersion = c.baseVersion + 1
                val aad = AegisCrypto.utf8("${c.type}|${c.key}|$newVersion")
                val (nonce, ciphertext) = AegisCrypto.aesGcmEncrypt(
                    vk, AegisCrypto.utf8(c.plaintext!!.toString()), aad
                )
                item.put("nonce_b64", AegisCrypto.b64(nonce))
                item.put("ciphertext_b64", AegisCrypto.b64(ciphertext))
            }
            items.put(item)
        }
        return http("/v1/sync/push", "POST", JSONObject().put("items", items), auth = true)
    }

    /** Pull items newer than cursor. Auto-decrypts. */
    fun pull(cursor: Long = 0, types: List<String>? = null): Pair<List<SyncItem>, JSONObject> {
        val vk = vaultKey ?: throw IllegalStateException("not logged in")
        val q = StringBuilder("/v1/sync/pull?cursor=$cursor")
        types?.let { q.append("&types=").append(java.net.URLEncoder.encode(it.joinToString(","), "UTF-8")) }
        val data = http(q.toString(), auth = true)
        val out = ArrayList<SyncItem>()
        val rows = data.getJSONArray("items")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val type = row.getString("item_type")
            val key = row.getString("item_key")
            val version = row.getLong("version")
            val deleted = row.optBoolean("deleted", false)
            if (deleted) {
                out.add(SyncItem(type, key, version, true, null))
            } else {
                val aad = AegisCrypto.utf8("$type|$key|$version")
                val plain = AegisCrypto.aesGcmDecrypt(
                    vk,
                    AegisCrypto.unb64(row.getString("nonce_b64")),
                    AegisCrypto.unb64(row.getString("ciphertext_b64")),
                    aad
                )
                out.add(SyncItem(type, key, version, false, JSONObject(String(plain, Charsets.UTF_8))))
            }
        }
        return out to data
    }

    fun cursor(): JSONObject = http("/v1/sync/cursor", auth = true)

    // -------------------------------------------------------------- Devices

    fun devices(): JSONObject = http("/v1/devices", auth = true)

    fun revokeDevice(deviceIdToRevoke: String): JSONObject =
        http("/v1/devices/${java.net.URLEncoder.encode(deviceIdToRevoke, "UTF-8")}/revoke", "POST", null, auth = true)
}
