import Toybox.Application;
import Toybox.Application.Storage;
import Toybox.WatchUi;
import Toybox.Graphics;
import Toybox.System;
import Toybox.Math;
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

        // Calculate progress in current 30-second window
        var epochTime = Time.now().value();
        var secondsInWindow = epochTime % 30;
        var progress = (30.0 - secondsInWindow) / 30.0;
        var secondsRemaining = 30 - secondsInWindow;

        // Premium dark background, shared across MIP and AMOLED
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        if (vault.size() == 0) {
            // Branded empty-vault screen
            dc.setColor(Graphics.COLOR_ORANGE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(width / 2, height / 2 - 28, Graphics.FONT_SMALL, "SENTRYKEY", Graphics.TEXT_JUSTIFY_CENTER);
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(width / 2, height / 2, Graphics.FONT_MEDIUM, "Vault is empty", Graphics.TEXT_JUSTIFY_CENTER);
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(width / 2, height / 2 + 30, Graphics.FONT_XTINY, "Sync via SentryKey app", Graphics.TEXT_JUSTIFY_CENTER);
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

        drawTokenFace(dc, formattedCode, label, progress, secondsRemaining);
    }

    // Premium dark token face: perimeter countdown ring, account label, big code.
    // Used for both MIP and AMOLED (the dark theme suits both and stays consistent).
    private function drawTokenFace(dc as Graphics.Dc, codeStr as String, labelStr as String, progress as Float, secondsRemaining as Number) as Void {
        var width = dc.getWidth();
        var height = dc.getHeight();
        var cx = width / 2;
        var cy = height / 2;
        var radius = (width / 2) - 8;

        // Accent turns red in the final 5 seconds as an expiry cue
        var accent = (secondsRemaining <= 5) ? Graphics.COLOR_RED : Graphics.COLOR_ORANGE;

        // Perimeter countdown ring: dim full-circle track + bright depleting arc
        var startAngle = 90; // 12 o'clock
        var endAngle = (90 - (progress * 360)).toNumber() % 360;
        if (endAngle < 0) {
            endAngle += 360;
        }
        dc.setPenWidth(6);
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawCircle(cx, cy, radius);
        dc.setColor(accent, Graphics.COLOR_TRANSPARENT);
        dc.drawArc(cx, cy, radius, Graphics.ARC_CLOCKWISE, startAngle, endAngle);

        // Account label near the top, shrunk/ellipsized to fit the round screen
        var labelY = height / 3;
        var labelFit = fitLabel(dc, labelStr, chordWidth(width, height, labelY) - 12);
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, labelY, labelFit[0] as Graphics.FontDefinition, labelFit[1] as String, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // 6-digit code, bold and bright, centered
        var codeFont = getLargestFont(dc, codeStr, width - 60);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, cy, codeFont, codeStr, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Seconds-remaining readout below the code in the accent color
        dc.setColor(accent, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, (height * 2) / 3, Graphics.FONT_TINY, secondsRemaining + "s", Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Page dots when more than one account is stored
        drawPageDots(dc, width, height);
    }

    // Small dots indicating which account is active when the vault has several
    private function drawPageDots(dc as Graphics.Dc, width as Number, height as Number) as Void {
        var count = vault.size();
        if (count <= 1 || count > 8) {
            return;
        }
        var spacing = 10;
        var totalWidth = (count - 1) * spacing;
        var startX = (width / 2) - (totalWidth / 2);
        var dotY = (height * 4) / 5;
        for (var i = 0; i < count; i++) {
            if (i == activeIndex) {
                dc.setColor(Graphics.COLOR_ORANGE, Graphics.COLOR_TRANSPARENT);
                dc.fillCircle(startX + (i * spacing), dotY, 3);
            } else {
                dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
                dc.fillCircle(startX + (i * spacing), dotY, 2);
            }
        }
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

    // Horizontal width available inside a round screen at vertical position y
    private function chordWidth(width as Number, height as Number, y as Number) as Number {
        var r = width / 2.0;
        var dy = (height / 2.0) - y;
        var inside = (r * r) - (dy * dy);
        if (inside < 0) {
            inside = 0.0;
        }
        return (2 * Math.sqrt(inside)).toNumber();
    }

    // Pick the largest text font that fits widthLimit; if even the smallest
    // overflows, ellipsize the string. Returns [font, text].
    private function fitLabel(dc as Graphics.Dc, text as String, widthLimit as Number) as Array {
        var fonts = [
            Graphics.FONT_MEDIUM,
            Graphics.FONT_SMALL,
            Graphics.FONT_TINY,
            Graphics.FONT_XTINY
        ] as Array<Graphics.FontDefinition>;

        for (var i = 0; i < fonts.size(); i++) {
            if (dc.getTextWidthInPixels(text, fonts[i]) <= widthLimit) {
                return [fonts[i], text] as Array;
            }
        }

        // Smallest font still overflows: trim characters and append an ellipsis
        var smallest = fonts[fonts.size() - 1];
        var truncated = text;
        while (truncated.length() > 1 &&
               dc.getTextWidthInPixels(truncated + "...", smallest) > widthLimit) {
            truncated = truncated.substring(0, truncated.length() - 1);
        }
        return [smallest, truncated + "..."] as Array;
    }

    // Computes the current TOTP code. The crypto lives in the Totp module
    // (pure, unit-tested in CryptoTests.mc); this just supplies the current time.
    private function generateTOTP(secret as String) as String {
        return Totp.generateAt(secret, Time.now().value());
    }

}
