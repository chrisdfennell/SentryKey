import Toybox.Application;
import Toybox.Application.Storage;
import Toybox.Application.Properties;
import Toybox.Lang;
import Toybox.WatchUi;
import Toybox.Communications;

class SentryKeyApp extends Application.AppBase {
    private var view as SentryKeyView? = null;

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
                var parsedData = parseVaultString(seedStr as String);
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

        if (seedStr != null) {
            var parsedData = parseVaultString(seedStr);
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
            var colonIndex = pair.find(":");
            if (colonIndex != null) {
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
