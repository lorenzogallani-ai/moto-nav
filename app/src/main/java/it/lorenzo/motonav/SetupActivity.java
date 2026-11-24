package it.lorenzo.motonav;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Configurazione iniziale: ogni riga è un permesso o un'impostazione.
 * Tocchi la riga, si apre la schermata giusta di Android, torni indietro e diventa verde.
 */
public class SetupActivity extends Activity {

    private static final int ORANGE = 0xFFFF8C00;
    private LinearLayout list;
    private Store store;

    private interface Check { boolean ok(); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));

        TextView title = new TextView(this);
        title.setText("Configurazione");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(8), 0, 0, dp(2));
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Tocca ogni riga arancione, sistema e torna indietro.");
        sub.setTextColor(0xFF9A9A9A);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setPadding(dp(8), 0, 0, dp(6));
        root.addView(sub);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView done = new TextView(this);
        done.setText("FATTO");
        done.setTextColor(Color.BLACK);
        done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setGravity(Gravity.CENTER);
        done.setBackground(states(ORANGE));
        done.setClickable(true);
        done.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, dp(56));
        dl.topMargin = dp(6);
        root.addView(done, dl);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        render();
    }

    private void render() {
        list.removeAllViews();
        final NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        row("Posizione", "Serve a OsmAnd e alla ricerca degli indirizzi offline",
                () -> Build.VERSION.SDK_INT < 23
                        || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                () -> {
                    if (Build.VERSION.SDK_INT >= 23)
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 9);
                });

        row("OsmAnd installato", "Il navigatore con le mappe offline (si apre la pagina per scaricarlo)",
                () -> Nav.installed(this, Nav.OSMAND_PLUS) || Nav.installed(this, Nav.OSMAND),
                () -> open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/net.osmand.plus/"))));

        row("MotoNav come schermata Home", "Scegli MotoNav e \"Sempre\"",
                this::isHome,
                () -> open(new Intent(Settings.ACTION_HOME_SETTINGS)));

        row("Luminosità", "Permesso \"Modifica impostazioni di sistema\" per il tasto GUIDA",
                () -> Settings.System.canWrite(this),
                () -> open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))));

        row("Non disturbare", "Per silenziare le notifiche in guida: attiva MotoNav",
                () -> nm != null && nm.isNotificationPolicyAccessGranted(),
                () -> open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));

        row("Tasto fisico per Siri", "In Accessibilità attiva \"MotoNav Tasti\", poi scegli il tasto da ⋯ → Impostazioni",
                () -> KeyService.instance != null,
                () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        row("Blocco schermo senza PIN", "Imposta \"Scorrimento\" o \"Nessuno\": il tasto superiore ti riporta subito alla mappa",
                () -> !GuidaService.secureLock(this),
                () -> open(new Intent(Settings.ACTION_SECURITY_SETTINGS)));

        row("Sempre attiva in sottofondo", "Esclude MotoNav dal risparmio batteria, così Bluetooth e cruscotto non si fermano a schermo spento",
                () -> {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
                },
                () -> open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()))));

        row("Bluetooth acceso", "Per il telecomando iPhone e il Segway",
                () -> {
                    BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                    return ad != null && ad.isEnabled();
                },
                () -> open(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)));

        row("Casa e Ufficio", "Nella Home tieni premuto il pulsante e scrivi l'indirizzo",
                () -> {
                    for (Store.Dest d : store.loadDests()) if (d.isEmpty()) return false;
                    return true;
                },
                () -> {
                    Toast.makeText(this, "Tieni premuto Casa o Ufficio nella Home", Toast.LENGTH_LONG).show();
                    finish();
                });
    }

    private boolean isHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void row(String title, String hint, Check check, Runnable action) {
        boolean ok;
        try {
            ok = check.ok();
        } catch (Exception e) {
            ok = false;
        }
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(8), dp(12), dp(8));
        r.setBackground(states(0xFF1B1B1B));
        r.setClickable(true);
        r.setOnClickListener(v -> action.run());

        TextView mark = new TextView(this);
        mark.setText(ok ? "✓" : "●");
        mark.setTextColor(ok ? 0xFF66BB6A : ORANGE);
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, ok ? 22 : 16);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setGravity(Gravity.CENTER);
        r.addView(mark, new LinearLayout.LayoutParams(dp(26), -2));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ok ? 0xFF9A9A9A : Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(t);
        if (!ok) {
            TextView h = new TextView(this);
            h.setText(hint);
            h.setTextColor(0xFF9A9A9A);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            texts.addView(h);
        }
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(8);
        r.addView(texts, tl);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(5);
        list.addView(r, lp);
    }

    private StateListDrawable states(int color) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(0xFF333333));
        s.addState(new int[]{}, round(color));
        return s;
    }

    private GradientDrawable round(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        return g;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
