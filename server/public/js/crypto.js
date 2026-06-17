/**
 * SentryKey Client-Side Cryptographic Engine.
 * Implements PBKDF2 key derivation, AES-256-GCM encryption/decryption,
 * and zero-knowledge auth/encryption key derivation.
 */
const SentryCrypto = (() => {
  const KDF_ALGO = "PBKDF2";
  const HASH_ALGO = "SHA-256";
  const CIPHER_ALGO = "AES-GCM";
  const KEY_LENGTH = 256; // bits
  const SALT_LENGTH = 16; // bytes
  const IV_LENGTH = 12;   // bytes
  const TAG_LENGTH = 128; // bits
  const DEFAULT_ITERATIONS = 210000;

  // --- Helpers ---
  
  function bytesToBase64(bytes) {
    let binary = "";
    const len = bytes.byteLength;
    for (let i = 0; i < len; i++) {
      binary += String.fromCharCode(bytes[i]);
    }
    return window.btoa(binary);
  }

  function base64ToBytes(b64) {
    const cleanB64 = b64.trim().replace(/\s/g, "");
    const binary = window.atob(cleanB64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  }

  function bytesToHex(bytes) {
    return Array.from(bytes)
      .map(b => b.toString(16).padStart(2, '0'))
      .join('');
  }

  // --- Key Derivation ---

  async function deriveKey(passphrase, saltBytes, iterations) {
    const encoder = new TextEncoder();
    const passphraseKey = await window.crypto.subtle.importKey(
      "raw",
      encoder.encode(passphrase),
      { name: KDF_ALGO },
      false,
      ["deriveKey"]
    );

    return window.crypto.subtle.deriveKey(
      {
        name: KDF_ALGO,
        salt: saltBytes,
        iterations: iterations,
        hash: HASH_ALGO
      },
      passphraseKey,
      { name: CIPHER_ALGO, length: KEY_LENGTH },
      false,
      ["encrypt", "decrypt"]
    );
  }

  // --- Zero-Knowledge Multi-User Key Derivation ---

  /**
   * Derives separate authentication and vault encryption keys from a username and password.
   * @param {string} username 
   * @param {string} password 
   * @returns {Promise<{ authKey: string, encKey: Uint8Array }>}
   */
  async function deriveUserKeys(username, password) {
    const cleanUsername = username.trim().toLowerCase();
    const encoder = new TextEncoder();

    // 1. PBKDF2 stretching of the password using the username as salt
    const baseKey = await window.crypto.subtle.importKey(
      "raw",
      encoder.encode(password),
      { name: KDF_ALGO },
      false,
      ["deriveBits"]
    );

    const masterKeyBuffer = await window.crypto.subtle.deriveBits(
      {
        name: KDF_ALGO,
        salt: encoder.encode(cleanUsername),
        iterations: DEFAULT_ITERATIONS,
        hash: HASH_ALGO
      },
      baseKey,
      KEY_LENGTH
    );

    // 2. Import the stretched master key to perform HMAC derivations
    const hmacMasterKey = await window.crypto.subtle.importKey(
      "raw",
      masterKeyBuffer,
      { name: "HMAC", hash: { name: HASH_ALGO } },
      false,
      ["sign"]
    );

    // 3. Derive Auth Key (sent to server)
    const authBuffer = await window.crypto.subtle.sign(
      "HMAC",
      hmacMasterKey,
      encoder.encode("auth-key")
    );
    const authKey = bytesToHex(new Uint8Array(authBuffer));

    // 4. Derive Vault Encryption Key (remains in browser memory)
    const encBuffer = await window.crypto.subtle.sign(
      "HMAC",
      hmacMasterKey,
      encoder.encode("encryption-key")
    );
    const encKey = new Uint8Array(encBuffer);

    return { authKey, encKey };
  }

  // --- Zero-Knowledge Account Recovery ---

  /** A readable one-time recovery key (~100 bits), e.g. ABCDE-FGHJK-LMNPQ-RSTUV. */
  function generateRecoveryKey() {
    const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no ambiguous 0/O/1/I
    const bytes = window.crypto.getRandomValues(new Uint8Array(20));
    let out = "";
    for (let i = 0; i < bytes.length; i++) {
      out += alphabet[bytes[i] % alphabet.length];
      if ((i + 1) % 5 === 0 && i < bytes.length - 1) out += "-";
    }
    return out;
  }

  function randomSalt() {
    return window.crypto.getRandomValues(new Uint8Array(SALT_LENGTH));
  }

  /**
   * Derives a wrapping key (stays on device) and an auth key (proves possession
   * to the server) from a recovery key + salt. Mirrors deriveUserKeys.
   * @returns {Promise<{ wrapKey: Uint8Array, authKey: string }>}
   */
  async function deriveRecovery(recoveryKey, saltBytes) {
    const encoder = new TextEncoder();
    const normalized = recoveryKey.replace(/[\s-]/g, "").toUpperCase();
    const base = await window.crypto.subtle.importKey(
      "raw", encoder.encode(normalized), { name: KDF_ALGO }, false, ["deriveBits"]
    );
    const masterBuf = await window.crypto.subtle.deriveBits(
      { name: KDF_ALGO, salt: saltBytes, iterations: DEFAULT_ITERATIONS, hash: HASH_ALGO },
      base, KEY_LENGTH
    );
    const hmacKey = await window.crypto.subtle.importKey(
      "raw", masterBuf, { name: "HMAC", hash: { name: HASH_ALGO } }, false, ["sign"]
    );
    const wrapBuf = await window.crypto.subtle.sign("HMAC", hmacKey, encoder.encode("recovery-wrap"));
    const authBuf = await window.crypto.subtle.sign("HMAC", hmacKey, encoder.encode("recovery-auth"));
    return { wrapKey: new Uint8Array(wrapBuf), authKey: bytesToHex(new Uint8Array(authBuf)) };
  }

  /** AES-GCM wrap of raw key bytes; returns base64(iv || ciphertext+tag). */
  async function wrapBytes(plainBytes, wrapKeyBytes) {
    const iv = window.crypto.getRandomValues(new Uint8Array(IV_LENGTH));
    const key = await window.crypto.subtle.importKey("raw", wrapKeyBytes, { name: CIPHER_ALGO }, false, ["encrypt"]);
    const ct = await window.crypto.subtle.encrypt({ name: CIPHER_ALGO, iv: iv, tagLength: TAG_LENGTH }, key, plainBytes);
    const ctBytes = new Uint8Array(ct);
    const combined = new Uint8Array(iv.length + ctBytes.length);
    combined.set(iv, 0);
    combined.set(ctBytes, iv.length);
    return bytesToBase64(combined);
  }

  /** Reverses wrapBytes. Throws if the wrap key is wrong. */
  async function unwrapBytes(blobB64, wrapKeyBytes) {
    const combined = base64ToBytes(blobB64);
    const iv = combined.slice(0, IV_LENGTH);
    const ct = combined.slice(IV_LENGTH);
    const key = await window.crypto.subtle.importKey("raw", wrapKeyBytes, { name: CIPHER_ALGO }, false, ["decrypt"]);
    const plain = await window.crypto.subtle.decrypt({ name: CIPHER_ALGO, iv: iv, tagLength: TAG_LENGTH }, key, ct);
    return new Uint8Array(plain);
  }

  // --- Encryption/Decryption ---

  /**
   * Encrypts plaintext using a raw derived key bytes directly.
   * @param {string} plaintext 
   * @param {Uint8Array} encKeyBytes 
   * @returns {Promise<string>} SentryKey skbackup JSON
   */
  async function encryptWithKey(plaintext, encKeyBytes) {
    const salt = window.crypto.getRandomValues(new Uint8Array(SALT_LENGTH));
    const iv = window.crypto.getRandomValues(new Uint8Array(IV_LENGTH));
    
    // Import the raw derived encryption key bytes into SubtleCrypto
    const key = await window.crypto.subtle.importKey(
      "raw",
      encKeyBytes,
      { name: CIPHER_ALGO },
      false,
      ["encrypt"]
    );

    const encoder = new TextEncoder();
    const ciphertextBuffer = await window.crypto.subtle.encrypt(
      {
        name: CIPHER_ALGO,
        iv: iv,
        tagLength: TAG_LENGTH
      },
      key,
      encoder.encode(plaintext)
    );

    const ciphertextBytes = new Uint8Array(ciphertextBuffer);

    const envelope = {
      app: "SentryKey",
      version: 1,
      encrypted: true,
      kdf: "PBKDF2WithHmacSHA256",
      iterations: DEFAULT_ITERATIONS,
      salt: bytesToBase64(salt),
      iv: bytesToBase64(iv),
      ciphertext: bytesToBase64(ciphertextBytes)
    };

    return JSON.stringify(envelope, null, 2);
  }

  /**
   * Decrypts an skbackup JSON envelope using the raw derived key bytes directly.
   * @param {string} envelopeJson 
   * @param {Uint8Array} encKeyBytes 
   * @returns {Promise<string>} Plaintext vault string
   */
  async function decryptWithKey(envelopeJson, encKeyBytes) {
    let envelope;
    try {
      envelope = JSON.parse(envelopeJson);
    } catch (e) {
      throw new Error("Invalid SentryKey backup: Failed to parse envelope JSON.");
    }

    if (envelope.app !== "SentryKey" || !envelope.encrypted) {
      throw new Error("Invalid SentryKey backup: Envelope metadata mismatch.");
    }

    const iv = base64ToBytes(envelope.iv);
    const ciphertext = base64ToBytes(envelope.ciphertext);

    // Import the raw derived encryption key bytes into SubtleCrypto
    const key = await window.crypto.subtle.importKey(
      "raw",
      encKeyBytes,
      { name: CIPHER_ALGO },
      false,
      ["decrypt"]
    );

    try {
      const decryptedBuffer = await window.crypto.subtle.decrypt(
        {
          name: CIPHER_ALGO,
          iv: iv,
          tagLength: TAG_LENGTH
        },
        key,
        ciphertext
      );

      const decoder = new TextDecoder();
      return decoder.decode(decryptedBuffer);
    } catch (e) {
      throw new Error("Decryption failed. The file is corrupt or password doesn't match.");
    }
  }

  // --- Password-Based Legacy/Standalone Encryption/Decryption ---

  async function encrypt(plaintext, passphrase) {
    const salt = window.crypto.getRandomValues(new Uint8Array(SALT_LENGTH));
    const iv = window.crypto.getRandomValues(new Uint8Array(IV_LENGTH));
    const iterations = DEFAULT_ITERATIONS;

    const key = await deriveKey(passphrase, salt, iterations);

    const encoder = new TextEncoder();
    const ciphertextBuffer = await window.crypto.subtle.encrypt(
      {
        name: CIPHER_ALGO,
        iv: iv,
        tagLength: TAG_LENGTH
      },
      key,
      encoder.encode(plaintext)
    );

    const ciphertextBytes = new Uint8Array(ciphertextBuffer);

    const envelope = {
      app: "SentryKey",
      version: 1,
      encrypted: true,
      kdf: "PBKDF2WithHmacSHA256",
      iterations: iterations,
      salt: bytesToBase64(salt),
      iv: bytesToBase64(iv),
      ciphertext: bytesToBase64(ciphertextBytes)
    };

    return JSON.stringify(envelope, null, 2);
  }

  async function decrypt(envelopeJson, passphrase) {
    let envelope;
    try {
      envelope = JSON.parse(envelopeJson);
    } catch (e) {
      throw new Error("Invalid SentryKey backup: Failed to parse envelope.");
    }

    const salt = base64ToBytes(envelope.salt);
    const iv = base64ToBytes(envelope.iv);
    const ciphertext = base64ToBytes(envelope.ciphertext);
    const iterations = envelope.iterations || DEFAULT_ITERATIONS;

    const key = await deriveKey(passphrase, salt, iterations);

    try {
      const decryptedBuffer = await window.crypto.subtle.decrypt(
        {
          name: CIPHER_ALGO,
          iv: iv,
          tagLength: TAG_LENGTH
        },
        key,
        ciphertext
      );

      const decoder = new TextDecoder();
      return decoder.decode(decryptedBuffer);
    } catch (e) {
      throw new Error("Wrong passphrase or corrupt backup.");
    }
  }

  return {
    encrypt,
    decrypt,
    deriveUserKeys,
    encryptWithKey,
    decryptWithKey,
    generateRecoveryKey,
    randomSalt,
    deriveRecovery,
    wrapBytes,
    unwrapBytes,
    bytesToBase64,
    base64ToBytes,
    bytesToHex
  };
})();

// Export for Node.js test environment if active, otherwise bind to window
if (typeof module !== "undefined" && module.exports) {
  module.exports = SentryCrypto;
} else {
  window.SentryCrypto = SentryCrypto;
}
