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
    private static final int CYAN = 0xFF26C6DA;
    private TextView dashBtn;
    private ImageView logo;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable live = new Runnable() {
        @Override
        public void run() {
            if (dashBtn != null && store.isScooter()) {
                dashBtn.setText(ScooterService.ready ? ScooterService.summary() + "   ›" : "Collega il Segway   ›");
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
        if (!store.setupShown()) {
            // primo avvio: si apre la configurazione guidata (permessi, OsmAnd, Home…)
            store.setSetupShown();
            startActivity(new Intent(this, SetupActivity.class));
        } else if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 5);
        }
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

    /** Colore della modalità: arancione in moto, azzurro in monopattino. */
    private int acc() {
        return store.isScooter() ? CYAN : ORANGE;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(6), dp(4), dp(6), dp(6));

        // --- riga in alto: icona del veicolo (tocca per cambiare) e batteria ---
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), 0, dp(2), 0);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(10), dp(3), dp(10), dp(3));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(22));
        pillBg.setStroke(dp(2), acc());
        StateListDrawable pillStates = new StateListDrawable();
        pillStates.addState(new int[]{android.R.attr.state_pressed}, round(0xFF333333));
        pillStates.addState(new int[]{}, pillBg);
        pill.setBackground(pillStates);
        pill.setClickable(true);
        ImageView icon = new ImageView(this);
        icon.setImageResource(store.isScooter() ? R.drawable.ic_scooter : R.drawable.ic_moto);
        icon.setColorFilter(acc());
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pill.addView(icon, new LinearLayout.LayoutParams(dp(store.isScooter() ? 34 : 54), dp(34)));
        TextView swap = new TextView(this);
        swap.setText(" ⇄");
        swap.setTextColor(0xFF8A8A8A);
        swap.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        pill.addView(swap);
        pill.setOnClickListener(v -> switchVehicle(store.isScooter() ? "moto" : "monopattino"));
        top.addView(pill, new LinearLayout.LayoutParams(-2, dp(44)));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        battery = new TextView(this);
        battery.setTextColor(Color.WHITE);
        battery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        battery.setTypeface(Typeface.DEFAULT_BOLD);
        battery.setGravity(Gravity.END);
        battery.setMinWidth(dp(46));
        top.addView(battery);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(48)));

        // --- monopattino: striscia con i dati del Segway (tocca per il cruscotto) ---
        dashBtn = new TextView(this);
        dashBtn.setText("Collega il Segway   ›");
        dashBtn.setTextColor(0xFF7FE6F2);
        dashBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        dashBtn.setTypeface(Typeface.DEFAULT_BOLD);
        dashBtn.setGravity(Gravity.CENTER_VERTICAL);
        dashBtn.setPadding(dp(14), 0, dp(14), 0);
        StateListDrawable dashBg = new StateListDrawable();
        dashBg.addState(new int[]{android.R.attr.state_pressed}, round(0xFF155A62));
        dashBg.addState(new int[]{}, round(0xFF0B2F33));
        dashBtn.setBackground(dashBg);
        dashBtn.setClickable(true);
        dashBtn.setOnClickListener(v -> startActivity(new Intent(this, ScooterActivity.class)));
        LinearLayout.LayoutParams dashLp = new LinearLayout.LayoutParams(-1, dp(42));
        dashLp.topMargin = dp(6);
        root.addView(dashBtn, dashLp);
        dashBtn.setVisibility(store.isScooter() ? View.VISIBLE : View.GONE);

        // --- destinazioni in elenco ---
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        sv.addView(grid);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        svLp.topMargin = dp(6);
        root.addView(sv, svLp);

        // --- barra in basso: GUIDA, CERCA, ♪, ⋯ ---
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        motoBtn = bigButton("GUIDA", 0xFF262626, 19);
        motoBtn.setOnClickListener(v -> toggleMoto());
        TextView search = bigButton("CERCA", 0xFF262626, 19);
        search.setOnClickListener(v -> searchDialog());
        TextView music = bigButton("♪", 0xFF262626, 24);
        music.setOnClickListener(v -> startActivity(new Intent(this, RemoteActivity.class)));
        TextView more = bigButton("⋯", 0xFF262626, 24);
        more.setOnClickListener(v -> moreDialog());
        dock.addView(motoBtn, cell(1.3f, 60));
        dock.addView(search, cell(1.3f, 60));
        dock.addView(music, cell(0.8f, 60));
        dock.addView(more, cell(0.7f, 60));
        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(-1, -2);
        dockLp.topMargin = dp(3);
        root.addView(dock, dockLp);

        // --- sfondo: logo R1200R in moto, sagoma del monopattino in monopattino ---
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        logo = new ImageView(this);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams logoLp;
        if (store.isScooter()) {
            logo.setImageResource(R.drawable.ic_scooter);
            logo.setAlpha(0.22f);
            logoLp = new FrameLayout.LayoutParams(-2, dp(60), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        } else {
            logo.setImageResource(R.drawable.logo_r1200r);
            logo.setAlpha(0.32f);
            logoLp = new FrameLayout.LayoutParams(-1, dp(26), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        }
        logoLp.setMargins(dp(18), 0, dp(18), dp(78));
        frame.addView(logo, logoLp);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    private void renderDests() {
        grid.removeAllViews();
        final List<Store.Dest> list = store.loadDests();
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            Store.Dest d = list.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), 0, dp(14), 0);
            row.setBackground(bg(0xFF1B1B1B));
            row.setClickable(true);

            View dot = new View(this);
            GradientDrawable dg = new GradientDrawable();
            dg.setShape(GradientDrawable.OVAL);
            dg.setColor(d.isEmpty() ? 0xFF444444 : acc());
            dot.setBackground(dg);
            row.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));

            TextView name = new TextView(this);
            name.setText(d.name);
            name.setTextColor(d.isEmpty() ? 0xFF8A8A8A : Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1f);
            nameLp.setMargins(dp(12), 0, dp(6), 0);
            row.addView(name, nameLp);

            if (d.isEmpty()) {
                TextView hint = new TextView(this);
                hint.setText("imposta");
                hint.setTextColor(0xFF888888);
                hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                hint.setPadding(0, 0, dp(8), 0);
                row.addView(hint);
            }
            TextView chev = new TextView(this);
            chev.setText("›");
            chev.setTextColor(0xFF555555);
            chev.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
            row.addView(chev);

            row.setOnClickListener(v -> go(idx));
            row.setOnLongClickListener(v -> {
                editDialog(idx);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(60));
            lp.bottomMargin = dp(6);
            grid.addView(row, lp);
        }
        if (list.size() < Store.MAX_DEST) {
            TextView add = new TextView(this);
            add.setText("+ Aggiungi destinazione");
            add.setTextColor(0xFF8A8A8A);
            add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            add.setGravity(Gravity.CENTER);
            GradientDrawable ag = new GradientDrawable();
            ag.setCornerRadius(dp(14));
            ag.setStroke(dp(2), 0xFF3A3A3A, dp(8), dp(6));
            StateListDrawable as = new StateListDrawable();
            as.addState(new int[]{android.R.attr.state_pressed}, round(0xFF262626));
            as.addState(new int[]{}, ag);
            add.setBackground(as);
            add.setClickable(true);
            add.setOnClickListener(v -> editDialog(-1));
            grid.addView(add, new LinearLayout.LayoutParams(-1, dp(42)));
        }
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
        if (!online()) {
            // senza internet: OsmAnd cerca l'indirizzo nelle mappe offline e parte col primo risultato
            Nav.searchNavigate(this, store, d.query, false);
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
                    // indirizzo non trovato online: lo cerca OsmAnd nelle mappe offline
                    Nav.searchNavigate(this, store, d.query, false);
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
        if (!online()) {
            // senza internet: OsmAnd cerca offline e mostra i risultati per scegliere
            Nav.searchNavigate(this, store, query, true);
            return;
        }
        toast("Cerco " + query + "…");
        new Thread(() -> {
            final Geo.Result r = Geo.find(query);
            runOnUiThread(() -> {
                if (r != null) Nav.go(this, store, r.lat, r.lon, name);
                else Nav.searchNavigate(this, store, query, true);
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
        if (!online()) {
            toast("Salvato. Senza internet l'indirizzo lo cerca OsmAnd nelle mappe offline quando premi il pulsante");
            return;
        }
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
            toast(GuidaService.secureLock(this)
                    ? "Guida attiva. Hai un PIN o una sequenza di blocco: per tornare subito alla mappa col tasto superiore impostala su \"Scorrimento\" o \"Nessuno\""
                    : "Guida attiva: tasto superiore per spegnere e riaccendere lo schermo, la navigazione continua");

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
        motoBtn.setBackground(bg(on ? acc() : 0xFF262626));
        motoBtn.setTextColor(on ? Color.BLACK : Color.WHITE);
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // in guida: niente schermata di blocco, così il tasto superiore riporta subito alla mappa
        Intent guida = new Intent(this, GuidaService.class);
        if (on) startService(guida);
        else stopService(guida);
    }

    // ================= moto / monopattino =================

    private void switchVehicle(String v) {
        if (v.equals(store.vehicle())) return;
        store.setVehicle(v);
        if (store.isScooter()) {
            toast("Monopattino: percorsi da bici e dati del Segway");
        } else {
            toast("Moto: percorsi da moto");
            if (ScooterService.instance != null) {
                startService(new Intent(this, ScooterService.class).setAction(ScooterService.ACTION_STOP));
            }
        }
        recreate(); // ridisegna tutto con l'icona e il colore della nuova modalità
    }

    private void updateVehicle() {
        if (dashBtn != null) dashBtn.setVisibility(store.isScooter() ? View.VISIBLE : View.GONE);
    }

    private void moreDialog() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setItems(new String[]{"Elenco app", "Impostazioni", "Configurazione iniziale"}, (d, w) -> {
                    if (w == 0) startActivity(new Intent(this, AppsActivity.class));
                    else if (w == 1) settingsDialog();
                    else startActivity(new Intent(this, SetupActivity.class));
                })
                .show();
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
                "Tasto fisico per Siri: " + (store.siriKey() < 0 ? "non impostato"
                        : android.view.KeyEvent.keyCodeToString(store.siriKey()).replace("KEYCODE_", "")),
                "Come attivare Siri: " + ("laterale".equals(store.siriMethod())
                        ? "tasto laterale tenuto" : "comando vocale"),
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
                        case 6: learnSiriKey(); break;
                        case 7:
                            store.setSiriMethod("laterale".equals(store.siriMethod()) ? "voce" : "laterale");
                            toast("laterale".equals(store.siriMethod())
                                    ? "Siri come tenere premuto il tasto laterale dell'iPhone (serve \"Premi tasto laterale per Siri\" attivo sull'iPhone)"
                                    : "Siri con il comando vocale Bluetooth");
                            settingsDialog();
                            break;
                        default: break;
                    }
                })
                .setNegativeButton("Chiudi", null)
                .show();
    }

    /** Fa scegliere il tasto fisico: serve il servizio di accessibilità "MotoNav Tasti". */
    private void learnSiriKey() {
        if (KeyService.instance == null) {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Attiva \"MotoNav Tasti\"")
                    .setMessage("Per usare un tasto fisico anche dentro OsmAnd, attiva \"MotoNav Tasti\" in Accessibilità. "
                            + "Poi torna qui e scegli di nuovo \"Tasto fisico per Siri\".")
                    .setPositiveButton("Apri Accessibilità", (d, w) -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    .setNegativeButton("Annulla", null)
                    .show();
            return;
        }
        KeyService.learning = true;
        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Tasto per Siri")
                .setMessage("Premi ora il tasto fisico dell'orologio che vuoi usare (non quello di accensione).\n\n"
                        + "Poi: pressione lunga = Siri, pressione breve = funzione normale del tasto.")
                .setNegativeButton("Annulla", (d, w) -> KeyService.learning = false)
                .setPositiveButton("Nessun tasto", (d, w) -> {
                    KeyService.learning = false;
                    store.setSiriKey(-1);
                })
                .show();
        final Runnable[] wait = new Runnable[1];
        wait[0] = () -> {
            if (!dlg.isShowing()) return;
            if (!KeyService.learning) dlg.dismiss();
            else ui.postDelayed(wait[0], 300);
        };
        ui.postDelayed(wait[0], 300);
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
        s.addState(new int[]{android.R.attr.state_pressed}, round(acc()));
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

    /** C'è una connessione a internet (Wi-Fi o SIM)? Sull'orologio di solito no. */
    private boolean online() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
