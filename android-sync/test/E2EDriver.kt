/*
 * Aegis Browser - LIVE end-to-end protocol test for AegisSyncClient.kt.
 *
 * Runs the Phase 3 acceptance sequence from the free-build plan (section 6.2)
 * against a deployed (or locally-run) aegis-sync worker:
 *
 *   register -> login (2 devices) -> push/pull bookmarks both ways ->
 *   optimistic-concurrency conflict -> device list -> password rotation
 *   (vault key survives) -> old password rejected -> account deletion
 *
 * Usage:
 *   kotlinc android-sync/AegisCrypto.kt android-sync/AegisSyncClient.kt E2EDriver.kt \
 *           -cp json-20240303.jar -include-runtime -d aegis-e2e.jar
 *   java -cp aegis-e2e.jar:json-20240303.jar org.aegis.sync.E2EDriverKt
 * Env: AEGIS_BASE (default http://127.0.0.1:8787)
 */
package org.aegis.sync

import org.json.JSONObject
import kotlin.system.exitProcess

private var passed = 0
private fun step(name: String, block: () -> String) {
    try {
        println("ok    $name\n        ${block().replace("\n", "\n        ")}")
        passed++
    } catch (e: Exception) {
        println("FAIL  $name: ${e.javaClass.simpleName}: ${e.message}")
        throw e
    }
}

fun main() {
    val base = (System.getenv("AEGIS_BASE") ?: "http://127.0.0.1:8787").trimEnd('/')
    val email = "e2e-kotlin-${System.currentTimeMillis()}@aegis.test"
    val pw1 = "Tr0ub4dor&3-密码!"
    val pw2 = "newer-stronger-密码-42!"

    val deviceA = AegisSyncClient(base, "11111111-1111-4111-8111-111111111111", "Kotlin E2E A", "android")
    val deviceB = AegisSyncClient(base, "22222222-2222-4222-8222-222222222222", "Kotlin E2E B", "android")

    try {
        // 1-2. register + login from device A
        step("register (device A)") {
            val r = deviceA.register(email, pw1)
            require(r.getBoolean("created")) { "created != true" }
            "user_id=${r.getString("user_id").take(8)}..."
        }
        step("login (device A) + vault key unwrap") {
            val u = deviceA.login(email, pw1)
            require(u.getString("email") == email)
            require(deviceA.vaultKey!!.size == 32) { "vault key not 32 bytes" }
            "email ok, vault key unlocked"
        }

        // 3. push a bookmark from A (base_version 0 -> server version 1)
        val bmA = JSONObject().put("url", "https://example.org/aegis-e2e").put("title", "Kotlin E2E bookmark")
        step("push bookmark b-01 (A)") {
            val r = deviceA.push(listOf(Change("bookmark", "b-01", 0, plaintext = bmA)))
            val item = r.getJSONArray("results").getJSONObject(0)
            require(item.getString("status") == "ok") { item.toString() }
            require(item.getLong("version") == 1L) { item.toString() }
            "status=ok version=1"
        }

        // 4. stale push must come back as a per-item conflict (HTTP stays 200)
        step("stale push -> per-item conflict (optimistic concurrency)") {
            val r = deviceA.push(listOf(Change("bookmark", "b-01", 0, plaintext = bmA)))
            val item = r.getJSONArray("results").getJSONObject(0)
            require(item.getString("status") == "conflict") { item.toString() }
            require(item.getJSONObject("server_item").getLong("version") == 1L) { item.toString() }
            "status=conflict, server_item.version=1"
        }

        // 5. login from device B; same vault key must be derived
        step("login (device B)") {
            val u = deviceB.login(email, pw1)
            require(u.getString("email") == email)
            require(deviceB.vaultKey!!.contentEquals(deviceA.vaultKey!!)) { "vault keys differ between devices!" }
            "same vault key derived on both devices"
        }
        step("device list shows 2 devices") {
            val d = deviceA.devices()
            val ids = d.getJSONArray("devices").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
            require(ids.size == 2) { ids.toString() }
            "devices=${ids.size}"
        }

        // 6. pull on B must return A's bookmark, decrypted correctly
        step("pull on B decrypts A's bookmark") {
            val (items, page) = deviceB.pull(0)
            require(!page.getBoolean("has_more"))
            val hit = items.firstOrNull { it.type == "bookmark" && it.key == "b-01" }
                ?: throw AssertionError("b-01 not in pull: ${items.map { it.key }}")
            require(hit.plaintext!!.getString("url") == "https://example.org/aegis-e2e")
            require(hit.version == 1L)
            "plaintext url matches, version=1"
        }

        // 7. B pushes b-02; A pulls it down
        step("push b-02 (B) then pull on A") {
            deviceB.push(listOf(Change("bookmark", "b-02", 0, plaintext = JSONObject().put("url", "https://example.org/second"))))
            val (items, _) = deviceA.pull(0)
            val hit = items.firstOrNull { it.key == "b-02" } ?: throw AssertionError("b-02 missing on A")
            require(hit.plaintext!!.getString("url") == "https://example.org/second")
            "A sees b-02 (${items.size} items total)"
        }

        // 8. password rotation: vault key must survive; old password must die
        step("changePassword (A)") {
            val r = deviceA.changePassword(pw1, pw2)
            require(r.getBoolean("ok"))
            "ok"
        }
        step("login with OLD password now rejected (401)") {
            try {
                AegisSyncClient(base, "33333333-3333-4333-8333-333333333333", "old pw probe", "android")
                    .login(email, pw1)
                throw AssertionError("old password login unexpectedly succeeded")
            } catch (e: AegisApiException) {
                require(e.status == 401) { "expected 401, got ${e.status}" }
                "401 invalid_credentials"
            }
        }
        step("login with NEW password + vault key survives") {
            val fresh = AegisSyncClient(base, "44444444-4444-4444-8444-444444444444", "Kotlin E2E C", "android")
            fresh.login(email, pw2)
            require(fresh.vaultKey!!.contentEquals(deviceA.vaultKey!!)) { "vault key changed after rotation!" }
            // and the vault still decrypts data written before the rotation
            val (items, _) = fresh.pull(0)
            val hit = items.firstOrNull { it.key == "b-01" } ?: throw AssertionError("b-01 missing after rotation")
            require(hit.plaintext!!.getString("url") == "https://example.org/aegis-e2e")
            "same vault key; pre-rotation ciphertext decrypts"
        }

        // 9. account deletion
        step("deleteAccount") {
            val r = deviceA.deleteAccount(pw2)
            require(r.getBoolean("deleted"))
            "deleted=true"
        }
        step("login after deletion rejected (401)") {
            try {
                AegisSyncClient(base, "55555555-5555-4555-8555-555555555555", "post-delete probe", "android")
                    .login(email, pw2)
                throw AssertionError("login after deletion unexpectedly succeeded")
            } catch (e: AegisApiException) {
                require(e.status == 401) { "expected 401, got ${e.status}" }
                "401"
            }
        }

        println("\nALL $passed E2E STEPS PASSED (live worker: $base)")
    } catch (e: Exception) {
        println("\nE2E FAILED after $passed passed steps")
        exitProcess(1)
    }
}
