package it.lorenzo.motonav;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Tutto ciò che l'app ricorda: destinazioni, preferenze, stato della modalità moto. */
class Store {

    static class Dest {
        String name;
        String query;          // indirizzo o nome del luogo
        double lat = Double.NaN;
        double lon = Double.NaN;

        Dest(String name, String query) {
            this.name = name;
            this.query = query;
        }

        boolean hasCoords() {
            return !Double.isNaN(lat) && !Double.isNaN(lon);
        }

        boolean isEmpty() {
            return query == null || query.trim().isEmpty();
        }
    }

    static final int MAX_DEST = 8;

    private final SharedPreferences p;

    Store(Context c) {
        p = c.getSharedPreferences("motonav", Context.MODE_PRIVATE);
    }

    // ---------- destinazioni ----------

    List<Dest> loadDests() {
        List<Dest> out = new ArrayList<>();
        String raw = p.getString("dests", null);
        if (raw == null) {
            out.add(new Dest("Casa", ""));
            out.add(new Dest("Domaso", "Domaso, Como"));
            out.add(new Dest("Ufficio", ""));
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Dest d = new Dest(o.optString("name", "?"), o.optString("query", ""));
                d.lat = o.optDouble("lat", Double.NaN);
                d.lon = o.optDouble("lon", Double.NaN);
                out.add(d);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    void saveDests(List<Dest> list) {
        JSONArray a = new JSONArray();
        try {
            for (Dest d : list) {
                JSONObject o = new JSONObject();
                o.put("name", d.name);
                o.put("query", d.query);
                if (d.hasCoords()) {
                    o.put("lat", d.lat);
                    o.put("lon", d.lon);
                }
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        p.edit().putString("dests", a.toString()).apply();
    }

    // ---------- preferenze ----------

    /** "auto", "osmand", "organic", "google" */
    String navApp() { return p.getString("navApp", "auto"); }
    void setNavApp(String v) { p.edit().putString("navApp", v).apply(); }

    /** profilo OsmAnd: "motorcycle" oppure "car" */
    String profile() { return p.getString("profile", "motorcycle"); }
    void setProfile(String v) { p.edit().putString("profile", v).apply(); }

    // ---------- veicolo: "moto" oppure "monopattino" ----------

    String vehicle() { return p.getString("vehicle", "moto"); }
    void setVehicle(String v) { p.edit().putString("vehicle", v).apply(); }
    boolean isScooter() { return "monopattino".equals(vehicle()); }

    /** Profilo OsmAnd per la navigazione: in monopattino si usa quello da bici (ciclabili). */
    String navProfile() { return isScooter() ? "bicycle" : profile(); }

    // ---------- monopattino Segway ----------

    String scooterMac() { return p.getString("scooterMac", null); }
    String scooterName() { return p.getString("scooterName", null); }
    void setScooter(String mac, String name) {
        p.edit().putString("scooterMac", mac).putString("scooterName", name).apply();
    }
    void forgetScooter() { p.edit().remove("scooterMac").remove("scooterName").apply(); }

    /** Chiave fissa a 16 byte con cui l'orologio si abbina al monopattino (creata una volta sola). */
    byte[] scooterKey() {
        String hex = p.getString("scooterKey", null);
        if (hex == null || hex.length() != 32) {
            byte[] k = new byte[16];
            new java.security.SecureRandom().nextBytes(k);
            StringBuilder sb = new StringBuilder();
            for (byte b : k) sb.append(String.format("%02x", b));
            hex = sb.toString();
            p.edit().putString("scooterKey", hex).apply();
        }
        byte[] k = new byte[16];
        for (int i = 0; i < 16; i++) k[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return k;
    }

    // ---------- modalità guida (luminosità, schermo, notifiche) ----------

    boolean motoOn() { return p.getBoolean("motoOn", false); }
    void setMotoOn(boolean v) { p.edit().putBoolean("motoOn", v).apply(); }

    void saveScreen(int brightness, int mode, int timeout) {
        p.edit().putInt("bright", brightness).putInt("brightMode", mode)
                .putInt("timeout", timeout).apply();
    }
    int savedBrightness() { return p.getInt("bright", 150); }
    int savedBrightnessMode() { return p.getInt("brightMode", 0); }
    int savedTimeout() { return p.getInt("timeout", 30000); }

    void saveFilter(int f) { p.edit().putInt("filter", f).apply(); }
    int savedFilter() { return p.getInt("filter", 1); }

    boolean askedDnd() { return p.getBoolean("askedDnd", false); }
    void setAskedDnd() { p.edit().putBoolean("askedDnd", true).apply(); }
}
