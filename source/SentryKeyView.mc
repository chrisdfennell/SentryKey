import Toybox.Application;
import Toybox.Application.Storage;
import Toybox.WatchUi;
import Toybox.Graphics;
import Toybox.System;
import Toybox.Time;
import Toybox.Time.Gregorian;
import Toybox.Timer;
import Toybox.Lang;

class SentryKeyView extends WatchUi.View {
    private var vault as Array< Dictionary<String, String> > = [] as Array< Dictionary<String, String> >;
    private var activeIndex as Number = 0;
    private var timer as Timer.Timer? = null;

    function initialize() {
        View.initialize();
        loadVaultFromStorage();
    }

    // Load vault data on start
    private function loadVaultFromStorage() as Void {
        var data = Storage.getValue("vault");
        if (data != null && data instanceof Array) {
            vault = data as Array< Dictionary<String, String> >;
        } else {
            vault = [] as Array< Dictionary<String, String> >;
        }
        activeIndex = 0;
    }

    // Reload vault when settings change
    public function reloadVault() as Void {
        loadVaultFromStorage();
    }

    // Cycle to next account in vault
    public function nextAccount() as Void {
        if (vault.size() > 1) {
            activeIndex = (activeIndex + 1) % vault.size();
            WatchUi.requestUpdate();
        }
    }

    // Cycle to previous account in vault
    public function previousAccount() as Void {
        if (vault.size() > 1) {
            activeIndex = (activeIndex - 1 + vault.size()) % vault.size();
            WatchUi.requestUpdate();
        }
    }

    // Called when this View is brought to the foreground
    function onShow() as Void {
        timer = new Timer.Timer();
        timer.start(method(:onTimer), 1000, true);
    }

    // Called when this View is removed from the screen
    function onHide() as Void {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    // Timer callback triggers screen refresh
    function onTimer() as Void {
        WatchUi.requestUpdate();
    }

    // Main drawing routine
    function onUpdate(dc as Graphics.Dc) as Void {
        var width = dc.getWidth();
        var height = dc.getHeight();

        // Check screen capabilities for MIP vs AMOLED
        var deviceSettings = System.getDeviceSettings();
        var isAmoled = false;
        if (deviceSettings has :requiresBurnInProtection) {
            isAmoled = deviceSettings.requiresBurnInProtection;
        }

        // Calculate progress in current 30-second window
        var epochTime = Time.now().value();
        var secondsInWindow = epochTime % 30;
        var progress = (30.0 - secondsInWindow) / 30.0;

        if (vault.size() == 0) {
            // Draw empty vault screen
            if (isAmoled) {
                dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
                dc.clear();
                dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
                dc.drawText(width / 2, height / 2 - 30, Graphics.FONT_MEDIUM, "Vault is empty", Graphics.TEXT_JUSTIFY_CENTER);
                dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
                dc.drawText(width / 2, height / 2 + 10, Graphics.FONT_SMALL, "Sync via SentryKey App", Graphics.TEXT_JUSTIFY_CENTER);
            } else {
                dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_WHITE);
                dc.clear();
                dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
                dc.drawText(width / 2, height / 2 - 30, Graphics.FONT_MEDIUM, "Vault is empty", Graphics.TEXT_JUSTIFY_CENTER);
                dc.drawText(width / 2, height / 2 + 10, Graphics.FONT_SMALL, "Sync via SentryKey App", Graphics.TEXT_JUSTIFY_CENTER);
            }
            return;
        }

        // Get current active token
        var entry = vault[activeIndex];
        var label = entry.get("label") as String;
        var secret = entry.get("secret") as String;
        var code = generateTOTP(secret);

        // Format code as "123 456" for premium presentation
        var formattedCode = code;
        if (code.length() == 6) {
            formattedCode = code.substring(0, 3) + " " + code.substring(3, 6);
        }

        if (isAmoled) {
            drawAMOLED(dc, formattedCode, label, progress, secondsInWindow);
        } else {
            drawMIP(dc, formattedCode, label, progress);
        }

        // TEMP DEBUG: raw UTC epoch seconds the watch is feeding to the TOTP.
        // Compare against true UTC to isolate clock skew vs an algorithm bug.
        dc.setColor(isAmoled ? Graphics.COLOR_LT_GRAY : Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(width / 2, height - 18, Graphics.FONT_XTINY, "t=" + epochTime.toString(), Graphics.TEXT_JUSTIFY_CENTER);
    }

    // Adaptive layout for Solar Elite MIP (White background, black text, thick horizontal bar)
    private function drawMIP(dc as Graphics.Dc, codeStr as String, labelStr as String, progress as Float) as Void {
        var width = dc.getWidth();
        var height = dc.getHeight();

        // MIP Solar Elite Background: clean white
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_WHITE);
        dc.clear();

        // Foreground: stark solid black
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);

        // Render Account Label
        dc.drawText(width / 2, height / 4, Graphics.FONT_MEDIUM, labelStr, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Render 6-digit code in largest thick native font that fits
        var codeFont = getLargestFont(dc, codeStr, width - 40);
        dc.drawText(width / 2, height / 2, codeFont, codeStr, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Draw solid thick loading bar in the lower third of the screen
        var barY = (height * 2) / 3 + 15;
        var barHeight = 12;
        var maxBarWidth = width - 80;
        var barWidth = (maxBarWidth * progress).toNumber();
        var barX = (width - maxBarWidth) / 2;

        // Draw track outline
        dc.setPenWidth(2);
        dc.drawRectangle(barX, barY, maxBarWidth, barHeight);

        // Fill progress
        if (barWidth > 0) {
            dc.fillRectangle(barX + 2, barY + 2, barWidth - 4, barHeight - 4);
        }
    }

    // Adaptive layout for AMOLED (Pure black background, circular arc, antialiased amber/white)
    private function drawAMOLED(dc as Graphics.Dc, codeStr as String, labelStr as String, progress as Float, secondsRemaining as Number) as Void {
        var width = dc.getWidth();
        var height = dc.getHeight();

        // AMOLED power-saving strict true black background
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = width / 2;
        var cy = height / 2;
        var radius = (width / 2) - 10;

        // Perimeter circular countdown arc
        var startAngle = 90; // 12 o'clock
        var endAngle = (90 - (progress * 360)).toNumber() % 360;
        if (endAngle < 0) {
            endAngle += 360;
        }

        // Draw dim track circle (amber/gray)
        dc.setPenWidth(4);
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawCircle(cx, cy, radius);

        // Draw active winding down arc (amber/orange glow)
        dc.setColor(Graphics.COLOR_ORANGE, Graphics.COLOR_TRANSPARENT);
        dc.drawArc(cx, cy, radius, Graphics.ARC_CLOCKWISE, startAngle, endAngle);

        // Render Account Label in dim light gray
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(width / 2, height / 3, Graphics.FONT_MEDIUM, labelStr, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Render 6-digit code in bright white
        var codeFont = getLargestFont(dc, codeStr, width - 60);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(width / 2, height / 2, codeFont, codeStr, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Render numeric seconds counter below code in bright yellow
        dc.setColor(Graphics.COLOR_YELLOW, Graphics.COLOR_TRANSPARENT);
        dc.drawText(width / 2, (height * 2) / 3, Graphics.FONT_SMALL, (30 - secondsRemaining) + "s", Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    // Helper to dynamically select the largest native font without horizontal overflow
    private function getLargestFont(dc as Graphics.Dc, text as String, widthLimit as Number) as Graphics.FontDefinition {
        var fonts = [
            Graphics.FONT_NUMBER_THAI_HOT,
            Graphics.FONT_NUMBER_HOT,
            Graphics.FONT_NUMBER_MEDIUM,
            Graphics.FONT_NUMBER_MILD,
            Graphics.FONT_LARGE
        ] as Array<Graphics.FontDefinition>;

        for (var i = 0; i < fonts.size(); i++) {
            var fontWidth = dc.getTextWidthInPixels(text, fonts[i]);
            if (fontWidth < widthLimit) {
                return fonts[i];
            }
        }
        return Graphics.FONT_MEDIUM;
    }

    // Computes the TOTP code
    private function generateTOTP(secret as String) as String {
        try {
            var keyBytes = decodeBase32(secret);
            if (keyBytes.size() == 0) {
                return "ERRKEY";
            }

            // Get UTC UNIX epoch time
            var epochTime = Time.now().value();
            var timeStep = epochTime / 30;

            // Message is the 8-byte big-endian representation of timeStep
            var messageBytes = new [8]b;
            messageBytes[0] = 0;
            messageBytes[1] = 0;
            messageBytes[2] = 0;
            messageBytes[3] = 0;
            messageBytes[4] = ((timeStep >> 24) & 0xFF);
            messageBytes[5] = ((timeStep >> 16) & 0xFF);
            messageBytes[6] = ((timeStep >> 8) & 0xFF);
            messageBytes[7] = (timeStep & 0xFF);

            // Compute HMAC-SHA1 using custom helper utilizing supported native Hash class
            var hmacResult = computeHmacSha1(keyBytes, messageBytes);

            // Dynamic truncation to extract 6-digit code
            var offset = (hmacResult[19] & 0xFF) & 0x0F;
            var binary = ((hmacResult[offset] & 0x7F) << 24) |
                         ((hmacResult[offset + 1] & 0xFF) << 16) |
                         ((hmacResult[offset + 2] & 0xFF) << 8) |
                         (hmacResult[offset + 3] & 0xFF);

            var otp = binary % 1000000;
            return otp.format("%06d");
        } catch (e) {
            return "ERRTOTP";
        }
    }

    // Custom HMAC-SHA1 implementation built on a pure Monkey C SHA-1.
    // Avoids Toybox.Cryptography, whose Hash primitive throws an uncatchable
    // fault for SHA-1 on some fenix8 firmware, crashing the app.
    private function computeHmacSha1(keyBytes as ByteArray, messageBytes as ByteArray) as ByteArray {
        var blockSize = 64; // SHA-1 block size

        // 1. If key is longer than block size, hash it first
        var formattedKey = keyBytes;
        if (formattedKey.size() > blockSize) {
            formattedKey = computeSha1(formattedKey);
        }

        // 2. Pad key to block size (64 bytes), then build ipad/opad keys
        var ipadKey = new [blockSize]b;
        var opadKey = new [blockSize]b;
        for (var i = 0; i < blockSize; i++) {
            var keyByte = (i < formattedKey.size()) ? formattedKey[i] : 0;
            ipadKey[i] = (keyByte ^ 0x36) & 0xFF;
            opadKey[i] = (keyByte ^ 0x5C) & 0xFF;
        }

        // 3. Inner hash H(ipad || message), then outer hash H(opad || inner)
        var innerDigest = computeSha1(concatBytes(ipadKey, messageBytes));
        return computeSha1(concatBytes(opadKey, innerDigest));
    }

    // Concatenate two byte arrays into a new ByteArray
    private function concatBytes(a as ByteArray, b as ByteArray) as ByteArray {
        var result = new [a.size() + b.size()]b;
        for (var i = 0; i < a.size(); i++) {
            result[i] = a[i];
        }
        for (var j = 0; j < b.size(); j++) {
            result[a.size() + j] = b[j];
        }
        return result;
    }

    // Rotate a 32-bit value left by n bits (operates on a masked Long)
    private function rotl32(x as Long, n as Number) as Long {
        x = x & 0xFFFFFFFFl;
        return ((x << n) | (x >> (32 - n))) & 0xFFFFFFFFl;
    }

    // Pure Monkey C SHA-1. Uses 64-bit Long arithmetic masked to 32 bits so
    // there is no dependency on the native Cryptography module.
    private function computeSha1(msg as ByteArray) as ByteArray {
        var mask = 0xFFFFFFFFl;

        // Build the padded message as an array of byte values
        var data = [] as Array<Number>;
        var msgLen = msg.size();
        for (var i = 0; i < msgLen; i++) {
            data.add(msg[i] & 0xFF);
        }
        data.add(0x80);
        while (data.size() % 64 != 56) {
            data.add(0x00);
        }
        // 64-bit big-endian bit length (TOTP inputs are small: high 32 bits are 0)
        var bitLen = msgLen * 8;
        data.add(0x00);
        data.add(0x00);
        data.add(0x00);
        data.add(0x00);
        data.add((bitLen >> 24) & 0xFF);
        data.add((bitLen >> 16) & 0xFF);
        data.add((bitLen >> 8) & 0xFF);
        data.add(bitLen & 0xFF);

        var h0 = 0x67452301l;
        var h1 = 0xEFCDAB89l;
        var h2 = 0x98BADCFEl;
        var h3 = 0x10325476l;
        var h4 = 0xC3D2E1F0l;

        var numChunks = data.size() / 64;
        for (var chunk = 0; chunk < numChunks; chunk++) {
            var w = new [80] as Array<Long>;
            for (var i = 0; i < 16; i++) {
                var base = chunk * 64 + i * 4;
                w[i] = ((data[base].toLong() << 24) |
                        (data[base + 1].toLong() << 16) |
                        (data[base + 2].toLong() << 8) |
                        data[base + 3].toLong()) & mask;
            }
            for (var i = 16; i < 80; i++) {
                var x = (w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16]) & mask;
                w[i] = rotl32(x, 1);
            }

            var a = h0;
            var b = h1;
            var c = h2;
            var d = h3;
            var e = h4;

            for (var i = 0; i < 80; i++) {
                var f;
                var k;
                if (i < 20) {
                    f = ((b & c) | ((~b & mask) & d)) & mask;
                    k = 0x5A827999l;
                } else if (i < 40) {
                    f = (b ^ c ^ d) & mask;
                    k = 0x6ED9EBA1l;
                } else if (i < 60) {
                    f = ((b & c) | (b & d) | (c & d)) & mask;
                    k = 0x8F1BBCDCl;
                } else {
                    f = (b ^ c ^ d) & mask;
                    k = 0xCA62C1D6l;
                }
                var temp = (rotl32(a, 5) + f + e + k + w[i]) & mask;
                e = d;
                d = c;
                c = rotl32(b, 30);
                b = a;
                a = temp;
            }

            h0 = (h0 + a) & mask;
            h1 = (h1 + b) & mask;
            h2 = (h2 + c) & mask;
            h3 = (h3 + d) & mask;
            h4 = (h4 + e) & mask;
        }

        var out = new [20]b;
        var words = [h0, h1, h2, h3, h4] as Array<Long>;
        for (var i = 0; i < 5; i++) {
            var word = words[i];
            out[i * 4] = ((word >> 24) & 0xFF).toNumber();
            out[i * 4 + 1] = ((word >> 16) & 0xFF).toNumber();
            out[i * 4 + 2] = ((word >> 8) & 0xFF).toNumber();
            out[i * 4 + 3] = (word & 0xFF).toNumber();
        }
        return out;
    }

    // Base32 Decoding algorithm implemented in pure Monkey C
    private function decodeBase32(secret as String) as ByteArray {
        var base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        var upperSecret = secret.toUpper();
        var bytes = [] as Array<Number>;

        var buffer = 0;
        var bitsLeft = 0;

        for (var i = 0; i < upperSecret.length(); i++) {
            var charStr = upperSecret.substring(i, i + 1);
            if (charStr.equals("=")) {
                break;
            }
            var val = base32Chars.find(charStr);
            if (val == null) {
                continue; // Skip padding or invalid characters
            }

            buffer = ((buffer << 5) | val) & 0xFFFF;
            bitsLeft += 5;

            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                var b = (buffer >> bitsLeft) & 0xFF;
                bytes.add(b);
            }
        }

        // Transfer bytes into a native Connect IQ ByteArray
        var byteArray = new [bytes.size()]b;
        for (var idx = 0; idx < bytes.size(); idx++) {
            byteArray[idx] = bytes[idx];
        }
        return byteArray;
    }
}
