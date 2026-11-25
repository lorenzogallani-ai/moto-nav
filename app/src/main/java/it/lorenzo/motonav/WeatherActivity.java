package it.lorenzo.motonav;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Meteo per la moto d'acqua: vento in nodi con direzione e raffiche, temperatura, pioggia, tramonto
 * e le prossime ore. Con internet (Wi-Fi o hotspot dell'iPhone) aggiorna da Open-Meteo, gratuito e
 * senza registrazione; senza internet mostra l'ultima previsione salvata.
 */
public class WeatherActivity extends Activity {

    static final int BLUE = 0xFF448AFF;
    static final int GUST_ALERT = 18; // nodi

    private Store store;
    private TextView upd, kn, dir, chips, alert;
    private LinearLayout hours;
    private ArrowView arrow;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF04101F);
        root.setPadding(dp(8), dp(4), dp(8), dp(6));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Meteo · " + store.portName(), 19, Color.WHITE, true);
        head.addView(title, new LinearLayout.LayoutParams(-2, -2));
        upd = text("", 12, 0xFF8AA0C0, false);
        upd.setGravity(Gravity.END);
        head.addView(upd, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(head);

        LinearLayout wind = new LinearLayout(this);
        wind.setGravity(Gravity.CENTER_VERTICAL);
        arrow = new ArrowView(this);
        wind.addView(arrow, new LinearLayout.LayoutParams(dp(76), dp(76)));
        LinearLayout wv = new LinearLayout(this);
        wv.setOrientation(LinearLayout.VERTICAL);
        wv.setPadding(dp(14), 0, 0, 0);
        kn = text("--", 50, Color.WHITE, true);
        wv.addView(kn);
        dir = text("", 15, 0xFFC9D6EA, false);
        wv.addView(dir);
        wind.addView(wv, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(wind, new LinearLayout.LayoutParams(-1, -2));

        chips = text("", 15, 0xFFC9D6EA, false);
        chips.setPadding(0, dp(2), 0, 0);
        root.addView(chips);

        alert = text("", 14, 0xFFFFD27A, true);
        alert.setPadding(dp(10), dp(5), dp(10), dp(5));
        alert.setBackground(round(0xFF5A3B00, 10));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(6);
        root.addView(alert, alp);

        hours = new LinearLayout(this);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, 0, 1f);
        hp.topMargin = dp(6);
        root.addView(hours, hp);

        LinearLayout btns = new LinearLayout(this);
        TextView ref = button("AGGIORNA");
        ref.setOnClickListener(v -> refresh(true));
        TextView home = button("◀ HOME");
        home.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(46), 1f);
        bl.setMargins(0, dp(6), dp(3), 0);
        btns.addView(ref, bl);
        LinearLayout.LayoutParams bl2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        bl2.setMargins(dp(3), dp(6), 0, 0);
        btns.addView(home, bl2);
        root.addView(btns);
        setContentView(root);

        show();
        if (System.currentTimeMillis() - store.weatherAt() > 30 * 60 * 1000L) refresh(false);
    }

    // ================= dati =================

    private void refresh(boolean asked) {
        if (!online(this)) {
            if (asked) upd.setText("niente internet: accendi l'hotspot dell'iPhone");
            return;
        }
        upd.setText("aggiorno…");
        new Thread(() -> {
            String r = fetch(this, store);
            h.post(() -> {
                if (r == null) upd.setText("aggiornamento non riuscito · " + age());
                else show();
            });
        }).start();
    }

    /** Scarica la previsione e la salva. Restituisce null se non riesce. Usata anche dalla Home. */
    static String fetch(Context c, Store store) {
        try {
            double lat = store.portLat(), lon = store.portLon();
            Location l = Nav.lastLocation(c);
            if (l != null && System.currentTimeMillis() - l.getTime() < 3 * 3600 * 1000L) {
                lat = l.getLatitude();
                lon = l.getLongitude();
            }
            String u = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m,weather_code"
                    + "&hourly=temperature_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation_probability,weather_code"
                    + "&daily=sunset&wind_speed_unit=kn&timezone=Europe%%2FRome&forecast_days=2", lat, lon);
            HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
            con.setConnectTimeout(12000);
            con.setReadTimeout(15000);
            if (con.getResponseCode() != 200) return null;
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            String json = bo.toString("UTF-8");
            new JSONObject(json); // controllo che sia valido
            store.setWeather(json);
            Dbg.log("meteo", "previsione aggiornata");
            return json;
        } catch (Exception e) {
            Dbg.log("meteo", "errore " + e);
            return null;
        }
    }

    /** Riga breve per la tessera della Home, es. "Breva 9 kn · 14°". */
    static String summary(Store store) {
        try {
            String js = store.weatherJson();
            if (js == null) return "Tocca per aggiornare";
            JSONObject hr = new JSONObject(js).getJSONObject("hourly");
            int i = nowIndex(hr.getJSONArray("time"));
            int w = (int) Math.round(hr.getJSONArray("wind_speed_10m").getDouble(i));
            int d = (int) Math.round(hr.getJSONArray("wind_direction_10m").getDouble(i));
            int t = (int) Math.round(hr.getJSONArray("temperature_2m").getDouble(i));
            String name = lakeWind(store, d);
            return (name != null ? name : "da " + compass(d)) + " " + w + " kn · " + t + "°";
        } catch (Exception e) {
            return "Tocca per aggiornare";
        }
    }

    private void show() {
        String js = store.weatherJson();
        if (js == null) {
            upd.setText(online(this) ? "" : "nessuna previsione salvata");
            dir.setText("Collega l'orologio a internet (Wi-Fi o hotspot) e tocca AGGIORNA");
            alert.setVisibility(View.GONE);
            return;
        }
        try {
            JSONObject o = new JSONObject(js);
            JSONObject hr = o.getJSONObject("hourly");
            JSONArray time = hr.getJSONArray("time");
            JSONArray ws = hr.getJSONArray("wind_speed_10m"), wd = hr.getJSONArray("wind_direction_10m"),
                    wg = hr.getJSONArray("wind_gusts_10m"), tp = hr.getJSONArray("temperature_2m"),
                    pp = hr.getJSONArray("precipitation_probability"), wc = hr.getJSONArray("weather_code");
            int i = nowIndex(time);
            // se è fresca uso il dato "adesso", altrimenti l'ora corrente della previsione salvata
            double speed = ws.getDouble(i), gust = wg.getDouble(i), deg = wd.getDouble(i), temp = tp.getDouble(i);
            if (System.currentTimeMillis() - store.weatherAt() < 3600 * 1000L && o.has("current")) {
                JSONObject cu = o.getJSONObject("current");
                speed = cu.optDouble("wind_speed_10m", speed);
                gust = cu.optDouble("wind_gusts_10m", gust);
                deg = cu.optDouble("wind_direction_10m", deg);
                temp = cu.optDouble("temperature_2m", temp);
            }
            upd.setText(age());
            kn.setText(Math.round(speed) + " kn");
            String name = lakeWind(store, (int) deg);
            dir.setText("da " + compass((int) deg) + (name != null ? " · " + name : "") + " · raffiche " + Math.round(gust) + " kn");
            arrow.setDeg((float) deg);

            String sunset = "";
            try {
                String ss = o.getJSONObject("daily").getJSONArray("sunset").getString(0);
                sunset = "   tramonto " + ss.substring(11, 16);
            } catch (Exception ignored) {
            }
            chips.setText(Math.round(temp) + "° aria   pioggia " + pp.optInt(i, 0) + "%" + sunset);

            // avviso: raffiche forti o temporale nelle prossime 6 ore
            String warn = null;
            for (int k = i; k < Math.min(time.length(), i + 7); k++) {
                int code = wc.optInt(k, 0);
                String hh = time.getString(k).substring(11, 13);
                if (code >= 95) {
                    warn = "⚠ Temporale previsto verso le " + hh + ": stai vicino al porto";
                    break;
                }
                if (warn == null && wg.getDouble(k) >= GUST_ALERT)
                    warn = "⚠ Dalle " + hh + " raffiche fino a " + Math.round(wg.getDouble(k)) + " kn: rientra prima";
            }
            alert.setVisibility(warn != null ? View.VISIBLE : View.GONE);
            if (warn != null) alert.setText(warn);

            hours.removeAllViews();
            for (int k = i + 1; k < Math.min(time.length(), i + 7); k++) {
                int g = (int) Math.round(wg.getDouble(k));
                TextView c = text(time.getString(k).substring(11, 13) + ":00\n" + Math.round(ws.getDouble(k)) + " " + arrowChar(wd.getDouble(k))
                        + "\nraff. " + g, 13, g >= GUST_ALERT ? 0xFFFFB74D : 0xFFD8E2F0, false);
                c.setGravity(Gravity.CENTER);
                c.setBackground(round(0xFF0C2340, 10));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                lp.setMargins(dp(2), 0, dp(2), 0);
                hours.addView(c, lp);
            }
        } catch (Exception e) {
            dir.setText("Previsione illeggibile: tocca AGGIORNA");
        }
    }

    private String age() {
        long at = store.weatherAt();
        if (at == 0) return "";
        String t = new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date(at));
        long min = (System.currentTimeMillis() - at) / 60000;
        if (min < 60) return "aggiornato " + t;
        return "previsione delle " + t + (min > 24 * 60 ? " (vecchia)" : "");
    }

    static int nowIndex(JSONArray time) throws Exception {
        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(new Date());
        for (int i = 0; i < time.length(); i++) if (time.getString(i).startsWith(now)) return i;
        return 0;
    }

    static String compass(int deg) {
        String[] n = {"N", "NE", "E", "SE", "S", "SO", "O", "NO"};
        return n[((int) Math.round(((deg % 360) + 360) % 360 / 45.0)) % 8];
    }

    /** Venti tipici del Lago di Como, solo se sei sul Lario. */
    static String lakeWind(Store s, int deg) {
        double lat = s.portLat(), lon = s.portLon();
        if (lat < 45.75 || lat > 46.25 || lon < 9.0 || lon > 9.5) return null;
        int d = ((deg % 360) + 360) % 360;
        if (d >= 135 && d <= 225) return "Breva";
        if (d >= 315 || d <= 45) return "Tivano";
        return null;
    }

    /** Freccia nella direzione verso cui soffia il vento. */
    private static String arrowChar(double from) {
        String[] a = {"↓", "↙", "←", "↖", "↑", "↗", "→", "↘"};
        return a[((int) Math.round((((from % 360) + 360) % 360) / 45.0)) % 8];
    }

    static boolean online(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        return ni != null && ni.isConnected();
    }

    // ================= grafica =================

    /** Bussola con la freccia che indica dove va il vento. */
    static class ArrowView extends View {
        private float deg = 0;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                txt = new Paint(Paint.ANTI_ALIAS_FLAG);

        ArrowView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(0xFF24406A);
            fill.setColor(BLUE);
            txt.setColor(0xFF7C93B5);
            txt.setTextAlign(Paint.Align.CENTER);
        }

        void setDeg(float d) {
            deg = d;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), cx = w / 2, cy = getHeight() / 2, r = Math.min(w, getHeight()) / 2 - 3;
            ring.setStrokeWidth(r * 0.08f);
            c.drawCircle(cx, cy, r, ring);
            txt.setTextSize(r * 0.28f);
            c.drawText("N", cx, cy - r + r * 0.34f, txt);
            c.save();
            c.rotate(deg + 180, cx, cy); // il vento "da" deg va verso deg+180
            Path p = new Path();
            p.moveTo(cx, cy - r * 0.78f);
            p.lineTo(cx + r * 0.3f, cy + r * 0.1f);
            p.lineTo(cx + r * 0.08f, cy);
            p.lineTo(cx + r * 0.08f, cy + r * 0.65f);
            p.lineTo(cx - r * 0.08f, cy + r * 0.65f);
            p.lineTo(cx - r * 0.08f, cy);
            p.lineTo(cx - r * 0.3f, cy + r * 0.1f);
            p.close();
            c.drawPath(p, fill);
            c.restore();
        }
    }

    private TextView text(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private TextView button(String t) {
        TextView v = text(t, 17, Color.WHITE, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(round(0xFF1B2A40, 12));
        v.setClickable(true);
        return v;
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
