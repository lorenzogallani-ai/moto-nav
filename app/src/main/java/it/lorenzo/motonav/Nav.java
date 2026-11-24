package it.lorenzo.motonav;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

/** Passa la destinazione all'app di navigazione installata (OsmAnd, Organic Maps o Google Maps). */
class Nav {

    static final String OSMAND_PLUS = "net.osmand.plus";
    static final String OSMAND = "net.osmand";
    static final String ORGANIC = "app.organicmaps";
    static final String GMAPS = "com.google.android.apps.maps";

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Pacchetto da usare secondo la preferenza, oppure null se non c'è niente. */
    static String pick(Context c, String pref) {
        String[] order;
        switch (pref) {
            case "osmand": order = new String[]{OSMAND_PLUS, OSMAND}; break;
            case "organic": order = new String[]{ORGANIC}; break;
            case "google": order = new String[]{GMAPS}; break;
            default: order = new String[]{OSMAND_PLUS, OSMAND, ORGANIC, GMAPS};
        }
        for (String p : order) if (installed(c, p)) return p;
        return null;
    }

    static String installedLabel(Context c) {
        StringBuilder sb = new StringBuilder();
        if (installed(c, OSMAND_PLUS) || installed(c, OSMAND)) sb.append("OsmAnd ");
        if (installed(c, ORGANIC)) sb.append("OrganicMaps ");
        if (installed(c, GMAPS)) sb.append("GoogleMaps ");
        return sb.length() == 0 ? "nessuno" : sb.toString().trim();
    }

    static void go(Activity a, Store s, double lat, double lon, String name) {
        String pkg = pick(a, s.navApp());
        Intent i;
        if (pkg == null) {
            i = geoIntent(lat, lon, name);
        } else if (pkg.startsWith("net.osmand")) {
            Uri u = Uri.parse("osmand.api://navigate").buildUpon()
                    .appendQueryParameter("dest_lat", String.valueOf(lat))
                    .appendQueryParameter("dest_lon", String.valueOf(lon))
                    .appendQueryParameter("dest_name", name)
                    .appendQueryParameter("profile", s.navProfile())
                    .appendQueryParameter("force", "true")
                    .build();
            i = new Intent(Intent.ACTION_VIEW, u);
            i.setPackage(pkg);
        } else if (pkg.equals(GMAPS)) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + lat + "," + lon + "&mode=" + (s.isScooter() ? "b" : "d")));
            i.setPackage(pkg);
        } else {
            i = geoIntent(lat, lon, name);
            i.setPackage(pkg);
        }
        start(a, i, geoIntent(lat, lon, name));
    }

    /** Ricerca libera quando non abbiamo le coordinate. */
    static void search(Activity a, Store s, String query) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)));
        String pkg = pick(a, s.navApp());
        Intent fallback = new Intent(i);
        if (pkg != null) i.setPackage(pkg);
        start(a, i, fallback);
    }

    /**
     * Navigazione senza coordinate e senza internet: OsmAnd cerca l'indirizzo nelle mappe offline
     * vicino alla tua posizione. showResults=false parte subito col primo risultato.
     */
    static void searchNavigate(Activity a, Store s, String query, boolean showResults) {
        String pkg = pick(a, s.navApp());
        android.location.Location here = lastLocation(a);
        if (pkg == null || !pkg.startsWith("net.osmand") || here == null) {
            search(a, s, query);
            return;
        }
        Uri u = Uri.parse("osmand.api://navigate_search").buildUpon()
                .appendQueryParameter("dest_search_query", query)
                .appendQueryParameter("search_lat", String.valueOf(here.getLatitude()))
                .appendQueryParameter("search_lon", String.valueOf(here.getLongitude()))
                .appendQueryParameter("show_search_results", String.valueOf(showResults))
                .appendQueryParameter("profile", s.navProfile())
                .appendQueryParameter("force", "true")
                .build();
        Intent i = new Intent(Intent.ACTION_VIEW, u);
        i.setPackage(pkg);
        Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)));
        fallback.setPackage(pkg);
        start(a, i, fallback);
    }

    /** Ultima posizione nota (GPS o rete), senza accendere nulla. */
    @SuppressWarnings("MissingPermission")
    static android.location.Location lastLocation(Context c) {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            android.location.Location best = null;
            for (String p : lm.getProviders(true)) {
                android.location.Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private static Intent geoIntent(double lat, double lon, String name) {
        return new Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon + "(" + Uri.encode(name) + ")"));
    }

    private static void start(Activity a, Intent i, Intent fallback) {
        try {
            // OsmAnd vuole le richieste "osmand.api" con startActivityForResult
            a.startActivityForResult(i, 77);
        } catch (Exception e) {
            try {
                a.startActivity(fallback);
            } catch (Exception e2) {
                Toast.makeText(a, "Nessuna app di navigazione installata: apri ⚙ → Scarica OsmAnd",
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
