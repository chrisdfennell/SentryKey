import Toybox.Lang;
import Toybox.Test;

// Known-answer tests for the watch's hand-rolled crypto (the riskiest code in
// the project: pure Monkey C SHA-1 / HMAC-SHA1 / Base32 / RFC 6238 TOTP). The
// vectors come from RFC 3174 / 2202 / 4648 / 6238 — the SAME TOTP table the
// phone, Wear, and server assert, so every platform provably agrees.
//
// These run in the Connect IQ simulator (the (:test) functions are stripped
// from release builds). Locally:
//   1) start the sim:  <sdk>/bin/connectiq
//   2) build a test prg:  monkeyc -f monkey.jungle -o bin/test.prg -y developer_key.der -d fenix8solar51mm --unit-test
//   3) run it:  monkeydo bin/test.prg fenix8solar51mm -t

(:test)
function test_sha1_abc(logger as Test.Logger) as Boolean {
    var got = tHex(Totp.computeSha1(tAscii("abc")));
    logger.debug("SHA1(abc) = " + got);
    return got.equals("a9993e364706816aba3e25717850c26c9cd0d89d");
}

(:test)
function test_sha1_empty(logger as Test.Logger) as Boolean {
    return tHex(Totp.computeSha1(tAscii(""))).equals("da39a3ee5e6b4b0d3255bfef95601890afd80709");
}

(:test)
function test_hmac_sha1_rfc2202(logger as Test.Logger) as Boolean {
    // RFC 2202 HMAC-SHA1 test case 2.
    var mac = Totp.computeHmacSha1(tAscii("Jefe"), tAscii("what do ya want for nothing?"));
    logger.debug("HMAC-SHA1 = " + tHex(mac));
    return tHex(mac).equals("effcdf6ae5eb2fa2d27416d5f184df9c259a7c79");
}

(:test)
function test_base32_decodes_rfc_seed(logger as Test.Logger) as Boolean {
    // RFC 6238 seed "12345678901234567890" -> hex of those 20 ASCII bytes.
    return tHex(Totp.decodeBase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
        .equals("3132333435363738393031323334353637383930");
}

(:test)
function test_totp_rfc6238(logger as Test.Logger) as Boolean {
    var seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    // 6-digit codes = last six digits of RFC 6238 Appendix B's 8-digit values.
    var ok = Totp.generateAt(seed, 59).equals("287082")
        && Totp.generateAt(seed, 1111111109).equals("081804")
        && Totp.generateAt(seed, 1111111111).equals("050471")
        && Totp.generateAt(seed, 1234567890).equals("005924")
        && Totp.generateAt(seed, 2000000000).equals("279037");
    if (!ok) {
        logger.error("TOTP@59 = " + Totp.generateAt(seed, 59) + " (expected 287082)");
    }
    return ok;
}

// ---- helpers (global, only referenced by the tests above) ----

// ASCII/UTF-8 String -> ByteArray, stripping a trailing null if the SDK adds one.
function tAscii(s as String) as ByteArray {
    var arr = s.toUtf8Array();
    var n = arr.size();
    if (n > 0 && arr[n - 1] == 0) { n -= 1; }
    var out = new [n]b;
    for (var i = 0; i < n; i++) {
        out[i] = arr[i] & 0xFF;
    }
    return out;
}

// ByteArray -> lowercase hex string.
function tHex(bytes as ByteArray) as String {
    var digits = "0123456789abcdef";
    var s = "";
    for (var i = 0; i < bytes.size(); i++) {
        var bb = bytes[i] & 0xFF;
        var hi = bb >> 4;
        var lo = bb & 0x0F;
        s = s + digits.substring(hi, hi + 1) + digits.substring(lo, lo + 1);
    }
    return s;
}
