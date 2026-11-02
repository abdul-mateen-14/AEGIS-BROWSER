/*
 * Aegis Browser - Android sync client (protocol layer), JAVA port.
 *
 * Java twin of AegisSyncClient.kt - a faithful port of the API half of
 * aegis-sync/client-example/aegis-client.mjs. Endpoints, request/response
 * field names, refresh-token retry and the vault-key unwrap path were
 * verified 1:1 against aegis-sync/src/auth.ts and src/sync.ts, and the
 * sequence is proven live by E2EDriver (13/13 steps, see test twin).
 *
 * Why a Java variant: Chromium's chrome_java targets are Java-first, so this
 * file drops into the browser tree with a one-line BUILD.gn addition instead
 * of new Kotlin tooling. Crypto lives in AegisCrypto.java (pure JVM, same
 * package) and is conformance-tested against the same generated vectors as
 * the Kotlin original (AegisCryptoVectorTest.java, 16/16).
 *
 * Dependencies: org.json only (bundled with Android). To compile/test on a
 * desktop JVM add org.json:json to the classpath; AegisCrypto.java alone has
 * zero dependencies.
 */
package org.aegis.sync;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class AegisSyncClient {

    /** Thrown for non-2xx responses (after a refresh retry has been tried). */
    public static class AegisApiException extends RuntimeException {
        public final int status;
        public final String code; // may be null

        public AegisApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    /** One local change queued for push. */
    public static class Change {
        public final String type; // e.g. "bookmark", "tab", "setting", "password", "autofill"
        public final String key;  // stable item key
        public final long baseVersion;
        public final boolean deleted;
        public final JSONObject plaintext; // null iff deleted

        public Change(String type, String key, long baseVersion, boolean deleted, JSONObject plaintext) {
            this.type = type;
            this.key = key;
            this.baseVersion = baseVersion;
            this.deleted = deleted;
            this.plaintext = plaintext;
        }

        /** Upsert (not deleted). */
        public Change(String type, String key, long baseVersion, JSONObject plaintext) {
            this(type, key, baseVersion, false, plaintext);
        }

        /** Tombstone. */
        public static Change deletion(String type, String key, long baseVersion) {
            return new Change(type, key, baseVersion, true, null);
        }
    }

    /** One pulled item (already decrypted). */
    public static class SyncItem {
        public final String type;
        public final String key;
        public final long version;
        public final boolean deleted;
        public final JSONObject plaintext; // null iff deleted

        public SyncItem(String type, String key, long version, boolean deleted, JSONObject plaintext) {
            this.type = type;
            this.key = key;
            this.version = version;
            this.deleted = deleted;
            this.plaintext = plaintext;
        }
    }

    /** Result page of pull(): decrypted items plus the raw server page. */
    public static class PullResult {
        public final List<SyncItem> items;
        public final JSONObject page; // has next_cursor / has_more

        public PullResult(List<SyncItem> items, JSONObject page) {
            this.items = items;
            this.page = page;
        }
    }

    private final String base;
    private final String deviceId; // stable UUID generated once per install
    private final String deviceName;
    private final String devicePlatform; // e.g. "android"

    private String accessToken;
    private String refreshToken;
    private byte[] vaultKey;
    private JSONObject user;

    public AegisSyncClient(String baseUrl, String deviceId, String deviceName, String devicePlatform) {
        this.base = baseUrl.trim().replaceAll("/+$", "");
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.devicePlatform = devicePlatform;
    }

    public String accessToken() { return accessToken; }
    public String refreshToken() { return refreshToken; }
    public byte[] vaultKey() { return vaultKey; }
    public JSONObject user() { return user; }

    // ------------------------------------------------------------------ HTTP

    private JSONObject http(String path, String method, JSONObject body, boolean auth, boolean retry)
            throws java.io.IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Content-Type", "application/json");
        if (auth && accessToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        java.io.InputStream stream = (status >= 200 && status <= 299) ? conn.getInputStream() : conn.getErrorStream();
        String text = "";
        if (stream != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                text = sb.toString().trim();
            }
        }
        conn.disconnect();

        JSONObject data = text.isEmpty() ? new JSONObject() : new JSONObject(text);

        if (status == 401 && auth && retry && refreshToken != null) {
            if (refreshTokens()) {
                return http(path, method, body, auth, false);
            }
        }
        if (status < 200 || status > 299) {
            JSONObject err = data.optJSONObject("error");
            throw new AegisApiException(status,
                    err != null ? err.optString("code", null) : null,
                    err != null ? err.optString("message", "HTTP " + status) : "HTTP " + status);
        }
        return data;
    }

    private boolean refreshTokens() {
        if (refreshToken == null) return false;
        try {
            JSONObject data = http("/v1/auth/refresh", "POST",
                    new JSONObject().put("refresh_token", refreshToken), false, true);
            accessToken = data.getString("access_token");
            refreshToken = data.getString("refresh_token");
            return true;
        } catch (Exception e) {
            accessToken = null;
            refreshToken = null;
            return false;
        }
    }

    // ----------------------------------------------------------------- Auth

    /** Creates a new account. KDF salt + vault key are generated on-device. */
    public JSONObject register(String email, String password, String inviteCode) throws java.io.IOException {
        String kdfSaltB64 = AegisCrypto.b64(AegisCrypto.randomBytes(16));
        AegisCrypto.Creds creds = AegisCrypto.deriveCredentials(password, kdfSaltB64);
        byte[] vaultKeyLocal = AegisCrypto.generateVaultKey();
        AegisCrypto.GcmResult wrapped = AegisCrypto.aesGcmEncrypt(creds.kek, vaultKeyLocal);

        JSONObject body = new JSONObject()
                .put("email", email)
                .put("kdf_salt_b64", kdfSaltB64)
                .put("kdf", new JSONObject()
                        .put("algo", AegisCrypto.KDF_ALGO)
                        .put("iterations", AegisCrypto.DEFAULT_ITERATIONS))
                .put("verifier_b64", AegisCrypto.b64(creds.verifier))
                .put("wrapped_vault_key_b64", AegisCrypto.b64(wrapped.ciphertext))
                .put("vault_nonce_b64", AegisCrypto.b64(wrapped.nonce));
        if (inviteCode != null && !inviteCode.isEmpty()) body.put("invite_code", inviteCode);
        return http("/v1/auth/register", "POST", body, false, true);
    }

    public JSONObject register(String email, String password) throws java.io.IOException {
        return register(email, password, null);
    }

    /**
     * Logs in, unlocks the vault key in memory, registers/updates this device.
     * Two steps: fetch public KDF params (salt), derive the key chain ONCE,
     * then send the verifier.
     */
    public JSONObject login(String email, String password) throws java.io.IOException {
        JSONObject params =
                http("/v1/auth/kdf-params?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8), "GET", null, false, true);
        JSONObject kdf = params.getJSONObject("kdf");
        AegisCrypto.Creds creds = AegisCrypto.deriveCredentials(
                password, params.getString("kdf_salt_b64"), kdf.getInt("iterations"));

        JSONObject data = http("/v1/auth/login", "POST",
                new JSONObject()
                        .put("email", email)
                        .put("verifier_b64", AegisCrypto.b64(creds.verifier))
                        .put("device", new JSONObject()
                                .put("id", deviceId)
                                .put("name", deviceName)
                                .put("platform", devicePlatform)),
                false, true);

        accessToken = data.getString("access_token");
        refreshToken = data.getString("refresh_token");
        user = data.getJSONObject("user");

        // Server-confirmed params must match what we derived with; if the
        // account was migrated to new KDF params, re-derive before unwrapping.
        JSONObject u = user;
        JSONObject userKdf = u.getJSONObject("kdf");
        byte[] vaultKeyLocal;
        if (!userKdf.optString("algo").equals(kdf.optString("algo"))
                || userKdf.getInt("iterations") != kdf.getInt("iterations")) {
            AegisCrypto.Creds creds2 = AegisCrypto.deriveCredentials(
                    password, u.getString("kdf_salt_b64"), userKdf.getInt("iterations"));
            vaultKeyLocal = AegisCrypto.aesGcmDecrypt(
                    creds2.kek,
                    AegisCrypto.unb64(u.getString("vault_nonce_b64")),
                    AegisCrypto.unb64(u.getString("wrapped_vault_key_b64")));
        } else {
            vaultKeyLocal = AegisCrypto.aesGcmDecrypt(
                    creds.kek,
                    AegisCrypto.unb64(u.getString("vault_nonce_b64")),
                    AegisCrypto.unb64(u.getString("wrapped_vault_key_b64")));
        }
        vaultKey = vaultKeyLocal;
        return u;
    }

    /** Rotates the password: re-derives keys, re-wraps the SAME vault key. */
    public JSONObject changePassword(String oldPassword, String newPassword) throws java.io.IOException {
        if (user == null) throw new IllegalStateException("not logged in");
        JSONObject u = user;
        String oldSalt = u.getString("kdf_salt_b64");
        int oldIterations = u.getJSONObject("kdf").getInt("iterations");
        byte[] oldVerifier =
                AegisCrypto.deriveCredentials(oldPassword, oldSalt, oldIterations).verifier;

        String newSaltB64 = AegisCrypto.b64(AegisCrypto.randomBytes(16));
        AegisCrypto.Creds newCreds = AegisCrypto.deriveCredentials(newPassword, newSaltB64);
        AegisCrypto.GcmResult wrapped = AegisCrypto.aesGcmEncrypt(newCreds.kek, vaultKey);

        JSONObject out = http("/v1/auth/change-password", "POST",
                new JSONObject()
                        .put("old_verifier_b64", AegisCrypto.b64(oldVerifier))
                        .put("kdf_salt_b64", newSaltB64)
                        .put("kdf", new JSONObject()
                                .put("algo", AegisCrypto.KDF_ALGO)
                                .put("iterations", AegisCrypto.DEFAULT_ITERATIONS))
                        .put("new_verifier_b64", AegisCrypto.b64(newCreds.verifier))
                        .put("wrapped_vault_key_b64", AegisCrypto.b64(wrapped.ciphertext))
                        .put("vault_nonce_b64", AegisCrypto.b64(wrapped.nonce)),
                true, true);
        user = new JSONObject(u.toString())
                .put("kdf_salt_b64", newSaltB64)
                .put("kdf", new JSONObject()
                        .put("algo", AegisCrypto.KDF_ALGO)
                        .put("iterations", AegisCrypto.DEFAULT_ITERATIONS))
                .put("wrapped_vault_key_b64", AegisCrypto.b64(wrapped.ciphertext))
                .put("vault_nonce_b64", AegisCrypto.b64(wrapped.nonce));
        return out;
    }

    public void logout() throws java.io.IOException {
        try {
            http("/v1/auth/logout", "POST", null, true, true);
        } finally {
            accessToken = null;
            refreshToken = null;
            vaultKey = null;
        }
    }

    public JSONObject deleteAccount(String password) throws java.io.IOException {
        if (user == null) throw new IllegalStateException("not logged in");
        JSONObject u = user;
        byte[] verifier = AegisCrypto.deriveCredentials(
                password, u.getString("kdf_salt_b64"), u.getJSONObject("kdf").getInt("iterations")).verifier;
        return http("/v1/auth/account", "DELETE",
                new JSONObject().put("verifier_b64", AegisCrypto.b64(verifier)), true, true);
    }

    // ----------------------------------------------------------------- Sync

    /** Push items. Auto-encrypts with the vault key; base version = clientVersionSeen. */
    public JSONObject push(List<Change> changes) throws java.io.IOException {
        if (vaultKey == null) throw new IllegalStateException("not logged in");
        JSONArray items = new JSONArray();
        for (Change c : changes) {
            JSONObject item = new JSONObject()
                    .put("item_type", c.type)
                    .put("item_key", c.key)
                    .put("base_version", c.baseVersion)
                    .put("deleted", c.deleted);
            if (!c.deleted) {
                long newVersion = c.baseVersion + 1;
                byte[] aad = AegisCrypto.utf8(c.type + "|" + c.key + "|" + newVersion);
                AegisCrypto.GcmResult encrypted = AegisCrypto.aesGcmEncrypt(
                        vaultKey, AegisCrypto.utf8(c.plaintext.toString()), aad);
                item.put("nonce_b64", AegisCrypto.b64(encrypted.nonce));
                item.put("ciphertext_b64", AegisCrypto.b64(encrypted.ciphertext));
            }
            items.put(item);
        }
        return http("/v1/sync/push", "POST", new JSONObject().put("items", items), true, true);
    }

    /** Pull items newer than cursor. Auto-decrypts. */
    public PullResult pull(long cursor, List<String> types) throws java.io.IOException {
        if (vaultKey == null) throw new IllegalStateException("not logged in");
        StringBuilder q = new StringBuilder("/v1/sync/pull?cursor=").append(cursor);
        if (types != null && !types.isEmpty()) {
            q.append("&types=").append(URLEncoder.encode(String.join(",", types), StandardCharsets.UTF_8));
        }
        JSONObject data = http(q.toString(), "GET", null, true, true);
        List<SyncItem> out = new ArrayList<>();
        JSONArray rows = data.getJSONArray("items");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String type = row.getString("item_type");
            String key = row.getString("item_key");
            long version = row.getLong("version");
            boolean deleted = row.optBoolean("deleted", false);
            if (deleted) {
                out.add(new SyncItem(type, key, version, true, null));
            } else {
                byte[] aad = AegisCrypto.utf8(type + "|" + key + "|" + version);
                byte[] plain = AegisCrypto.aesGcmDecrypt(
                        vaultKey,
                        AegisCrypto.unb64(row.getString("nonce_b64")),
                        AegisCrypto.unb64(row.getString("ciphertext_b64")),
                        aad);
                out.add(new SyncItem(type, key, version, false,
                        new JSONObject(new String(plain, StandardCharsets.UTF_8))));
            }
        }
        return new PullResult(out, data);
    }

    public PullResult pull(long cursor) throws java.io.IOException {
        return pull(cursor, null);
    }

    public JSONObject cursor() throws java.io.IOException {
        return http("/v1/sync/cursor", "GET", null, true, true);
    }

    // -------------------------------------------------------------- Devices

    public JSONObject devices() throws java.io.IOException {
        return http("/v1/devices", "GET", null, true, true);
    }

    public JSONObject revokeDevice(String deviceIdToRevoke) throws java.io.IOException {
        return http("/v1/devices/" + URLEncoder.encode(deviceIdToRevoke, StandardCharsets.UTF_8) + "/revoke",
                "POST", null, true, true);
    }
}
