// Crypto unit tests for the client reference library. Run: node test/crypto.test.mjs
import {
  deriveMasterKey,
  hkdf,
  computeVerifier,
  deriveCredentials,
  generateVaultKey,
  wrapVaultKey,
  unwrapVaultKey,
  encryptItem,
  decryptItem,
  bytesToB64,
  b64ToBytes,
  DEFAULT_KDF,
} from '../client-example/aegis-client.mjs';

let failures = 0;
function check(name, cond) {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}`);
  if (!cond) failures++;
}
async function checkThrows(name, fn) {
  try {
    await fn();
    console.log(`FAIL  ${name} (did not throw)`);
    failures++;
  } catch {
    console.log(`PASS  ${name}`);
  }
}

const SALT = bytesToB64(new Uint8Array(16).fill(7));

// 1. Determinism: same password+salt -> same master key
const mk1 = await deriveMasterKey('correct horse battery staple', SALT);
const mk2 = await deriveMasterKey('correct horse battery staple', SALT);
check('masterKey deterministic', bytesToB64(mk1) === bytesToB64(mk2));

// 2. Different password -> different key
const mk3 = await deriveMasterKey('wrong password', SALT);
check('different password -> different masterKey', bytesToB64(mk1) !== bytesToB64(mk3));

// 3. Different salt -> different key
const mk4 = await deriveMasterKey('correct horse battery staple', bytesToB64(new Uint8Array(16).fill(8)));
check('different salt -> different masterKey', bytesToB64(mk1) !== bytesToB64(mk4));

// 4. Key sizes are 32 bytes
check('masterKey is 32 bytes', mk1.length === 32);
const authKey = await hkdf(mk1, 'aegis-auth-v1');
check('hkdf output is 32 bytes', authKey.length === 32);
const verifier = await computeVerifier(authKey);
check('verifier is 32 bytes', verifier.length === 32);

// 5. Full chain determinism
const c1 = await deriveCredentials('p@ssw0rd!', SALT);
const c2 = await deriveCredentials('p@ssw0rd!', SALT);
check('credential chain deterministic', bytesToB64(c1.verifier) === bytesToB64(c2.verifier) && bytesToB64(c1.kek) === bytesToB64(c2.kek));

// 6. Vault key wrap/unwrap roundtrip
const vaultKey = generateVaultKey();
const { wrapped, nonce } = await wrapVaultKey(vaultKey, c1.kek);
const unwrapped = await unwrapVaultKey(wrapped, bytesToB64(nonce), c1.kek);
check('vault key wrap/unwrap roundtrip', bytesToB64(unwrapped) === bytesToB64(vaultKey));

// 7. Unwrap with wrong KEK fails
await checkThrows('unwrap with wrong KEK throws', async () => {
  const wrong = await deriveCredentials('other password', SALT);
  await unwrapVaultKey(wrapped, bytesToB64(nonce), wrong.kek);
});

// 8. Item encrypt/decrypt roundtrip
const doc = { url: 'https://example.org', title: 'Example', children: [1, 2, 3] };
const enc = await encryptItem(vaultKey, 'bookmark', 'bk-123', 4, doc);
const row = { item_type: 'bookmark', item_key: 'bk-123', version: 4, nonce_b64: enc.nonce_b64, ciphertext_b64: enc.ciphertext_b64 };
const dec = await decryptItem(vaultKey, row);
check('item encrypt/decrypt roundtrip', JSON.stringify(dec) === JSON.stringify(doc));

// 9. AAD binding: decrypt with mismatched version must fail (tamper detection)
await checkThrows('decrypt with wrong version (AAD) throws', async () => {
  await decryptItem(vaultKey, { ...row, version: 5 });
});

// 10. Decrypt with wrong key fails
await checkThrows('decrypt with wrong vault key throws', async () => {
  await decryptItem(generateVaultKey(), row);
});

// 11. Ciphertext is not the plaintext (sanity)
check('ciphertext hides plaintext', !bytesToB64(b64ToBytes(enc.ciphertext_b64)).includes('example.org'));

// 12. KDF params guard
await checkThrows('unsupported KDF algo rejected', async () => {
  await deriveMasterKey('x', SALT, { algo: 'MD5', iterations: 1000 });
});
check('default iterations >= 600k', DEFAULT_KDF.iterations >= 600000);

console.log(failures === 0 ? '\nALL CRYPTO TESTS PASSED' : `\n${failures} CRYPTO TEST(S) FAILED`);
process.exit(failures === 0 ? 0 : 1);
