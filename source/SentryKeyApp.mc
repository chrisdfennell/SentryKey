import Toybox.Application;
import Toybox.Application.Storage;
import Toybox.Application.Properties;
import Toybox.Lang;
import Toybox.WatchUi;
import Toybox.Communications;

class SentryKeyApp extends Application.AppBase {
    private var view as SentryKeyView? = null;

    // Sentinel the phone sends to request a watch -> phone recovery pull instead
    // of pushing a vault. MUST stay in sync with the Android/iOS companions
    // (GarminSyncManager pullRequest). A real Base32 secret can never produce
    // this string, so it can't collide with a legitimate vault push.
    private const PULL_REQUEST = "__SENTRYKEY_PULL__";

    function initialize() {
        AppBase.initialize();
    }

    // onStart() is called on application start up
    function onStart(state as Dictionary?) as Void {
        parseAndStoreVault();
        Communications.registerForPhoneAppMessages(method(:onAppMessage));
    }

    function onAppMessage(mail as Communications.PhoneAppMessage) as Void {
        try {
            var seedStr = mail.data;
            if (seedStr != null && seedStr instanceof Lang.String) {
                var str = seedStr as String;
                // Recovery pull: the phone is asking the watch to send its vault
                // back (e.g. after a lost/stolen phone). Gate it behind an
                // on-watch confirmation so a stolen watch can't silently hand
                // over every secret.
                if (str.equals(PULL_REQUEST)) {
                    WatchUi.pushView(
                        new WatchUi.Confirmation("Send vault to phone?"),
                        new PullConfirmDelegate(),
                        WatchUi.SLIDE_IMMEDIATE
                    );
                    return;
                }
                var parsedData = parseVaultString(str);
                Storage.setValue("vault", parsedData);
                if (view != null) {
                    view.reloadVault();
                }
                WatchUi.requestUpdate();
            }
        } catch (e) {
            // Prevent crashes from invalid data transmissions
        }
    }

    // Serializes the stored vault back into the "label:secret,label:secret" sync
    // string and transmits it to the companion phone app. Called only after the
    // user confirms the on-watch recovery prompt (PullConfirmDelegate).
    function sendVaultToPhone() as Void {
        var vault = null;
        try {
            vault = Storage.getValue("vault");
        } catch (e) {
            vault = null;
        }
        Communications.transmit(serializeVault(vault), null, new VaultTransmitListener());
    }

    private function serializeVault(vault as Lang.Object?) as String {
        var out = "";
        if (vault == null || !(vault instanceof Lang.Array)) {
            return out;
        }
        var arr = vault as Array;
        for (var i = 0; i < arr.size(); i++) {
            var entry = arr[i] as Dictionary;
            var label = entry["label"];
            var secret = entry["secret"];
            if (label != null && secret != null) {
                if (out.length() > 0) {
                    out = out + ",";
                }
                out = out + label + ":" + secret;
            }
        }
        return out;
    }

    // onStop() is called when your application is exiting
    function onStop(state as Dictionary?) as Void {
    }

    function onSettingsChanged() as Void {
        parseAndStoreVault();
        if (view != null) {
            view.reloadVault();
        }
        WatchUi.requestUpdate();
    }

    // Return the initial view of your application here
    function getInitialView() as [Views] or [Views, InputDelegates] {
        var mainView = new SentryKeyView();
        view = mainView;
        var delegate = new SentryKeyDelegate(mainView);
        return [ mainView, delegate ];
    }

    // Parses the settings string and saves it securely to storage
    private function parseAndStoreVault() as Void {
        var seedStr = null;
        try {
            seedStr = Properties.getValue("cryptoSeeds");
        } catch (e) {
            // Properties might not exist yet in simulator
        }

        // Only let the app-settings string seed the vault when it actually
        // contains data. The property defaults to "" (empty, not null), so an
        // unconditional write here would wipe a vault that was synced over BLE
        // (onAppMessage) every time the app restarts.
        if (seedStr != null && seedStr instanceof Lang.String && (seedStr as String).length() > 0) {
            var parsedData = parseVaultString(seedStr as String);
            Storage.setValue("vault", parsedData);
        }
    }

    // Custom helper to parse vaults of form "label:secret,label:secret"
    private function parseVaultString(seedStr as String) as Array< Dictionary<String, String> > {
        var vault = [] as Array< Dictionary<String, String> >;
        if (seedStr == null || seedStr.length() == 0) {
            return vault;
        }

        var pairs = splitString(seedStr, ",");
        for (var i = 0; i < pairs.size(); i++) {
            var pair = pairs[i];
            // Split on the LAST colon, not the first: QR-scanned labels often
            // contain a colon (e.g. "Discord:username"), while a Base32 secret
            // never does, so the final colon is always the true delimiter.
            var colonIndex = lastIndexOf(pair, ":");
            if (colonIndex >= 0) {
                var label = pair.substring(0, colonIndex);
                var secret = pair.substring(colonIndex + 1, pair.length());
                
                label = trimString(label);
                secret = trimString(secret);
                
                if (label.length() > 0 && secret.length() > 0) {
                    var entry = {
                        "label" => label,
                        "secret" => secret
                    } as Dictionary<String, String>;
                    vault.add(entry);
                }
            }
        }
        return vault;
    }

    // Trim string whitespaces helper
    private function trimString(str as String) as String {
        var start = 0;
        var end = str.length();
        while (start < end && isWhitespace(str.substring(start, start + 1))) {
            start++;
        }
        while (end > start && isWhitespace(str.substring(end - 1, end))) {
            end--;
        }
        return str.substring(start, end);
    }

    // Check if character is whitespace helper
    private function isWhitespace(char as String) as Boolean {
        return char.equals(" ") || char.equals("\t") || char.equals("\r") || char.equals("\n");
    }

    // Return the index of the last occurrence of a single-character needle, or -1
    private function lastIndexOf(str as String, needle as String) as Number {
        var found = -1;
        for (var i = 0; i < str.length(); i++) {
            if (str.substring(i, i + 1).equals(needle)) {
                found = i;
            }
        }
        return found;
    }

    // Split string helper
    private function splitString(str as String, delimiter as String) as Array<String> {
        var result = [] as Array<String>;
        var index = str.find(delimiter);
        while (index != null) {
            var part = str.substring(0, index);
            result.add(part);
            str = str.substring(index + delimiter.length(), str.length());
            index = str.find(delimiter);
        }
        result.add(str);
        return result;
    }
}

function getApp() as SentryKeyApp {
    return Application.getApp() as SentryKeyApp;
}

// Confirms an on-watch recovery request before any secrets leave the device.
// Requires a physical button press on the watch itself.
class PullConfirmDelegate extends WatchUi.ConfirmationDelegate {
    function initialize() {
        ConfirmationDelegate.initialize();
    }

    function onResponse(response as WatchUi.Confirm) as Boolean {
        if (response == WatchUi.CONFIRM_YES) {
            getApp().sendVaultToPhone();
        }
        return true;
    }
}

// transmit() requires a ConnectionListener. Result reporting is the phone's job
// (it knows whether the reply arrived via its own receive callback / timeout),
// so these are intentionally silent.
class VaultTransmitListener extends Communications.ConnectionListener {
    function initialize() {
        ConnectionListener.initialize();
    }

    function onComplete() as Void {
    }

    function onError() as Void {
    }
}
