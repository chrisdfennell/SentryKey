import Toybox.WatchUi;
import Toybox.Lang;

class SentryKeyDelegate extends WatchUi.BehaviorDelegate {
    private var view as SentryKeyView;

    function initialize(mainView as SentryKeyView) {
        BehaviorDelegate.initialize();
        view = mainView;
    }

    // Capture physical down button or swipe down gesture
    function onNextPage() as Boolean {
        view.nextAccount();
        return true;
    }

    // Capture physical up button or swipe up gesture
    function onPreviousPage() as Boolean {
        view.previousAccount();
        return true;
    }
}
