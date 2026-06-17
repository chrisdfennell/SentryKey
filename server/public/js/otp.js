/**
 * SentryKey Client-Side OTP Engine.
 * Implements Base32 decoding and RFC 6238 TOTP generation.
 * Operates client-side using the Web Crypto API (SubtleCrypto) for HMAC-SHA1.
 */
const SentryOTP = (() => {
  
  /**
   * Decodes a Base32 string into a Uint8Array.
   * Compatible with standard Base32 (RFC 4648).
   * @param {string} base32 
   * @returns {Uint8Array}
   */
  function decodeBase32(base32) {
    const allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    const clean = base32.toUpperCase()
      .replace(/\s+/g, "")
      .replace(/-+/g, "")
      .replace(/=+/g, ""); // strip padding & formatting
      
    const out = [];
    let bits = 0;
    let value = 0;
    
    for (let i = 0; i < clean.length; i++) {
      const char = clean[i];
      const lookup = allowedChars.indexOf(char);
      if (lookup < 0) continue; // skip non-base32 chars
      
      value = (value << 5) | lookup;
      bits += 5;
      
      if (bits >= 8) {
        bits -= 8;
        out.push((value >> bits) & 0xFF);
      }
    }
    
    return new Uint8Array(out);
  }

  /**
   * Calculates a 6-digit TOTP code for a given secret and timestamp.
   * @param {string} secret Base32 encoded secret key
   * @param {number} timeSeconds Unix timestamp in seconds
   * @returns {Promise<string>} 6-digit TOTP code
   */
  async function getTOTPCode(secret, timeSeconds) {
    try {
      const keyBytes = decodeBase32(secret);
      if (keyBytes.length === 0) return "000000";

      const epoch30 = Math.floor(timeSeconds / 30);

      // Convert the 30-second epoch counter to an 8-byte big-endian counter buffer
      const msgBytes = new Uint8Array(8);
      let temp = epoch30;
      for (let i = 7; i >= 0; i--) {
        msgBytes[i] = temp & 0xff;
        temp = Math.floor(temp / 256);
      }

      // Import the raw secret bytes as an HMAC Key using Web Crypto API
      const hmacKey = await window.crypto.subtle.importKey(
        "raw",
        keyBytes,
        {
          name: "HMAC",
          hash: { name: "SHA-1" }
        },
        false,
        ["sign"]
      );

      // Sign the big-endian counter
      const hashBuffer = await window.crypto.subtle.sign(
        "HMAC",
        hmacKey,
        msgBytes
      );
      const hash = new Uint8Array(hashBuffer);

      // Dynamic Truncation (RFC 4226 Section 5.3)
      const offset = hash[hash.length - 1] & 0x0f;
      const binary = ((hash[offset] & 0x7f) << 24) |
                     ((hash[offset + 1] & 0xff) << 16) |
                     ((hash[offset + 2] & 0xff) << 8) |
                     (hash[offset + 3] & 0xff);

      const otp = binary % 1000000;
      let result = otp.toString();
      while (result.length < 6) {
        result = "0" + result;
      }
      return result;
    } catch (e) {
      console.error("TOTP calculation failed:", e);
      return "000000";
    }
  }

  return {
    decodeBase32,
    getTOTPCode
  };
})();

// Export for Node.js test environment if active, otherwise bind to window
if (typeof module !== "undefined" && module.exports) {
  module.exports = SentryOTP;
} else {
  window.SentryOTP = SentryOTP;
}
