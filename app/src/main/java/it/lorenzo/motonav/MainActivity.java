package it.lorenzo.motonav;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Schermata principale: orologio, batteria, pulsanti destinazione, modalità moto. */
public class MainActivity extends Activity {

    private static final int ORANGE = 0xFFFF8C00;
    private static final int[] COLORS = {
            0xFF1E5AA8, 0xFF2E7D32, 0xFF6A1B9A, 0xFF00838F,
            0xFFAD1457, 0xFF5D4037, 0xFF37474F, 0xFF283593
    };

    private Store store;
    private LinearLayout grid;
    private TextView battery;
    private TextView motoBtn;
    private TextView vehMoto, vehScooter, dashBtn;
    private ImageView logo;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable live = new Runnable() {
        @Override
        public void run() {
            if (dashBtn != null && store.isScooter()) {
                dashBtn.setText(ScooterService.ready ? "CRUSCOTTO · " + ScooterService.summary() : "CRUSCOTTO SEGWAY");
            }
            ui.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            showBattery(i);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        showBattery(registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        renderDests();
        updateMoto();
        updateVehicle();
        ui.post(live);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(live);
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        // siamo la schermata Home: "indietro" non fa nulla
    }

    // ================= interfaccia =================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(6), dp(4), dp(6), dp(6));

        // --- intestazione: ora grande, data e batteria ---
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 54);
        clock.setTypeface(Typeface.DEFAULT_BOLD);
        clock.setIncludeFontPadding(false);
        head.addView(clock, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.END);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEE d MMM");
        date.setFormat12Hour("EEE d MMM");
        date.setTextColor(0xFFBBBBBB);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        side.addView(date);
        battery = new TextView(this);
        battery.setTextColor(Color.WHITE);
        battery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        battery.setTypeface(Typeface.DEFAULT_BOLD);
        battery.setGravity(Gravity.END);
        side.addView(battery);
        head.addView(side);
        root.addView(head);

        // --- interruttore veicolo: MOTO / MONOPATTINO ---
        LinearLayout veh = new LinearLayout(this);
        veh.setOrientation(LinearLayout.HORIZONTAL);
        vehMoto = bigButton("MOTO", 0xFF222222, 17);
        vehMoto.setOnClickListener(v -> switchVehicle("moto"));
        vehScooter = bigButton("MONOPATTINO", 0xFF222222, 17);
        vehScooter.setOnClickListener(v -> switchVehicle("monopattino"));
        veh.addView(vehMoto, cell(1f, 40));
        veh.addView(vehScooter, cell(1.4f, 40));
        root.addView(veh, new LinearLayout.LayoutParams(-1, -2));

        // --- pulsanti destinazione ---
        ScrollView sv = new ScrollView(this);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        sv.addView(grid);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        svLp.topMargin = dp(4);
        root.addView(sv, svLp);

        // --- cruscotto Segway (solo in modalità monopattino) ---
        dashBtn = bigButton("CRUSCOTTO SEGWAY", 0xFF00838F, 19);
        dashBtn.setOnClickListener(v -> startActivity(new Intent(this, ScooterActivity.class)));
        root.addView(dashBtn, cell(0f, 56));
        ((LinearLayout.LayoutParams) dashBtn.getLayoutParams()).width = -1;

        // --- barra in basso ---
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        motoBtn = bigButton("GUIDA", 0xFF333333, 20);
        motoBtn.setOnClickListener(v -> toggleMoto());
        TextView search = bigButton("CERCA", 0xFF333333, 20);
        search.setOnClickListener(v -> searchDialog());
        TextView apps = bigButton("APP", 0xFF333333, 20);
        apps.setOnClickListener(v -> startActivity(new Intent(this, AppsActivity.class)));
        TextView music = bigButton("♪", 0xFF2E7D32, 26);
        music.setOnClickListener(v -> startActivity(new Intent(this, RemoteActivity.class)));
        TextView gear = bigButton("⚙", 0xFF333333, 24);
        gear.setOnClickListener(v -> settingsDialog());
        bar.addView(motoBtn, cell(1.4f, 64));
        bar.addView(search, cell(1.2f, 64));
        bar.addView(apps, cell(0.9f, 64));
        bar.addView(music, cell(0.7f, 64));
        bar.addView(gear, cell(0.6f, 64));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.topMargin = dp(4);
        root.addView(bar, barLp);

        // sfondo: logo R1200R, grigio tenue nello spazio libero sopra la barra
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_r1200r);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setAlpha(0.32f);
        FrameLayout.LayoutParams logoLp = new FrameLayout.LayoutParams(-1, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        logoLp.setMargins(dp(18), 0, dp(18), dp(96));
        frame.addView(logo, logoLp);
        root.setBackgroundColor(Color.TRANSPARENT);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    private void renderDests() {
        grid.removeAllViews();
        final List<Store.Dest> list = store.loadDests();
        int n = list.size() + (list.size() < Store.MAX_DEST ? 1 : 0);
        LinearLayout row = null;
        for (int i = 0; i < n; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            TextView btn;
            if (i < list.size()) {
                final int idx = i;
                Store.Dest d = list.get(i);
                btn = bigButton(d.name, COLORS[i % COLORS.length], 26);
                if (d.isEmpty()) btn.setAlpha(0.55f);
                btn.setOnClickListener(v -> go(idx));
                btn.setOnLongClickListener(v -> {
                    editDialog(idx);
                    return true;
                });
            } else {
                btn = bigButton("+", 0xFF222222, 34);
                btn.setOnClickListener(v -> editDialog(-1));
            }
            row.addView(btn, cell(1f, 92));
        }
        // riga dispari: riempi lo spazio vuoto
        if (n % 2 == 1 && row != null) row.addView(new View(this), cell(1f, 92));
    }

    private void showBattery(Intent i) {
        if (i == null || battery == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (level < 0) return;
        int pct = Math.round(level * 100f / scale);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        battery.setText((charging ? "⚡" : "") + pct + "%");
        battery.setTextColor(pct <= 15 ? 0xFFFF5252 : pct <= 30 ? 0xFFFFC107 : Color.WHITE);
    }

    // ================= navigazione =================

    private void go(int idx) {
        List<Store.Dest> list = store.loadDests();
        if (idx >= list.size()) return;
        final Store.Dest d = list.get(idx);
        if (d.isEmpty()) {
            editDialog(idx);
            return;
        }
        if (d.hasCoords()) {
            Nav.go(this, store, d.lat, d.lon, d.name);
            return;
        }
        toast("Cerco " + d.query + "…");
        new Thread(() -> {
            final Geo.Result r = Geo.find(d.query);
            runOnUiThread(() -> {
                if (r != null) {
                    List<Store.Dest> l2 = store.loadDests();
                    if (idx < l2.size()) {
                        l2.get(idx).lat = r.lat;
                        l2.get(idx).lon = r.lon;
                        store.saveDests(l2);
                    }
                    Nav.go(this, store, r.lat, r.lon, d.name);
                } else {
                    // senza internet o indirizzo non trovato: lo cerca l'app di mappe
                    Nav.search(this, store, d.query);
                }
            });
        }).start();
    }

    private void searchDialog() {
        final EditText q = input("Dove vuoi andare?", "");
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Cerca")
                .setView(pad(q))
                .setPositiveButton("Naviga", (dlg, w) -> {
                    String s = q.getText().toString().trim();
                    if (!s.isEmpty()) navigateTo(s, s);
                })
                .setNeutralButton("Salva pulsante", (dlg, w) -> {
                    String s = q.getText().toString().trim();
                    if (!s.isEmpty()) addDest(s, s);
                })
                .setNegativeButton("Annulla", null)
                .show();
        showKeyboard(q);
    }

    private void navigateTo(final String name, final String query) {
        toast("Cerco " + query + "…");
        new Thread(() -> {
            final Geo.Result r = Geo.find(query);
            runOnUiThread(() -> {
                if (r != null) Nav.go(this, store, r.lat, r.lon, name);
                else Nav.search(this, store, query);
            });
        }).start();
    }

    // ================= modifica destinazioni =================

    private void editDialog(final int idx) {
        final List<Store.Dest> list = store.loadDests();
        final boolean isNew = idx < 0 || idx >= list.size();
        final Store.Dest d = isNew ? new Store.Dest("", "") : list.get(idx);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText name = input("Nome sul pulsante (es. Casa)", d.name);
        final EditText addr = input("Indirizzo o luogo (es. Via Roma 1, Milano)", d.query);
        box.addView(name);
        box.addView(addr);

        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(isNew ? "Nuova destinazione" : "Modifica destinazione")
                .setView(pad(box))
                .setPositiveButton("Salva", (dlg, w) -> {
                    String n = name.getText().toString().trim();
                    String a = addr.getText().toString().trim();
                    if (n.isEmpty()) n = a;
                    if (n.isEmpty()) return;
                    if (isNew) {
                        addDest(n, a);
                    } else {
                        Store.Dest cur = list.get(idx);
                        boolean changed = !a.equals(cur.query);
                        cur.name = n;
                        cur.query = a;
                        if (changed) {
                            cur.lat = Double.NaN;
                            cur.lon = Double.NaN;
                        }
                        store.saveDests(list);
                        renderDests();
                        if (changed && !a.isEmpty()) locate(idx, a);
                    }
                })
                .setNegativeButton("Annulla", null);
        if (!isNew) {
            b.setNeutralButton("Elimina", (dlg, w) -> {
                list.remove(idx);
                store.saveDests(list);
                renderDests();
            });
        }
        b.show();
        showKeyboard(isNew ? name : addr);
    }

    private void addDest(String name, String query) {
        List<Store.Dest> list = store.loadDests();
        if (list.size() >= Store.MAX_DEST) {
            toast("Massimo " + Store.MAX_DEST + " pulsanti: eliminane uno (tienilo premuto)");
            return;
        }
        list.add(new Store.Dest(name, query));
        store.saveDests(list);
        renderDests();
        if (!query.isEmpty()) locate(list.size() - 1, query);
    }

    /** Trova subito le coordinate, così poi la navigazione parte anche senza internet. */
    private void locate(final int idx, final String query) {
        new Thread(() -> {
            final Geo.Result r = Geo.find(query);
            runOnUiThread(() -> {
                if (r == null) {
                    toast("Indirizzo non trovato ora: riproverò quando premi il pulsante");
                    return;
                }
                List<Store.Dest> l = store.loadDests();
                if (idx < l.size() && query.equals(l.get(idx).query)) {
                    l.get(idx).lat = r.lat;
                    l.get(idx).lon = r.lon;
                    store.saveDests(l);
                }
                toast("Trovato: " + r.label);
            });
        }).start();
    }

    // ================= modalità moto =================

    private void toggleMoto() {
        ContentResolver cr = getContentResolver();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (!store.motoOn()) {
            if (!Settings.System.canWrite(this)) {
                toast("Consenti a MotoNav di modificare le impostazioni di sistema, poi torna indietro e premi GUIDA");
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            store.saveScreen(
                    Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 150),
                    Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0),
                    Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30000));
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 255);
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30 * 60 * 1000);

            if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                store.saveFilter(nm.getCurrentInterruptionFilter());
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            }
            store.setMotoOn(true);
            updateMoto();
            toast("Modalità guida: luminosità al massimo, schermo acceso 30 minuti");

            if (nm != null && !nm.isNotificationPolicyAccessGranted() && !store.askedDnd()) {
                store.setAskedDnd();
                toast("Per silenziare le notifiche in guida, consenti l'accesso \"Non disturbare\" a MotoNav");
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        } else {
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, store.savedBrightness());
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, store.savedBrightnessMode());
                Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, store.savedTimeout());
            }
            if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                int f = store.savedFilter();
                nm.setInterruptionFilter(f >= 1 ? f : NotificationManager.INTERRUPTION_FILTER_ALL);
            }
            store.setMotoOn(false);
            updateMoto();
            toast("Modalità guida spenta");
        }
    }

    private void updateMoto() {
        boolean on = store.motoOn();
        motoBtn.setText(on ? "GUIDA ON" : "GUIDA");
        motoBtn.setBackground(bg(on ? ORANGE : 0xFF333333));
        motoBtn.setTextColor(on ? Color.BLACK : Color.WHITE);
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ================= moto / monopattino =================

    private void switchVehicle(String v) {
        if (v.equals(store.vehicle())) return;
        store.setVehicle(v);
        updateVehicle();
        if (store.isScooter()) {
            toast("Monopattino: percorsi da bici e cruscotto Segway");
        } else {
            toast("Moto: percorsi da moto");
            if (ScooterService.instance != null) {
                startService(new Intent(this, ScooterService.class).setAction(ScooterService.ACTION_STOP));
            }
        }
    }

    private void updateVehicle() {
        boolean sc = store.isScooter();
        vehMoto.setBackground(bg(sc ? 0xFF222222 : ORANGE));
        vehMoto.setTextColor(sc ? 0xFF888888 : Color.BLACK);
        vehScooter.setBackground(bg(sc ? 0xFF26C6DA : 0xFF222222));
        vehScooter.setTextColor(sc ? Color.BLACK : 0xFF888888);
        dashBtn.setVisibility(sc ? View.VISIBLE : View.GONE);
        logo.setVisibility(sc ? View.GONE : View.VISIBLE);
    }

    // ================= impostazioni =================

    private void settingsDialog() {
        String navLabel;
        switch (store.navApp()) {
            case "osmand": navLabel = "OsmAnd"; break;
            case "organic": navLabel = "Organic Maps"; break;
            case "google": navLabel = "Google Maps"; break;
            default: navLabel = "Automatico";
        }
        String profLabel = "motorcycle".equals(store.profile()) ? "Moto" : "Auto";
        String[] items = {
                "Navigatore: " + navLabel,
                "Percorso in moto (OsmAnd): " + profLabel,
                "Scarica OsmAnd",
                "Usa MotoNav come schermata Home",
                "Impostazioni Android",
                "Dimentica il monopattino abbinato",
                "Versione " + versionName() + " · installati: " + Nav.installedLabel(this)
        };
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Impostazioni")
                .setItems(items, (dlg, which) -> {
                    switch (which) {
                        case 0: chooseNavApp(); break;
                        case 1:
                            store.setProfile("motorcycle".equals(store.profile()) ? "car" : "motorcycle");
                            settingsDialog();
                            break;
                        case 2: openUrl("https://f-droid.org/packages/net.osmand.plus/"); break;
                        case 3: openSettings(Settings.ACTION_HOME_SETTINGS); break;
                        case 4: openSettings(Settings.ACTION_SETTINGS); break;
                        case 5:
                            store.forgetScooter();
                            if (ScooterService.instance != null) {
                                startService(new Intent(this, ScooterService.class).setAction(ScooterService.ACTION_STOP));
                            }
                            toast("Monopattino dimenticato: al prossimo collegamento lo cercherà di nuovo");
                            break;
                        default: break;
                    }
                })
                .setNegativeButton("Chiudi", null)
                .show();
    }

    private void chooseNavApp() {
        final String[] keys = {"auto", "osmand", "organic", "google"};
        String[] labels = {"Automatico (prima OsmAnd)", "OsmAnd", "Organic Maps", "Google Maps"};
        int sel = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(store.navApp())) sel = i;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Navigatore")
                .setSingleChoiceItems(labels, sel, (dlg, which) -> {
                    store.setNavApp(keys[which]);
                    dlg.dismiss();
                    settingsDialog();
                })
                .show();
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Nessun browser trovato");
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ================= piccoli aiuti =================

    private TextView bigButton(String text, int color, int sp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setPadding(dp(4), dp(2), dp(4), dp(2));
        t.setBackground(bg(color));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private StateListDrawable bg(int color) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(ORANGE));
        s.addState(new int[]{}, round(color));
        return s;
    }

    private GradientDrawable round(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        return g;
    }

    private LinearLayout.LayoutParams cell(float weight, int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(heightDp), weight);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    private EditText input(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        e.setSelection(e.getText().length());
        return e;
    }

    private View pad(View v) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        box.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void showKeyboard(final EditText e) {
        e.requestFocus();
        e.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(e, 0);
        }, 200);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
