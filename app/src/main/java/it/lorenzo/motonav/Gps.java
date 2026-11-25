package it.lorenzo.motonav;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

/** Aiuti per il GPS dell'orologio: dati di assistenza quando c'è internet e GPS "caldo" in GUIDA. */
final class Gps {
    private Gps() {
    }

    /**
     * Scarica i dati di assistenza (posizione dei satelliti e ora esatta) finché l'orologio ha internet:
     * poi il primo punto arriva in pochi secondi invece che in minuti, anche offline per qualche giorno.
     */
    static String assist(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            boolean t = lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_time_injection", null);
            boolean x = lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_xtra_injection", null);
            boolean p = lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_psds_injection", null);
            c.getSharedPreferences("motonav", Context.MODE_PRIVATE).edit()
                    .putLong("gpsAssistAt", System.currentTimeMillis()).apply();
            String r = "assistenza GPS: ora " + t + " · satelliti " + x + "/" + p;
            Dbg.log("gps", r);
            return r;
        } catch (Exception e) {
            // segno comunque l'ora, così non riprovo a ogni giro
            c.getSharedPreferences("motonav", Context.MODE_PRIVATE).edit()
                    .putLong("gpsAssistAt", System.currentTimeMillis()).apply();
            Dbg.log("gps", "assistenza non riuscita: " + e);
            return "errore " + e;
        }
    }

    /** Se c'è internet e l'ultima assistenza ha più di 4 ore, la rifà. */
    static void assistIfStale(Context c, boolean online) {
        long at = c.getSharedPreferences("motonav", Context.MODE_PRIVATE).getLong("gpsAssistAt", 0);
        if (online && System.currentTimeMillis() - at > 4 * 3600 * 1000L) assist(c);
    }

    /** Cancella i dati del GPS: il prossimo avvio è "a freddo" (utile se si è bloccato su dati sbagliati). */
    static String reset(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            Bundle b = new Bundle();
            b.putBoolean("all", true);
            boolean ok = lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "delete_aiding_data", b);
            Dbg.log("gps", "dati GPS cancellati: " + ok);
            return "dati GPS cancellati: " + ok;
        } catch (Exception e) {
            return "errore " + e;
        }
    }

    // ---- GPS sempre acceso in GUIDA: il ricevitore resta agganciato anche a schermo spento ----
    private static final LocationListener keep = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
        }

        @Override
        public void onStatusChanged(String p, int s, Bundle e) {
        }

        @Override
        public void onProviderEnabled(String p) {
        }

        @Override
        public void onProviderDisabled(String p) {
        }
    };
    private static boolean keeping = false;

    static void keepWarm(Context c, boolean on) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (on && !keeping) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, keep, Looper.getMainLooper());
                keeping = true;
                Dbg.log("gps", "GPS tenuto acceso (GUIDA)");
            } else if (!on && keeping) {
                lm.removeUpdates(keep);
                keeping = false;
            }
        } catch (SecurityException e) {
            Dbg.log("gps", "manca il permesso posizione");
        } catch (Exception e) {
            Dbg.log("gps", "errore " + e);
        }
    }
}
