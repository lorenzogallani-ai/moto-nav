package it.lorenzo.motonav;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Cruscotto del monopattino: velocità grande, batteria, autonomia, modalità ECO/D/S, fari. */
public class ScooterActivity extends Activity {

    private static final int ORANGE = 0xFFFF8C00;
    private static final int TEAL = 0xFF00838F;

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView statusView, speedView, unitView, batView, rangeView, lightBtn;
    private View batFill, batRest;
    private final TextView[] modeBtns = new TextView[3];
    private static final int[] MODE_OF_BTN = {ScooterService.MODE_ECO, ScooterService.MODE_DRIVE, ScooterService.MODE_SPORT};

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            h.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(6), dp(4), dp(6), dp(6));

        statusView = new TextView(this);
        statusView.setTextColor(0xFFDDDDDD);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(4), dp(2), dp(4), dp(2));
        statusView.setOnClickListener(v -> {
            if (!ScooterService.ready) start();
        });
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        // velocità
        LinearLayout sp = new LinearLayout(this);
        sp.setOrientation(LinearLayout.HORIZONTAL);
        sp.setGravity(Gravity.CENTER);
        speedView = text("--", 104, Color.WHITE, true);
        speedView.setIncludeFontPadding(false);
        unitView = text(" km/h", 22, 0xFFBBBBBB, true);
        sp.addView(speedView);
        sp.addView(unitView);
        root.addView(sp, new LinearLayout.LayoutParams(-1, 0, 1f));

        // batteria e autonomia
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        batView = text("--%", 26, Color.WHITE, true);
        info.addView(batView, new LinearLayout.LayoutParams(dp(78), -2));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(round(0xFF2A2A2A, 6));
        batFill = new View(this);
        batFill.setBackground(round(0xFF43A047, 6));
        batRest = new View(this);
        bar.addView(batFill, new LinearLayout.LayoutParams(0, -1, 0f));
        bar.addView(batRest, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, dp(18), 1f);
        barLp.setMargins(dp(4), 0, dp(4), 0);
        info.addView(bar, barLp);
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));

        rangeView = text("Autonomia -- km", 22, 0xFFDDDDDD, false);
        rangeView.setGravity(Gravity.CENTER);
        rangeView.setPadding(0, dp(4), 0, dp(4));
        root.addView(rangeView, new LinearLayout.LayoutParams(-1, -2));

        // modalità di guida
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"ECO", "D", "S"};
        for (int i = 0; i < 3; i++) {
            final int m = MODE_OF_BTN[i];
            modeBtns[i] = button(labels[i], 0xFF333333, 26);
            modeBtns[i].setOnClickListener(v -> command(ScooterService.ACTION_MODE, m));
            modes.addView(modeBtns[i], cell(1f, 60));
        }
        root.addView(modes, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        lightBtn = button("FARI", 0xFF333333, 20);
        lightBtn.setOnClickListener(v -> command(ScooterService.ACTION_LIGHT, 0));
        TextView home = button("◀ HOME", 0xFF333333, 20);
        home.setOnClickListener(v -> finish());
        bottom.addView(lightBtn, cell(1f, 56));
        bottom.addView(home, cell(1f, 56));
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && new Store(this).scooterMac() == null) {
            // su Android 7 la ricerca Bluetooth richiede il permesso di posizione
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 3);
        } else {
            start();
        }
        h.post(tick);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 4 && res == RESULT_OK) startService(new Intent(this, ScooterService.class));
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    private void start() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad != null && !ad.isEnabled()) {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 4);
            } catch (Exception ignored) {
            }
            return;
        }
        if (ScooterService.instance == null || !ScooterService.ready) {
            startService(new Intent(this, ScooterService.class));
        }
    }

    private void command(String action, int mode) {
        if (!ScooterService.ready) return;
        startService(new Intent(this, ScooterService.class).setAction(action).putExtra(ScooterService.EXTRA_MODE, mode));
    }

    private void render() {
        boolean ok = ScooterService.ready;
        if (ok) {
            String n = ScooterService.scooterName;
            statusView.setText(ScooterService.error != 0
                    ? "⚠ Errore " + ScooterService.error + " sul monopattino"
                    : "● " + (n != null ? n : "Collegato"));
            statusView.setTextColor(ScooterService.error != 0 ? 0xFFFF5252 : 0xFF81C784);
        } else {
            boolean idle = ScooterService.instance == null;
            statusView.setText(idle ? "Non collegato · tocca qui per RIPROVA" : ScooterService.status);
            statusView.setTextColor(ScooterService.needButton ? ORANGE : 0xFFDDDDDD);
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ScooterService.needButton ? 20 : 15);
        }

        double s = ScooterService.speed;
        speedView.setText(ok && !Double.isNaN(s) ? String.valueOf(Math.round(s)) : "--");

        int bat = ScooterService.battery;
        batView.setText(ok && bat >= 0 ? bat + "%" : "--%");
        batView.setTextColor(bat >= 0 && bat <= 15 ? 0xFFFF5252 : bat >= 0 && bat <= 30 ? 0xFFFFC107 : Color.WHITE);
        float f = ok && bat >= 0 ? Math.max(0, Math.min(100, bat)) / 100f : 0f;
        ((LinearLayout.LayoutParams) batFill.getLayoutParams()).weight = f;
        ((LinearLayout.LayoutParams) batRest.getLayoutParams()).weight = 1f - f;
        batFill.setBackground(round(bat >= 0 && bat <= 15 ? 0xFFE53935 : bat >= 0 && bat <= 30 ? 0xFFFFB300 : 0xFF43A047, 6));
        batFill.requestLayout();

        double r = ScooterService.rangeKm;
        rangeView.setText("Autonomia " + (ok && !Double.isNaN(r) ? Math.round(r) + " km" : "-- km"));

        int m = ScooterService.mode;
        for (int i = 0; i < 3; i++) {
            boolean on = ok && m == MODE_OF_BTN[i];
            modeBtns[i].setBackground(states(on ? ORANGE : 0xFF333333));
            modeBtns[i].setTextColor(on ? Color.BLACK : ok ? Color.WHITE : 0xFF777777);
        }
        boolean light = ok && ScooterService.funWord >= 0 && (ScooterService.funWord & 1) == 1;
        lightBtn.setText(light ? "FARI ON" : "FARI");
        lightBtn.setBackground(states(light ? 0xFFFFEB3B : 0xFF333333));
        lightBtn.setTextColor(light ? Color.BLACK : ok ? Color.WHITE : 0xFF777777);
    }

    // ---------------- aiuti ----------------

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String s, int color, int sp) {
        TextView t = text(s, sp, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(states(color));
        t.setClickable(true);
        return t;
    }

    private StateListDrawable states(int color) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(TEAL, 14));
        s.addState(new int[]{}, round(color, 14));
        return s;
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private LinearLayout.LayoutParams cell(float w, int hDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(hDp), w);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
