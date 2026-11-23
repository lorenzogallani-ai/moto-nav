package it.lorenzo.motonav;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Trasforma un indirizzo in coordinate con OpenStreetMap (Nominatim). Da chiamare fuori dal thread principale. */
class Geo {

    static class Result {
        double lat, lon;
        String label;
    }

    static Result find(String query) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&accept-language=it&q="
                    + URLEncoder.encode(query, "UTF-8"));
            c = (HttpURLConnection) url.openConnection();
            c.setRequestProperty("User-Agent", "MotoNav/1.0 (navigatore personale)");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONArray a = new JSONArray(sb.toString());
            if (a.length() == 0) return null;
            JSONObject o = a.getJSONObject(0);
            Result res = new Result();
            res.lat = Double.parseDouble(o.getString("lat"));
            res.lon = Double.parseDouble(o.getString("lon"));
            res.label = o.optString("display_name", query);
            return res;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
