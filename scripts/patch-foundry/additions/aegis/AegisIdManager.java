// Copyright 2026 The Aegis Browser Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.aegis;

import static org.chromium.build.NullUtil.assumeNonNull;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.chromium.base.ContextUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.aegis.sync.AegisCrypto;
import org.chromium.chrome.browser.aegis.sync.AegisSyncClient;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Aegis ID: sign-in and session manager for the Aegis sync backend
 * (https://aegis-sync.abdulmateen14.workers.dev - a Cloudflare Worker that
 * only ever sees opaque verifiers and wrapped keys).
 *
 * End-to-end model (mirrors aegis-sync/client-example/aegis-client.mjs):
 *   - The passphrase never leaves the device. signIn() fetches the public
 *     KDF params, derives the key chain ONCE (AegisCrypto.deriveCredentials)
 *     and sends only the verifier over TLS.
 *   - The vault key is generated on-device and only ever stored server-side
 *     wrapped (AES-256-GCM) under the KEK; unwrapping happens in memory.
 *   - Persisted state is identity-only: email, a stable device UUID and the
 *     signed-in flag ("aegis_id_prefs"). Access/refresh tokens and the vault
 *     key are volatile - a process restart keeps the identity but requires
 *     a fresh sign-in before any backend call works again.
 *
 * Threading: every public operation runs on a single-thread executor and
 * posts exactly one Callback result back on the main looper.
 */
@NullMarked
public class AegisIdManager {
    /** Delivered on the main thread. On failure errorOrSummary is a human
     * readable error; on success it carries a summary (the email, or the
     * raw JSON of the devices array for devices()). */
    public interface Callback {
        void onResult(boolean ok, String errorOrSummary);
    }

    private static final String BASE_URL = "https://aegis-sync.abdulmateen14.workers.dev";
    private static final String PLATFORM = "android";
    private static final String PREFS_NAME = "aegis_id_prefs";
    private static final String KEY_EMAIL = "aegis_id_email";
    private static final String KEY_DEVICE_UUID = "aegis_id_device_uuid";
    private static final String KEY_SIGNED_IN = "aegis_id_signed_in";
    private static final int DEVICE_NAME_MAX = 64;

    private static AegisIdManager sInstance;

    private final SharedPreferences mPrefs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final String mDeviceUuid;
    private final String mDeviceName;

    // Volatile session state - never persisted (see class comment).
    private @Nullable String mAccessToken;
    private @Nullable String mRefreshToken;
    private @Nullable byte[] mVaultKey;
    private @Nullable AegisSyncClient mClient;

    /** Returns the process-wide instance, bound to the application context. */
    public static AegisIdManager getInstance(Context context) {
        if (sInstance == null) {
            Context appContext = context.getApplicationContext();
            assumeNonNull(appContext);
            sInstance = new AegisIdManager(appContext);
        }
        return sInstance;
    }

    private AegisIdManager(Context context) {
        mPrefs = ContextUtils.getAppSharedPreferences(context, PREFS_NAME);
        mDeviceUuid = getOrCreateDeviceUuid();
        mDeviceName = sanitizeDeviceName(Build.MODEL);
    }

    /** True when this device's identity is marked signed-in. */
    public boolean isSignedIn() {
        return mPrefs.getBoolean(KEY_SIGNED_IN, false);
    }

    /** The persisted account email, or "" when unknown. */
    public String signedInEmail() {
        return mPrefs.getString(KEY_EMAIL, "");
    }

    /** The stable per-install device UUID (random UUID, persisted once). */
    public String deviceUuid() {
        return mDeviceUuid;
    }

    /**
     * Signs in: kdf-params -> deriveCredentials -> verifier login. On
     * success the identity is persisted and the session kept in memory.
     */
    public void signIn(final String email, final String password, final Callback callback) {
        mExecutor.execute(() -> {
            try {
                post(callback, true, doSignIn(email, password));
            } catch (AegisSyncClient.AegisApiException e) {
                post(callback, false, describeApiError(e));
            } catch (Exception e) {
                post(callback, false, describeFailure("Couldn't sign in", e));
            }
        });
    }

    /**
     * Creates the account - KDF salt, verifier and wrapped vault key are all
     * generated on-device - and then runs the normal sign-in flow.
     */
    public void createAccount(final String email, final String password, final Callback callback) {
        mExecutor.execute(() -> {
            try {
                String kdfSaltB64 = AegisCrypto.b64(AegisCrypto.randomBytes(16));
                AegisCrypto.Creds creds = AegisCrypto.deriveCredentials(password, kdfSaltB64);
                byte[] vaultKey = AegisCrypto.generateVaultKey();
                AegisCrypto.GcmResult wrapped = AegisCrypto.aesGcmEncrypt(creds.kek, vaultKey);
                JSONObject kdf = new JSONObject()
                        .put("algo", AegisCrypto.KDF_ALGO)
                        .put("iterations", AegisCrypto.DEFAULT_ITERATIONS);
                client().register(email, kdfSaltB64, kdf, AegisCrypto.b64(creds.verifier),
                        AegisCrypto.b64(wrapped.ciphertext), AegisCrypto.b64(wrapped.nonce), null);
                post(callback, true, doSignIn(email, password));
            } catch (AegisSyncClient.AegisApiException e) {
                post(callback, false, describeApiError(e));
            } catch (Exception e) {
                post(callback, false, describeFailure("Couldn't create the account", e));
            }
        });
    }

    /** Best-effort server logout (errors swallowed), then local sign-out. */
    public void signOut(final Callback callback) {
        mExecutor.execute(() -> {
            if (mAccessToken != null || mRefreshToken != null) {
                try {
                    client().logout();
                } catch (Exception e) {
                    // Best-effort: the server session expires on its own.
                }
            }
            clearSession();
            post(callback, true, "");
        });
    }

    /** Lists the account's devices; onResult carries the devices array JSON. */
    public void devices(final Callback callback) {
        mExecutor.execute(() -> {
            AegisSyncClient client = client();
            try {
                JSONObject data = client.devices();
                adopt(client);
                post(callback, true, data.getJSONArray("devices").toString());
            } catch (AegisSyncClient.AegisApiException e) {
                adopt(client);
                dropStaleSession();
                post(callback, false, describeApiError(e));
            } catch (Exception e) {
                post(callback, false, describeFailure("Couldn't load devices", e));
            }
        });
    }

    /** Revokes a device by id; on success it disappears from devices(). */
    public void revokeDevice(final String deviceId, final Callback callback) {
        mExecutor.execute(() -> {
            AegisSyncClient client = client();
            try {
                client.revokeDevice(deviceId);
                adopt(client);
                post(callback, true, "");
            } catch (AegisSyncClient.AegisApiException e) {
                adopt(client);
                dropStaleSession();
                post(callback, false, describeApiError(e));
            } catch (Exception e) {
                post(callback, false, describeFailure("Couldn't revoke the device", e));
            }
        });
    }

    // ------------------------------------------------------------- internals

    private String doSignIn(String email, String password) throws IOException, JSONException {
        AegisSyncClient client = client();
        JSONObject params = client.kdfParams(email);
        JSONObject kdf = params.getJSONObject("kdf");
        AegisCrypto.Creds creds = AegisCrypto.deriveCredentials(
                password, params.getString("kdf_salt_b64"), kdf.getInt("iterations"));
        JSONObject data = client.login(email, AegisCrypto.b64(creds.verifier),
                mDeviceUuid, mDeviceName, PLATFORM);
        mAccessToken = data.getString("access_token");
        mRefreshToken = data.getString("refresh_token");
        JSONObject user = data.getJSONObject("user");
        mVaultKey = unwrapVaultKey(password, creds, kdf, user);
        client.setVaultKey(mVaultKey);
        mPrefs.edit().putString(KEY_EMAIL, email).putBoolean(KEY_SIGNED_IN, true).apply();
        return email;
    }

    /** Unwraps the vault key under the KEK, honoring migrated KDF params. */
    private byte[] unwrapVaultKey(
            String password, AegisCrypto.Creds creds, JSONObject kdf, JSONObject user)
            throws JSONException {
        JSONObject userKdf = user.getJSONObject("kdf");
        byte[] kek = creds.kek;
        if (!userKdf.optString("algo").equals(kdf.optString("algo"))
                || userKdf.getInt("iterations") != kdf.getInt("iterations")) {
            // The account was migrated to new KDF params: re-derive ONCE from
            // the passphrase before unwrapping (mirrors aegis-client.mjs).
            kek = AegisCrypto.deriveCredentials(
                    password, user.getString("kdf_salt_b64"), userKdf.getInt("iterations")).kek;
        }
        return AegisCrypto.aesGcmDecrypt(kek,
                AegisCrypto.unb64(user.getString("vault_nonce_b64")),
                AegisCrypto.unb64(user.getString("wrapped_vault_key_b64")));
    }

    /** The protocol client for this session; tokens are pushed in per call. */
    private AegisSyncClient client() {
        if (mClient == null) {
            mClient = new AegisSyncClient(BASE_URL);
        }
        mClient.setTokens(mAccessToken, mRefreshToken);
        mClient.setVaultKey(mVaultKey);
        return mClient;
    }

    /** Pulls token rotations (from the client's refresh retry) back in. */
    private void adopt(AegisSyncClient client) {
        mAccessToken = client.accessToken();
        mRefreshToken = client.refreshToken();
    }

    /** A 401 with no tokens left means the volatile session died with the
     * process: reset the persisted flag so the UI offers sign-in again. */
    private void dropStaleSession() {
        if (mAccessToken == null && mRefreshToken == null && isSignedIn()) {
            clearSession();
        }
    }

    private void clearSession() {
        mAccessToken = null;
        mRefreshToken = null;
        mVaultKey = null;
        mPrefs.edit().putBoolean(KEY_SIGNED_IN, false).remove(KEY_EMAIL).apply();
    }

    private String getOrCreateDeviceUuid() {
        String uuid = mPrefs.getString(KEY_DEVICE_UUID, "");
        if (uuid.isEmpty()) {
            uuid = UUID.randomUUID().toString();
            mPrefs.edit().putString(KEY_DEVICE_UUID, uuid).apply();
        }
        return uuid;
    }

    /** Build.MODEL stripped to a sane, single-line length (server cap: 100). */
    private static String sanitizeDeviceName(@Nullable String model) {
        String name = model == null ? "" : model.replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) name = "Android device";
        if (name.length() > DEVICE_NAME_MAX) name = name.substring(0, DEVICE_NAME_MAX);
        return name;
    }

    /** Maps API failures to human messages (no account enumeration). */
    private static String describeApiError(AegisSyncClient.AegisApiException e) {
        if (e.status == 401) return "Wrong email or passphrase";
        if (e.status == 429) return "Too many attempts, try later";
        if (e.code != null && !e.code.isEmpty()) return e.code;
        String message = e.getMessage();
        if (message == null || message.isEmpty()) return "Error " + e.status;
        return message;
    }

    private static String describeFailure(String what, Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) message = e.getClass().getSimpleName();
        return what + ": " + message;
    }

    /** Posts exactly one callback result, always on the main looper. */
    private void post(final Callback callback, final boolean ok, final String errorOrSummary) {
        mHandler.post(() -> callback.onResult(ok, errorOrSummary));
    }
}
