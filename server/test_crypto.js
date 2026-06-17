// Node.js test script to verify SentryCrypto and SentryOTP implementation.
// Mocks the browser environment (Web Crypto, atob/btoa) to execute in Node.

// Mock window and browser APIs
const { webcrypto } = require('crypto');
global.window = {
  crypto: {
    subtle: webcrypto.subtle,
    getRandomValues: (array) => webcrypto.getRandomValues(array)
  },
  atob: (str) => Buffer.from(str, 'base64').toString('binary'),
  btoa: (str) => Buffer.from(str, 'binary').toString('base64')
};

// Load our client-side libraries
const SentryCrypto = require('./public/js/crypto.js');
const SentryOTP = require('./public/js/otp.js');

async function runTests() {
  console.log("=========================================");
  console.log(" Running SentryKey Web Cryptography Tests ");
  console.log("=========================================\n");

  let passed = 0;
  let failed = 0;

  function assert(condition, message) {
    if (condition) {
      console.log(`[PASS] ${message}`);
      passed++;
    } else {
      console.error(`[FAIL] ${message}`);
      failed++;
    }
  }

  // --- Test 1: Base32 Decoding ---
  try {
    const rawSecret = "JBSWY3DPEHPK3PXP"; 
    const decoded = SentryOTP.decodeBase32(rawSecret);
    const hex = Buffer.from(decoded).toString('hex');
    assert(hex === "48656c6c6f21deadbeef", `Base32 decode matches standard: expected "48656c6c6f21deadbeef", got "${hex}"`);
  } catch (e) {
    console.error(e);
    assert(false, "Base32 decoding threw an exception");
  }

  // --- Test 2: TOTP Code Generation ---
  try {
    const secret = "JBSWY3DPEHPK3PXP";
    const time = 59; // Time in seconds
    const code = await SentryOTP.getTOTPCode(secret, time);
    assert(code === "996554", `TOTP code calculation matches standard: expected "996554", got "${code}"`);
    
    const time2 = 1111111111;
    const code2 = await SentryOTP.getTOTPCode(secret, time2);
    assert(code2 === "358462", `TOTP code calculation at high timestamp: expected "358462", got "${code2}"`);
  } catch (e) {
    console.error(e);
    assert(false, "TOTP generation threw an exception");
  }

  // --- Test 3: Legacy Passphrase-based Backup Round-Trip ---
  try {
    const vaultPlaintext = JSON.stringify({
      app: "SentryKey",
      version: 1,
      accounts: [
        { label: "GitHub: testuser", secret: "KRSXG5CTMVRXEZLU" }
      ]
    });
    
    const password = "correct horse battery staple";
    const envelopeJson = await SentryCrypto.encrypt(vaultPlaintext, password);
    const decrypted = await SentryCrypto.decrypt(envelopeJson, password);
    assert(decrypted === vaultPlaintext, "Legacy passphrase decrypted plaintext matches original");
  } catch (e) {
    console.error(e);
    assert(false, "Legacy passphrase encrypt/decrypt threw exception");
  }

  // --- Test 4: Zero-Knowledge Multi-User Key Derivation & Encryption ---
  try {
    const username = "alice";
    const password = "supersecuremasterpassword";
    const vaultPlaintext = JSON.stringify({
      app: "SentryKey",
      version: 1,
      accounts: [
        { label: "GitHub: alice", secret: "KRSXG5CTMVRXEZLU" },
        { label: "Slack: workspace", secret: "JBSWY3DPEHPK3PXP" }
      ]
    });

    console.log("Deriving Zero-Knowledge User Keys for 'alice'...");
    const keys = await SentryCrypto.deriveUserKeys(username, password);
    
    assert(keys.authKey !== undefined && keys.authKey.length === 64, "Derived authKey is a 64-character hex string (SHA-256 hash size)");
    assert(keys.encKey !== undefined && keys.encKey.length === 32, "Derived encKey is a 32-byte key (256 bits AES-GCM key size)");
    
    console.log("Encrypting vault using derived encryption key...");
    const envelopeJson = await SentryCrypto.encryptWithKey(vaultPlaintext, keys.encKey);
    const envelope = JSON.parse(envelopeJson);
    
    assert(envelope.app === "SentryKey", "Key-encrypted envelope contains 'app': 'SentryKey'");
    assert(envelope.encrypted === true, "Key-encrypted envelope indicates 'encrypted': true");
    assert(envelope.ciphertext !== undefined, "Key-encrypted envelope contains ciphertext");
    
    console.log("Decrypting vault using derived encryption key...");
    const decrypted = await SentryCrypto.decryptWithKey(envelopeJson, keys.encKey);
    assert(decrypted === vaultPlaintext, "Decrypted plaintext matches original plaintext when using derived key");

    // Test that Bob's derived encryption key cannot decrypt Alice's envelope
    console.log("Deriving keys for a different user 'bob'...");
    const bobKeys = await SentryCrypto.deriveUserKeys("bob", password); // Same password, different username (salt)
    
    assert(keys.authKey !== bobKeys.authKey, "Auth key for 'alice' and 'bob' are different even with same password");
    assert(Buffer.from(keys.encKey).toString('hex') !== Buffer.from(bobKeys.encKey).toString('hex'), "Encryption key for 'alice' and 'bob' are different even with same password");
    
    try {
      await SentryCrypto.decryptWithKey(envelopeJson, bobKeys.encKey);
      assert(false, "Decryption of Alice's envelope using Bob's key should fail");
    } catch (err) {
      assert(true, `Decryption of Alice's envelope with Bob's key fails as expected: "${err.message}"`);
    }

  } catch (e) {
    console.error(e);
    assert(false, "Zero-Knowledge user key derivation / encryption tests threw exception");
  }

  console.log("\n=========================================");
  console.log(` Test Summary: ${passed} Passed, ${failed} Failed`);
  console.log("=========================================");
  
  process.exit(failed > 0 ? 1 : 0);
}

runTests();
