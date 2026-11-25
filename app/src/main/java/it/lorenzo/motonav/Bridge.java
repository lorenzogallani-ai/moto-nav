package it.lorenzo.motonav;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Banco prove: collega l'orologio a un "foglio" su GitHub (segnalazione "Banco prove orologio").
 * L'orologio tiene aggiornato un commento "Diario" con lo stato e le ultime righe del diario,
 * ed esegue i comandi scritti nei commenti che iniziano con /w. Serve per provare e sistemare
 * monopattino, telecomando e GPS senza fotografare lo schermo.
 */
public class Bridge extends Service {

    // mini-app GitHub "moto-nav-banco-prove" (identificativo pubblico, non è una password)
    static final String CLIENT_ID = "CLIENT_ID_DA_INSERIRE";
    static final String REPO = "lorenzogallani-ai/moto-nav";
    static final String OWNER = "lorenzogallani-ai";
    static final String ISSUE_TITLE = "Banco prove orologio";
    static final String APK_URL = "https://github.com/" + REPO + "/releases/latest/download/moto-nav.apk";

    static final String ACTION_STOP = "it.lorenzo.motonav.BRIDGE_STOP";

    static volatile String state = "Spento";
    static volatile String userCode = null, verifyUri = null;
    static volatile boolean linked = false;
    static volatile Bridge instance;
    static volatile String lastCmd = "-";

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private volatile boolean running = false;
    private Thread worker;
    private String etag = null;
    private long lastDiarySeq = -1, lastDiaryAt = 0;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("motonav", MODE_PRIVATE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.edit().putBoolean("bridgeOn", false).apply();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        prefs.edit().putBoolean("bridgeOn", true).apply();
        startForeground(11, notification());
        if (!running) {
            running = true;
            worker = new Thread(this::loop, "banco-prove");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        stopGps();
        instance = null;
        linked = false;
        userCode = null;
        state = "Spento";
        super.onDestroy();
    }

    static boolean on(Context c) {
        return c.getSharedPreferences("motonav", MODE_PRIVATE).getBoolean("bridgeOn", false);
    }

    // ===================== ciclo principale =====================

    private void loop() {
        Dbg.log("ponte", "avvio banco prove v" + version());
        while (running) {
            try {
                if (!online()) {
                    set("Aspetto internet (Wi-Fi o hotspot dell'iPhone)");
                    linked = false;
                    sleep(8000);
                    continue;
                }
                if (token() == null) {
                    deviceFlow();
                    continue;
                }
                int issue = issue();
                if (issue <= 0) {
                    sleep(15000);
                    continue;
                }
                linked = true;
                userCode = null;
                pollCommands(issue);
                long now = System.currentTimeMillis();
                if ((Dbg.seq() != lastDiarySeq && now - lastDiaryAt > 4000) || now - lastDiaryAt > 30000) {
                    writeDiary(issue);
                }
                set("Collegato a Claude · ultimo comando: " + lastCmd);
                sleep(3000);
            } catch (Unauthorized u) {
                Dbg.log("ponte", "permesso scaduto o revocato: rifaccio il collegamento");
                prefs.edit().remove("bridgeToken").apply();
                linked = false;
            } catch (InterruptedException ie) {
                return;
            } catch (Exception e) {
                linked = false;
                set("Errore: " + e.getMessage() + " (riprovo)");
                Dbg.log("ponte", "errore: " + e);
                try {
                    sleep(10000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private void set(String s) {
        if (s.equals(state)) return;
        state = s;
        h.post(() -> {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && running) nm.notify(11, notification());
        });
    }

    private String token() {
        return prefs.getString("bridgeToken", null);
    }

    // ===================== accesso a GitHub (codice da inserire sul telefono) =====================

    private void deviceFlow() throws Exception {
        if (CLIENT_ID.contains("DA_INSERIRE")) {
            set("Manca l'identificativo della mini-app GitHub");
            sleep(30000);
            return;
        }
        Resp r = http("POST", "https://github.com/login/device/code", "client_id=" + CLIENT_ID, false, null);
        JSONObject j = new JSONObject(r.body);
        String device = j.getString("device_code");
        userCode = j.getString("user_code");
        verifyUri = j.optString("verification_uri", "https://github.com/login/device");
        int interval = Math.max(5, j.optInt("interval", 5));
        long until = System.currentTimeMillis() + j.optInt("expires_in", 900) * 1000L;
        set("Sul telefono apri github.com/login/device e scrivi " + userCode);
        Dbg.log("ponte", "codice per GitHub: " + userCode);
        while (running && System.currentTimeMillis() < until) {
            sleep(interval * 1000L);
            Resp t = http("POST", "https://github.com/login/oauth/access_token",
                    "client_id=" + CLIENT_ID + "&device_code=" + device
                            + "&grant_type=urn:ietf:params:oauth:grant-type:device_code", false, null);
            JSONObject k = new JSONObject(t.body);
            if (k.has("access_token")) {
                prefs.edit().putString("bridgeToken", k.getString("access_token")).apply();
                userCode = null;
                set("Collegato a GitHub");
                Dbg.log("ponte", "collegato a GitHub");
                return;
            }
            String err = k.optString("error");
            if ("slow_down".equals(err)) interval += 5;
            else if (!"authorization_pending".equals(err)) {
                Dbg.log("ponte", "accesso GitHub: " + err);
                break;
            }
        }
        userCode = null;
    }

    // ===================== canale: segnalazione + commenti =====================

    private int issue() throws Exception {
        int n = prefs.getInt("bridgeIssue", 0);
        if (n > 0) return n;
        Resp r = api("GET", "/repos/" + REPO + "/issues?state=open&per_page=50", null);
        JSONArray a = new JSONArray(r.body);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            if (ISSUE_TITLE.equals(o.optString("title"))) {
                n = o.getInt("number");
                break;
            }
        }
        if (n <= 0) {
            JSONObject b = new JSONObject().put("title", ISSUE_TITLE).put("body", "Canale di prova tra Claude e l'orologio.");
            n = new JSONObject(api("POST", "/repos/" + REPO + "/issues", b.toString()).body).getInt("number");
        }
        prefs.edit().putInt("bridgeIssue", n).apply();
        // la prima volta salto i comandi vecchi
        if (prefs.getLong("bridgeLastCmd", -1) < 0) {
            long max = 0;
            JSONArray c = new JSONArray(api("GET", "/repos/" + REPO + "/issues/" + n + "/comments?per_page=100", null).body);
            for (int i = 0; i < c.length(); i++) max = Math.max(max, c.getJSONObject(i).getLong("id"));
            prefs.edit().putLong("bridgeLastCmd", max).apply();
        }
        return n;
    }

    private void pollCommands(int issue) throws Exception {
        long last = prefs.getLong("bridgeLastCmd", 0);
        String since = iso(System.currentTimeMillis() - 6L * 3600 * 1000);
        Resp r = api("GET", "/repos/" + REPO + "/issues/" + issue + "/comments?per_page=100&since=" + since, null, etag);
        if (r.code == 304) return;
        etag = r.etag;
        JSONArray a = new JSONArray(r.body);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            long id = o.getLong("id");
            if (id <= last) continue;
            String body = o.optString("body", "");
            String who = o.getJSONObject("user").optString("login");
            if (!body.trim().startsWith("/w") || !OWNER.equalsIgnoreCase(who)) continue;
            last = id;
            prefs.edit().putLong("bridgeLastCmd", last).apply();
            StringBuilder out = new StringBuilder();
            for (String line : body.split("\n")) {
                line = line.trim();
                if (!line.startsWith("/w")) continue;
                String cmd = line.substring(2).trim();
                lastCmd = cmd.length() > 40 ? cmd.substring(0, 40) + "…" : cmd;
                Dbg.log("ponte", "comando: " + cmd);
                String res;
                try {
                    res = run(cmd);
                } catch (Exception e) {
                    res = "ERRORE " + e;
                }
                out.append("> ").append(cmd).append('\n').append(res).append("\n\n");
            }
            // lascio al monopattino/iPhone un attimo per rispondere, poi allego il diario recente
            Thread.sleep(1500);
            String reply = "↩️ risposta a " + id + " · v" + version() + "\n```\n" + out.toString().trim()
                    + "\n--- diario recente ---\n" + Dbg.tail(40) + "```";
            api("POST", "/repos/" + REPO + "/issues/" + issue + "/comments",
                    new JSONObject().put("body", clip(reply)).toString());
            etag = null;
        }
    }

    private void writeDiary(int issue) throws Exception {
        lastDiarySeq = Dbg.seq();
        lastDiaryAt = System.currentTimeMillis();
        String body = "📟 **Diario orologio** · " + new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY).format(new Date())
                + " · v" + version() + "\n```\n" + snapshot() + "\n--- diario ---\n" + Dbg.tail(160) + "```";
        body = clip(body);
        long cid = prefs.getLong("bridgeDiary", 0);
        String json = new JSONObject().put("body", body).toString();
        if (cid > 0) {
            Resp r = api("PATCH", "/repos/" + REPO + "/issues/comments/" + cid, json, null, true);
            if (r.code < 300) return;
        }
        Resp r = api("POST", "/repos/" + REPO + "/issues/" + issue + "/comments", json);
        prefs.edit().putLong("bridgeDiary", new JSONObject(r.body).getLong("id")).apply();
        etag = null;
    }

    private static String clip(String s) {
        return s.length() > 60000 ? s.substring(s.length() - 60000) : s;
    }

    // ===================== comandi =====================

    private String run(String cmd) throws Exception {
        String[] w = cmd.split("\\s+");
        String a = w.length > 0 ? w[0].toLowerCase(Locale.ROOT) : "";
        String b = w.length > 1 ? w[1].toLowerCase(Locale.ROOT) : "";
        switch (a) {
            case "ping":
                return "pong v" + version();
            case "stato":
                return snapshot();
            case "log":
                return Dbg.tail(w.length > 1 ? Integer.parseInt(w[1]) : 100);
            case "sc":
                return scooter(w, b);
            case "hid":
                return hid(w, b);
            case "gps":
                if ("on".equals(b)) {
                    h.post(this::startGps);
                    return "GPS acceso: una riga nel diario ogni 5 s";
                }
                if ("off".equals(b)) {
                    h.post(this::stopGps);
                    return "GPS spento";
                }
                return gpsLine();
            case "nav":
                return nav(cmd.substring(3).trim());
            case "open":
                return open(b);
            case "sh":
                return shell(cmd.substring(2).trim());
            case "prefs": {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, ?> e : prefs.getAll().entrySet())
                    if (!e.getKey().equals("bridgeToken")) sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
                return sb.toString();
            }
            case "set":
                return setPref(w);
            case "bt":
                return bt();
            case "update":
                return update();
            case "help":
                return "ping · stato · log N · sc start|stop|int|sniff on/off/all|poll on/off|raw DST CMD ARG HEX|read DST REG LEN|write DST REG HEX|mode N|light|pick MAC"
                        + " · hid start|stop|diag|key BITS|raw HEX|kb HEX|forget|adv · gps on|off · nav testo|lat,lon · open home|scooter|remote · sh comando · prefs · set chiave valore · bt · update";
            default:
                return "comando sconosciuto (help)";
        }
    }

    private String scooter(String[] w, String b) {
        ScooterService s = ScooterService.instance;
        switch (b) {
            case "start":
                startService(new Intent(this, ScooterService.class));
                return "avvio il collegamento";
            case "stop":
                startService(new Intent(this, ScooterService.class).setAction(ScooterService.ACTION_STOP));
                return "scollego";
            case "pick":
                startService(new Intent(this, ScooterService.class).setAction(ScooterService.ACTION_PICK)
                        .putExtra("addr", w[2].toUpperCase(Locale.ROOT)).putExtra("name", w.length > 3 ? w[3] : "?"));
                return "collego " + w[2];
            case "int":
                return ScooterService.state() + "\n" + (s != null ? s.internals() : "servizio spento")
                        + "\nvisti: " + ScooterService.seen;
            case "sniff":
                ScooterService.sniff = !"off".equals(w.length > 2 ? w[2] : "on");
                ScooterService.sniffAll = "all".equals(w.length > 2 ? w[2] : "");
                return "ascolto pacchetti: " + ScooterService.sniff + (ScooterService.sniffAll ? " (anche 0xB0)" : "");
            case "poll":
                ScooterService.pollOn = !"off".equals(w.length > 2 ? w[2] : "on");
                return "lettura periodica: " + ScooterService.pollOn;
            case "mode":
                if (s == null) return "servizio spento";
                startService(new Intent(this, ScooterService.class).setAction(ScooterService.ACTION_MODE)
                        .putExtra(ScooterService.EXTRA_MODE, Integer.parseInt(w[2])));
                return "modalità " + w[2] + " inviata";
            case "light":
                startService(new Intent(this, ScooterService.class).setAction(ScooterService.ACTION_LIGHT));
                return "fari: comando inviato";
            case "raw":
            case "read":
            case "write": {
                if (s == null) return "servizio spento";
                int dst = Integer.parseInt(w[2], 16);
                if ("raw".equals(b)) {
                    s.raw(dst, Integer.parseInt(w[3], 16), Integer.parseInt(w[4], 16), w.length > 5 ? Dbg.unhex(w[5]) : new byte[0]);
                } else if ("read".equals(b)) {
                    s.raw(dst, 0x01, Integer.parseInt(w[3], 16), new byte[]{(byte) Integer.parseInt(w[4], 16)});
                } else {
                    s.raw(dst, 0x03, Integer.parseInt(w[3], 16), Dbg.unhex(w[4]));
                }
                return "inviato (risposte nel diario qui sotto)";
            }
            default:
                return ScooterService.state();
        }
    }

    private String hid(String[] w, String b) {
        RemoteService s = RemoteService.instance;
        switch (b) {
            case "start":
                startService(new Intent(this, RemoteService.class));
                return "telecomando acceso";
            case "stop":
                stopService(new Intent(this, RemoteService.class));
                return "telecomando spento";
            case "key":
                RemoteService.key(this, Integer.parseInt(w[2], 16));
                return "tasto " + w[2] + " premuto";
            case "raw":
                return s == null ? "servizio spento" : "media " + s.rawReport(false, Dbg.unhex(w[2]));
            case "kb":
                return s == null ? "servizio spento" : "tastiera " + s.rawReport(true, Dbg.unhex(w[2]));
            case "forget":
                return "abbinamenti cancellati: " + RemoteService.forgetIphones(this);
            case "adv":
                if (s != null) s.restartAdv();
                return "visibilità riavviata";
            default:
                return RemoteService.status + "\n" + RemoteService.diag() + "\n" + RemoteService.eventLog();
        }
    }

    private String nav(String q) {
        if (q.isEmpty()) return "manca la destinazione";
        Store st = new Store(this);
        Intent i;
        if (q.matches("-?\\d+(\\.\\d+)?\\s*,\\s*-?\\d+(\\.\\d+)?")) {
            String[] p = q.split(",");
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("osmand.api://navigate?dest_lat=" + p[0].trim() + "&dest_lon=" + p[1].trim()
                    + "&dest_name=Destinazione&profile=" + st.navProfile() + "&force=true"));
        } else {
            Location l = Nav.lastLocation(this);
            String enc;
            try {
                enc = URLEncoder.encode(q, "UTF-8");
            } catch (Exception e) {
                enc = q;
            }
            String u = "osmand.api://navigate_search?dest_search_query=" + enc + "&show_search_results=false&profile="
                    + st.navProfile() + "&force=true";
            if (l != null) u += "&search_lat=" + l.getLatitude() + "&search_lon=" + l.getLongitude();
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return "navigazione avviata verso " + q;
        } catch (Exception e) {
            return "OsmAnd non risponde: " + e.getMessage();
        }
    }

    private String open(String what) {
        Class<?> c = "scooter".equals(what) ? ScooterActivity.class : "remote".equals(what) ? RemoteActivity.class
                : "setup".equals(what) ? SetupActivity.class : "bridge".equals(what) ? BridgeActivity.class : MainActivity.class;
        startActivity(new Intent(this, c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return "aperto " + c.getSimpleName();
    }

    private String shell(String c) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", c});
        StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null && sb.length() < 20000) sb.append(l).append('\n');
            } catch (Exception ignored) {
            }
        });
        reader.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
            String l;
            while ((l = r.readLine()) != null && sb.length() < 20000) sb.append("! ").append(l).append('\n');
        }
        reader.join(15000);
        p.destroy();
        return sb.toString();
    }

    private String setPref(String[] w) {
        if (w.length < 3) return "uso: set chiave valore";
        Object cur = prefs.getAll().get(w[1]);
        SharedPreferences.Editor e = prefs.edit();
        String v = w[2];
        if (cur instanceof Boolean) e.putBoolean(w[1], Boolean.parseBoolean(v));
        else if (cur instanceof Integer) e.putInt(w[1], Integer.parseInt(v));
        else if (cur instanceof Long) e.putLong(w[1], Long.parseLong(v));
        else if (cur instanceof Float) e.putFloat(w[1], Float.parseFloat(v));
        else if ("true".equals(v) || "false".equals(v)) e.putBoolean(w[1], Boolean.parseBoolean(v));
        else e.putString(w[1], v);
        e.apply();
        return w[1] + " = " + v;
    }

    private String bt() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) return "niente Bluetooth";
        StringBuilder sb = new StringBuilder("acceso=" + ad.isEnabled() + " nome=" + ad.getName()
                + " LE-periferica=" + ad.isMultipleAdvertisementSupported() + "\nabbinati:\n");
        for (BluetoothDevice d : ad.getBondedDevices())
            sb.append("  ").append(d.getName()).append(" ").append(d.getAddress()).append(" tipo=").append(d.getType()).append('\n');
        return sb.toString();
    }

    // ===================== aggiornamento dell'app =====================

    private long downloadId = -1;

    private String update() {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(APK_URL))
                .setTitle("MotoNav aggiornamento")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "moto-nav-" + System.currentTimeMillis() + ".apk");
        downloadId = dm.enqueue(r);
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2) != downloadId) return;
                try {
                    unregisterReceiver(this);
                } catch (Exception ignored) {
                }
                Uri u = dm.getUriForDownloadedFile(downloadId);
                Dbg.log("ponte", "aggiornamento scaricato: " + u);
                if (u == null) return;
                Intent inst = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(inst);
                } catch (Exception e) {
                    Dbg.log("ponte", "installazione: " + e);
                }
            }
        }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        return "scarico l'ultima versione: sull'orologio comparirà INSTALLA";
    }

    // ===================== GPS =====================

    private LocationManager lm;
    private volatile Location lastFix;
    private volatile int satsUsed = 0, satsSeen = 0;
    private volatile float bestCn0 = 0;
    private long lastGpsLog = 0;
    private boolean gpsOn = false;

    private final LocationListener locL = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            lastFix = l;
            long now = System.currentTimeMillis();
            if (now - lastGpsLog > 5000) {
                lastGpsLog = now;
                Dbg.log("gps", gpsLine());
            }
        }

        @Override
        public void onStatusChanged(String p, int s, Bundle e) {
        }

        @Override
        public void onProviderEnabled(String p) {
            Dbg.log("gps", "provider acceso " + p);
        }

        @Override
        public void onProviderDisabled(String p) {
            Dbg.log("gps", "provider SPENTO " + p);
        }
    };

    private Object gnssCb;

    private void startGps() {
        if (gpsOn) return;
        try {
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locL, Looper.getMainLooper());
            if (Build.VERSION.SDK_INT >= 24) {
                GnssStatus.Callback cb = new GnssStatus.Callback() {
                    @Override
                    public void onSatelliteStatusChanged(GnssStatus st) {
                        int used = 0;
                        float best = 0;
                        for (int i = 0; i < st.getSatelliteCount(); i++) {
                            if (st.usedInFix(i)) used++;
                            best = Math.max(best, st.getCn0DbHz(i));
                        }
                        satsUsed = used;
                        satsSeen = st.getSatelliteCount();
                        bestCn0 = best;
                    }

                    @Override
                    public void onFirstFix(int ttff) {
                        Dbg.log("gps", "primo punto dopo " + ttff / 1000 + " s");
                    }
                };
                lm.registerGnssStatusCallback(cb);
                gnssCb = cb;
            }
            gpsOn = true;
            Dbg.log("gps", "ascolto GPS avviato");
        } catch (SecurityException e) {
            Dbg.log("gps", "manca il permesso posizione");
        } catch (Exception e) {
            Dbg.log("gps", "errore " + e);
        }
    }

    private void stopGps() {
        if (!gpsOn || lm == null) return;
        try {
            lm.removeUpdates(locL);
            if (Build.VERSION.SDK_INT >= 24 && gnssCb != null) lm.unregisterGnssStatusCallback((GnssStatus.Callback) gnssCb);
        } catch (Exception ignored) {
        }
        gpsOn = false;
    }

    private String gpsLine() {
        Location l = lastFix != null ? lastFix : Nav.lastLocation(this);
        String fix = l == null ? "nessun punto"
                : String.format(Locale.ITALY, "%.5f,%.5f ±%.0fm vel %.1f km/h età %ds (%s)", l.getLatitude(), l.getLongitude(),
                l.getAccuracy(), l.getSpeed() * 3.6, (System.currentTimeMillis() - l.getTime()) / 1000, l.getProvider());
        return fix + " · satelliti usati " + satsUsed + "/" + satsSeen + " · segnale max " + Math.round(bestCn0) + " dB"
                + (gpsOn ? "" : " (ascolto spento)");
    }

    // ===================== stato complessivo =====================

    private String snapshot() {
        StringBuilder sb = new StringBuilder();
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int lvl = bat != null ? bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
        sb.append("versione ").append(version()).append(" · batteria ").append(lvl).append("% · veicolo ")
                .append(new Store(this).isScooter() ? "monopattino" : "moto").append(" · rete ").append(netName()).append('\n');
        sb.append("MONOPATTINO: ").append(ScooterService.state()).append('\n');
        ScooterService s = ScooterService.instance;
        if (s != null) sb.append("  ").append(s.internals()).append('\n');
        sb.append("TELECOMANDO: ").append(RemoteService.status).append(" · ").append(RemoteService.diag()).append('\n');
        sb.append("GPS: ").append(gpsLine());
        return sb.toString();
    }

    private String netName() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        return ni == null ? "nessuna" : ni.getTypeName() + " " + ni.getExtraInfo();
    }

    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        return ni != null && ni.isConnected();
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ===================== HTTP =====================

    static final class Unauthorized extends Exception {
    }

    static final class Resp {
        int code;
        String body;
        String etag;
    }

    private Resp api(String method, String path, String json) throws Exception {
        return api(method, path, json, null, false);
    }

    private Resp api(String method, String path, String json, String ifNoneMatch) throws Exception {
        return api(method, path, json, ifNoneMatch, false);
    }

    private Resp api(String method, String path, String json, String ifNoneMatch, boolean tolerate) throws Exception {
        Resp r = http(method, "https://api.github.com" + path, json, true, ifNoneMatch);
        if (r.code == 401) throw new Unauthorized();
        if (r.code >= 400 && !tolerate) throw new Exception("GitHub " + r.code + " " + method + " " + path);
        return r;
    }

    private Resp http(String method, String url, String body, boolean github, String ifNoneMatch) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", github ? "application/vnd.github+json" : "application/json");
        c.setRequestProperty("User-Agent", "moto-nav-watch");
        if (github && token() != null) c.setRequestProperty("Authorization", "Bearer " + token());
        if (ifNoneMatch != null) c.setRequestProperty("If-None-Match", ifNoneMatch);
        try {
            c.setRequestMethod(method);
        } catch (java.net.ProtocolException pe) {
            c.setRequestMethod("POST");
            c.setRequestProperty("X-HTTP-Method-Override", method);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", github ? "application/json; charset=utf-8" : "application/x-www-form-urlencoded");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes("UTF-8"));
            }
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        r.etag = c.getHeaderField("ETag");
        InputStream in = r.code >= 400 ? c.getErrorStream() : (r.code == 304 ? null : c.getInputStream());
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
        }
        r.body = bo.toString("UTF-8");
        c.disconnect();
        return r;
    }

    private static String iso(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t));
    }

    // ===================== notifica =====================

    @SuppressWarnings("deprecation")
    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel("bridge", "Banco prove", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "bridge");
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 21, new Intent(this, BridgeActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 22, new Intent(this, Bridge.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Banco prove")
                .setContentText(state)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(R.drawable.ic_launcher, "SPEGNI", stop).build())
                .build();
    }
}
