/*
 * Aegis Browser - LIVE end-to-end protocol test for AegisSyncClient.java.
 *
 * Java twin of android-sync/test/E2EDriver.kt: the SAME 13-step Phase 3
 * acceptance sequence from the free-build plan (section 6.2), run against a
 * deployed (or locally-run) aegis-sync worker:
 *
 *   register -> login (2 devices) -> push/pull bookmarks both ways ->
 *   optimistic-concurrency conflict -> device list -> password rotation
 *   (vault key survives) -> old password rejected -> account deletion
 *
 * Compile + run (any JDK 17+; org.json from Maven Central):
 *   javac -encoding UTF-8 -d out android-sync/java/org/aegis/sync/*.java
 *   java -cp out:json-20240303.jar org.aegis.sync.E2EDriver
 * Env: AEGIS_BASE (default http://127.0.0.1:8787)
 */
package org.aegis.sync;

import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

public final class E2EDriver {

    private interface StepBlock {
        String run() throws Exception;
    }

    private static int passed = 0;

    private E2EDriver() {}

    private static void step(String name, StepBlock block) throws Exception {
        try {
            String detail = block.run().replace("\n", "\n        ");
            System.out.println("ok    " + name + "\n        " + detail);
            passed++;
        } catch (Exception e) {
            System.out.println("FAIL  " + name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    public static void main(String[] args) throws Exception {
        String base = (System.getenv("AEGIS_BASE") != null ? System.getenv("AEGIS_BASE") : "http://127.0.0.1:8787")
                .trim().replaceAll("/+$", "");
        String email = "e2e-java-" + System.currentTimeMillis() + "@aegis.test";
        String pw1 = "Tr0ub4dor&3-密码!";
        String pw2 = "newer-stronger-密码-42!";

        AegisSyncClient deviceA = new AegisSyncClient(
                base, "77777777-7777-4777-8777-777777777771", "Java E2E A", "android");
        AegisSyncClient deviceB = new AegisSyncClient(
                base, "77777777-7777-4777-8777-777777777772", "Java E2E B", "android");

        try {
            // 1-2. register + login from device A
            step("register (device A)", () -> {
                JSONObject r = deviceA.register(email, pw1);
                if (!r.getBoolean("created")) throw new AssertionError("created != true");
                return "user_id=" + r.getString("user_id").substring(0, 8) + "...";
            });
            step("login (device A) + vault key unwrap", () -> {
                JSONObject u = deviceA.login(email, pw1);
                if (!u.getString("email").equals(email)) throw new AssertionError("email mismatch");
                if (deviceA.vaultKey().length != 32) throw new AssertionError("vault key not 32 bytes");
                return "email ok, vault key unlocked";
            });

            // 3. push a bookmark from A (base_version 0 -> server version 1)
            JSONObject bmA = new JSONObject()
                    .put("url", "https://example.org/aegis-e2e")
                    .put("title", "Java E2E bookmark");
            step("push bookmark b-01 (A)", () -> {
                JSONObject r = deviceA.push(Collections.singletonList(
                        new AegisSyncClient.Change("bookmark", "b-01", 0, bmA)));
                JSONObject item = r.getJSONArray("results").getJSONObject(0);
                if (!item.getString("status").equals("ok")) throw new AssertionError(item.toString());
                if (item.getLong("version") != 1L) throw new AssertionError(item.toString());
                return "status=ok version=1";
            });

            // 4. stale push must come back as a per-item conflict (HTTP stays 200)
            step("stale push -> per-item conflict (optimistic concurrency)", () -> {
                JSONObject r = deviceA.push(Collections.singletonList(
                        new AegisSyncClient.Change("bookmark", "b-01", 0, bmA)));
                JSONObject item = r.getJSONArray("results").getJSONObject(0);
                if (!item.getString("status").equals("conflict")) throw new AssertionError(item.toString());
                if (item.getJSONObject("server_item").getLong("version") != 1L) {
                    throw new AssertionError(item.toString());
                }
                return "status=conflict, server_item.version=1";
            });

            // 5. login from device B; same vault key must be derived
            step("login (device B)", () -> {
                JSONObject u = deviceB.login(email, pw1);
                if (!u.getString("email").equals(email)) throw new AssertionError("email mismatch");
                if (!Arrays.equals(deviceB.vaultKey(), deviceA.vaultKey())) {
                    throw new AssertionError("vault keys differ between devices!");
                }
                return "same vault key derived on both devices";
            });
            step("device list shows 2 devices", () -> {
                JSONObject d = deviceA.devices();
                JSONArray a = d.getJSONArray("devices");
                int n = a.length();
                if (n != 2) throw new AssertionError("devices=" + n);
                return "devices=" + n;
            });

            // 6. pull on B must return A's bookmark, decrypted correctly
            step("pull on B decrypts A's bookmark", () -> {
                AegisSyncClient.PullResult res = deviceB.pull(0);
                if (res.page.getBoolean("has_more")) throw new AssertionError("unexpected has_more");
                AegisSyncClient.SyncItem hit = find(res, "b-01");
                if (hit == null) throw new AssertionError("b-01 not in pull");
                if (!hit.plaintext.getString("url").equals("https://example.org/aegis-e2e")) {
                    throw new AssertionError("url mismatch: " + hit.plaintext);
                }
                if (hit.version != 1L) throw new AssertionError("version=" + hit.version);
                return "plaintext url matches, version=1";
            });

            // 7. B pushes b-02; A pulls it down
            step("push b-02 (B) then pull on A", () -> {
                deviceB.push(Collections.singletonList(new AegisSyncClient.Change(
                        "bookmark", "b-02", 0,
                        new JSONObject().put("url", "https://example.org/second"))));
                AegisSyncClient.PullResult res = deviceA.pull(0);
                AegisSyncClient.SyncItem hit = find(res, "b-02");
                if (hit == null) throw new AssertionError("b-02 missing on A");
                if (!hit.plaintext.getString("url").equals("https://example.org/second")) {
                    throw new AssertionError("url mismatch: " + hit.plaintext);
                }
                return "A sees b-02 (" + res.items.size() + " items total)";
            });

            // 8. password rotation: vault key must survive; old password must die
            step("changePassword (A)", () -> {
                JSONObject r = deviceA.changePassword(pw1, pw2);
                if (!r.getBoolean("ok")) throw new AssertionError(r.toString());
                return "ok";
            });
            step("login with OLD password now rejected (401)", () -> {
                try {
                    new AegisSyncClient(base, "77777777-7777-4777-8777-777777777773", "old pw probe", "android")
                            .login(email, pw1);
                } catch (AegisSyncClient.AegisApiException e) {
                    if (e.status != 401) throw new AssertionError("expected 401, got " + e.status);
                    return "401 invalid_credentials";
                }
                throw new AssertionError("old password login unexpectedly succeeded");
            });
            step("login with NEW password + vault key survives", () -> {
                AegisSyncClient fresh = new AegisSyncClient(
                        base, "77777777-7777-4777-8777-777777777774", "Java E2E C", "android");
                fresh.login(email, pw2);
                if (!Arrays.equals(fresh.vaultKey(), deviceA.vaultKey())) {
                    throw new AssertionError("vault key changed after rotation!");
                }
                // and the vault still decrypts data written before the rotation
                AegisSyncClient.SyncItem hit = find(fresh.pull(0), "b-01");
                if (hit == null) throw new AssertionError("b-01 missing after rotation");
                if (!hit.plaintext.getString("url").equals("https://example.org/aegis-e2e")) {
                    throw new AssertionError("pre-rotation data no longer decrypts");
                }
                return "same vault key; pre-rotation ciphertext decrypts";
            });

            // 9. account deletion
            step("deleteAccount", () -> {
                JSONObject r = deviceA.deleteAccount(pw2);
                if (!r.getBoolean("deleted")) throw new AssertionError(r.toString());
                return "deleted=true";
            });
            step("login after deletion rejected (401)", () -> {
                try {
                    new AegisSyncClient(base, "77777777-7777-4777-8777-777777777775", "post-delete probe", "android")
                            .login(email, pw2);
                } catch (AegisSyncClient.AegisApiException e) {
                    if (e.status != 401) throw new AssertionError("expected 401, got " + e.status);
                    return "401";
                }
                throw new AssertionError("login after deletion unexpectedly succeeded");
            });

            System.out.println("\nALL " + passed + " E2E STEPS PASSED (live worker: " + base + ")");
        } catch (Exception e) {
            System.out.println("\nE2E FAILED after " + passed + " passed steps");
            System.exit(1);
        }
    }

    private static AegisSyncClient.SyncItem find(AegisSyncClient.PullResult res, String key) {
        for (AegisSyncClient.SyncItem it : res.items) {
            if (it.key.equals(key)) return it;
        }
        return null;
    }
}
