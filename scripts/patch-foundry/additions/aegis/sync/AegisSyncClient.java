// Copyright 2026 The Aegis Browser Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

/*
 * Aegis Browser - Android sync client (protocol layer), JAVA port.
 *
 * Java twin of AegisSyncClient.kt - a faithful port of the API half of
 * aegis-sync/client-example/aegis-client.mjs. Endpoints, request/response
 * field names, refresh-token retry and the vault-key unwrap path were
 * verified 1:1 against aegis-sync/src/auth.ts and src/sync.ts, and the
 * sequence is proven live by E2EDriver (13/13 steps, see test twin).
 *
 * Browser-embedded variant: this copy is a THIN PROTOCOL LAYER driven by
 * org.chromium.chrome.browser.aegis.AegisIdManager. Key derivation is
 * orchestrated by the manager (kdf-params -> deriveCredentials -> login),
 * so register()/login() take pre-derived opaque fields (verifier, wrapped
 * vault key, kdf params) with field names mirroring aegis-client.mjs
 * exactly. Session tokens and the vault key live in memory only - the
 * manager pushes them in via setTokens()/setVaultKey() and reads them back
 * after calls that may rotate them. Crypto lives in AegisCrypto.java (same
 * package) and is conformance-tested against the same generated vectors as
 * the Kotlin original (AegisCryptoVectorTest.java, 16/16).
 *
 * Dependencies: org.json only (bundled with Android). No Android UI or
 * framework imports - java.net + org.json only, so this file stays unit
 * testable off-device.
 */
package org.chromium.chrome.browser.aegis.sync;

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

    private String accessToken;
    private String refreshToken;
    private byte[] vaultKey;

    public AegisSyncClient(String baseUrl) {
        this.base = baseUrl.trim().replaceAll("/+$", "");
    }

    public String accessToken() { return accessToken; }
    public String refreshToken() { return refreshToken; }
    public byte[] vaultKey() { return vaultKey; }

    /** The manager owns the session; it pushes tokens in before each call. */
    public void setTokens(String accessToken, String refreshToken) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    public void setVaultKey(byte[] vaultKey) {
        this.vaultKey = vaultKey;
    }

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
            refresh(refreshToken);
            return true;
        } catch (Exception e) {
            accessToken = null;
            refreshToken = null;
            return false;
        }
    }

    // ----------------------------------------------------------------- Auth

    /** Public KDF parameters for an email: {kdf_salt_b64, kdf:{algo,iterations}}. */
    public JSONObject kdfParams(String email) throws java.io.IOException {
        return http("/v1/auth/kdf-params?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8),
                "GET", null, false, true);
    }

    /**
     * Creates a new account. All crypto is done by the caller: it generates
     * the KDF salt, derives verifier + KEK, generates and wraps the vault
     * key. Field names mirror the register body of aegis-client.mjs.
     */
    public JSONObject register(String email, String kdfSaltB64, JSONObject kdf,
            String verifierB64, String wrappedVaultKeyB64, String vaultNonceB64, String inviteCode)
            throws java.io.IOException {
        JSONObject body = new JSONObject()
                .put("email", email)
                .put("kdf_salt_b64", kdfSaltB64)
                .put("kdf", kdf)
                .put("verifier_b64", verifierB64)
                .put("wrapped_vault_key_b64", wrappedVaultKeyB64)
                .put("vault_nonce_b64", vaultNonceB64);
        if (inviteCode != null && !inviteCode.isEmpty()) body.put("invite_code", inviteCode);
        return http("/v1/auth/register", "POST", body, false, true);
    }

    /**
     * Sends the pre-derived verifier (the caller fetched kdf-params and ran
     * deriveCredentials), registers/updates the device and mints the session
     * pair. Stores the tokens in memory and returns the raw response
     * ({access_token, refresh_token, user:{...}}); unwrapping the vault key
     * from response.user is the caller's job - it holds the derived KEK.
     */
    public JSONObject login(String email, String verifierB64, String deviceId,
            String deviceName, String devicePlatform) throws java.io.IOException {
        JSONObject data = http("/v1/auth/login", "POST",
                new JSONObject()
                        .put("email", email)
                        .put("verifier_b64", verifierB64)
                        .put("device", new JSONObject()
                                .put("id", deviceId)
                                .put("name", deviceName)
                                .put("platform", devicePlatform)),
                false, true);
        accessToken = data.getString("access_token");
        refreshToken = data.getString("refresh_token");
        return data;
    }

    /** Exchanges a refresh token for a fresh pair (rotation: old one dies). */
    public JSONObject refresh(String refreshToken) throws java.io.IOException {
        JSONObject data = http("/v1/auth/refresh", "POST",
                new JSONObject().put("refresh_token", refreshToken), false, true);
        accessToken = data.getString("access_token");
        this.refreshToken = data.getString("refresh_token");
        return data;
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
