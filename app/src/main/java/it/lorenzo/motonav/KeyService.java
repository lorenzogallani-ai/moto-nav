package it.lorenzo.motonav;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Ascolta i tasti fisici dell'orologio anche quando sei dentro OsmAnd o altre app.
 * Sul tasto scelto: pressione lunga = Siri sull'iPhone, pressione breve = funzione normale del tasto.
 */
public class KeyService extends AccessibilityService {

    static volatile boolean learning = false;
    static KeyService instance;

    private static final long LONG_PRESS = 600;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Store store;
    private boolean fired = false;
    private boolean down = false;

    private final Runnable longPress = () -> {
        fired = true;
        RemoteService.siri(this);
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        store = new Store(this);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_UNKNOWN) return false;

        if (learning) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                learning = false;
                store.setSiriKey(code);
                Toast.makeText(this, "Tasto per Siri salvato (" + KeyEvent.keyCodeToString(code)
                        + "). Tienilo premuto per attivare Siri.", Toast.LENGTH_LONG).show();
            }
            return true;
        }

        if (store == null || code != store.siriKey()) return false;

        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0 && !down) {
                down = true;
                fired = false;
                h.postDelayed(longPress, LONG_PRESS);
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            down = false;
            h.removeCallbacks(longPress);
            if (!fired) normalAction(code);
            return true;
        }
        return true;
    }

    /** Pressione breve: il tasto fa quello che faceva prima. */
    private void normalAction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case KeyEvent.KEYCODE_HOME:
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case KeyEvent.KEYCODE_APP_SWITCH:
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            default:
                break;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
