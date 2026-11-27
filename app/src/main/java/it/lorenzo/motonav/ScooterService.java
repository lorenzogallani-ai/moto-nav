package it.lorenzo.motonav;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * Collegamento Bluetooth al Segway-Ninebot Max G30: abbinamento cifrato,
 * lettura di velocità / batteria / autonomia, cambio modalità di guida e fari.
 * Resta attivo in sottofondo (notifica) così i dati arrivano anche mentre navighi.
 */
public class ScooterService extends Service {

    static final String ACTION_STOP = "it.lorenzo.motonav.SCOOTER_STOP";
    static final String ACTION_MODE = "it.lorenzo.motonav.SCOOTER_MODE";
    static final String ACTION_LIGHT = "it.lorenzo.motonav.SCOOTER_LIGHT";
    static final String EXTRA_MODE = "mode";

    static final int MODE_DRIVE = 0, MODE_ECO = 1, MODE_SPORT = 2;

    // indirizzi e comandi del protocollo Ninebot
    private static final int APP = 0x3E, ESC = 0x20, BLE = 0x21;
    private static final int READ = 0x01, WRITE_NR = 0x03, READ_ACK = 0x04;
    private static final int REG_QUICK = 0xB0, REG_MODE = 0x75, REG_FUN = 0x80;
    // registri "classici" della scheda di controllo, validi su Max G2 e G30
    private static final int REG_BATT = 0x22, REG_RANGE = 0x25, REG_SPEED = 0x26;
    private static final int REG_FUN_G2 = 0x7D;   // luci sul Max G2

    private static final UUID NUS = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_TX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_RX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NB = UUID.fromString("6e400001-0000-0000-006e-696e65626f74");
    private static final UUID NB_TX = UUID.fromString("6e400002-0000-0000-006e-696e65626f74");
    private static final UUID NB_RX = UUID.fromString("6e400004-0000-0000-006e-696e65626f74");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ---- stato letto dalle schermate ----
    static volatile String status = "Spento";
    static volatile boolean ready = false;
    static volatile boolean needButton = false;
    static volatile double speed = Double.NaN;      // km/h
    static volatile int battery = -1;               // %
    static volatile double rangeKm = Double.NaN;    // autonomia stimata
    static volatile double tripKm = Double.NaN;
    static volatile int mode = -1;
    static volatile int funWord = -1;               // bit 0 = fari sempre accesi
    static volatile int error = 0;
    static volatile String scooterName = null;
    // diagnosi mostrata nel cruscotto
    static volatile int seenDevices = 0, txFrames = 0, rxFrames = 0, rxBytes = 0;
    static volatile String lastRx = "-", phase = "-";
    static final String ACTION_PICK = "it.lorenzo.motonav.SCOOTER_PICK";
    // dispositivi Bluetooth visti durante la ricerca, per sceglierli a mano se non riconosco il monopattino
    static final java.util.LinkedHashMap<String, String> seen = new java.util.LinkedHashMap<>();
    static volatile boolean needPick = false;

    static String diag() {
        return "Fase: " + phase + " · visti " + seenDevices + " · inviati " + txFrames
                + " · ricevuti " + rxFrames + " (" + rxBytes + " B) · ultimo: " + lastRx
                + "\nmodo75=" + rawMode + " marcia F1=" + rawGear + " luci7D=" + raw7d + " luci80=" + raw80;
    }
    static ScooterService instance;

    private static final int STAGE_IDLE = 0, STAGE_PRE = 1, STAGE_PAIR = 2, STAGE_AUTH = 3, STAGE_COMM = 4;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Store store;
    private BluetoothAdapter ad;
    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx;
    private String devName;
    private NbCrypto crypto;
    private boolean plain = false;
    private int stage = STAGE_IDLE;
    private byte[] serial;
    private int tries = 0;
    private long stageStart = 0;
    private int pollTick = 0;
    private long lastFrame = 0;
    private boolean stopping = false;
    private int bleAddr = BLE;          // modulo Bluetooth del monopattino: 0x21 (G30) o 0x04 (modelli nuovi)
    private int connectFails = 0;
    private int ctrlAddr = -1;          // scheda di controllo che risponde ai dati (0x20 sul G30, da scoprire sul G2)
    private int probeRound = 0;
    private boolean forcePair = false;  // dopo un AUTH fallito rifà l'abbinamento completo
    private int chunk = 20;             // byte per pacchetto Bluetooth (cresce se il monopattino accetta un MTU più grande)
    private BluetoothGattCharacteristic rxChar;
    private boolean notifyStarted = false;

    private boolean isG2() {
        return bleAddr == 0x04 || g2Seen;
    }

    private int funReg() {
        return fun7dSeen || isG2() ? REG_FUN_G2 : REG_FUN;
    }

    // valori grezzi letti, mostrati nella diagnosi per capire come il G2 codifica modalità e luci
    private static final int REG_GEAR_G2 = 0xF1;
    private boolean g2Seen = false, fun7dSeen = false;
    static volatile int rawMode = -1, rawGear = -1, raw7d = -1, raw80 = -1;
    private long lastNotif = 0;

    private final Runnable preR = this::sendPre;
    private final Runnable pairR = this::sendPair;
    private final Runnable authR = this::sendAuth;

    private final LinkedList<byte[]> out = new LinkedList<>();
    private boolean writing = false;
    private long writeStart = 0;
    private final ByteArrayOutputStream inbuf = new ByteArrayOutputStream();

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        store = new Store(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(8, buildNotification());
        if (ACTION_MODE.equals(action)) {
            setMode(intent.getIntExtra(EXTRA_MODE, MODE_DRIVE));
        } else if (ACTION_PICK.equals(action)) {
            String addr = intent.getStringExtra("addr");
            String nm = intent.getStringExtra("name");
            needPick = false;
            stopScan();
            if (addr != null && BluetoothAdapter.getDefaultAdapter() != null) {
                ad = BluetoothAdapter.getDefaultAdapter();
                connect(ad.getRemoteDevice(addr), nm);
            }
        } else if (ACTION_LIGHT.equals(action)) {
            toggleLight();
        } else if (gatt == null && !scanning) {
            begin();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        h.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        ready = false;
        needButton = false;
        speed = Double.NaN;
        instance = null;
        status = "Spento";
        super.onDestroy();
    }

    // ================= ricerca e collegamento =================

    private void begin() {
        if (stopping) return;
        ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) {
            fail("Questo orologio non ha il Bluetooth utilizzabile");
            return;
        }
        if (!ad.isEnabled()) {
            fail("Accendi il Bluetooth dell'orologio e riprova");
            return;
        }
        String mac = store.scooterMac();
        if (mac != null) {
            try {
                connect(ad.getRemoteDevice(mac), store.scooterName());
                return;
            } catch (Exception ignored) {
            }
        }
        scan();
    }

    private void scan() {
        phase = "ricerca";
        seenDevices = 0;
        if (!locationOn()) {
            fail("Accendi la Posizione (GPS) dell'orologio: Android la richiede per cercare il monopattino");
            return;
        }
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) {
            fail("Non riesco a cercare dispositivi Bluetooth");
            return;
        }
        seen.clear();
        needPick = false;
        setStatus("Cerco il monopattino… accendilo e chiudi l'app Segway sull'iPhone");
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(null, s, scanCb);
            scanning = true;
        } catch (Exception e) {
            fail("Per cercare il monopattino consenti la posizione a MotoNav");
            return;
        }
        h.postDelayed(() -> {
            if (scanning) {
                stopScan();
                if (!seen.isEmpty()) {
                    needPick = true;
                    fail("Non riconosco il monopattino da solo: sceglilo dall'elenco");
                } else {
                    fail("Nessun dispositivo Bluetooth trovato: accendi il monopattino, chiudi l'app Segway sull'iPhone e premi RIPROVA");
                }
            }
        }, 20000);
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            String name = null;
            boolean nus = false;
            ScanRecord rec = r.getScanRecord();
            if (rec != null) {
                name = rec.getDeviceName();
                if (rec.getManufacturerSpecificData(0x4E42) != null || rec.getManufacturerSpecificData(0x4E43) != null
                        || rec.getManufacturerSpecificData(0x424E) != null) nus = true;
                List<ParcelUuid> ids = rec.getServiceUuids();
                if (ids != null) for (ParcelUuid u : ids) if (NUS.equals(u.getUuid()) || NB.equals(u.getUuid())) nus = true;
            }
            if (name == null) name = r.getDevice().getName();
            seenDevices++;
            if (name == null && !nus) return;
            if (name == null) name = r.getDevice().getName() != null ? r.getDevice().getName() : "";
            if (!name.isEmpty()) {
                final String an = name, aa = r.getDevice().getAddress();
                final int rssi = r.getRssi();
                h.post(() -> {
                    if (!seen.containsKey(aa)) seen.put(aa, an + "  (" + rssi + " dBm)");
                });
            }
            String n = name.toUpperCase();
            boolean looks = n.startsWith("NB") || (n.contains("MAX") && !n.equals("MAX")) || n.contains("NINEBOT")
                    || n.contains("G30") || n.contains("G2") || n.contains("SEGWAY");
            // i Max G2 si presentano spesso col numero di serie (es. 01GXA2324C2526)
            boolean serialLike = n.matches("[0-9A-Z]{12,16}") && n.matches(".*[0-9].*") && n.matches(".*[A-Z].*");
            if (!looks && !nus && !serialLike) return;
            final String fname = name;
            final BluetoothDevice dev = r.getDevice();
            h.post(() -> {
                if (!scanning) return;
                stopScan();
                setStatus("Trovato " + fname + ", mi collego…");
                // alcuni Bluetooth MediaTek falliscono se ci si collega subito dopo la ricerca
                h.postDelayed(() -> connect(dev, fname), 500);
            });
        }

        @Override
        public void onScanFailed(int code) {
            h.post(() -> {
                scanning = false;
                fail("Ricerca Bluetooth non riuscita (errore " + code + "): riprova");
            });
        }
    };

    private boolean locationOn() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            return lm != null && (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER));
        } catch (Exception e) {
            return true;
        }
    }

    private void stopScan() {
        if (scanning && scanner != null) {
            try {
                scanner.stopScan(scanCb);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
    }

    private void connect(BluetoothDevice dev, String name) {
        phase = "collegamento";
        devName = name != null && !name.isEmpty() ? name : dev.getName();
        scooterName = devName;
        setStatus("Mi collego a " + (devName != null ? devName : "monopattino") + "…");
        closeGatt();
        connAddr = dev.getAddress();
        if (Build.VERSION.SDK_INT >= 23) {
            gatt = dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = dev.connectGatt(this, false, gattCb);
        }
        // se entro 15 secondi non risponde, riparte da capo
        final BluetoothGatt mine = gatt;
        h.postDelayed(() -> {
            if (gatt == mine && stage == STAGE_IDLE && !stopping) {
                closeGatt();
                if (store.scooterMac() != null) {
                    setStatus("Il monopattino non risponde: è acceso? Riprovo…");
                    h.postDelayed(this::begin, 4000);
                } else {
                    fail("Il monopattino non risponde: premi RIPROVA");
                }
            }
        }, 15000);
    }

    private void closeGatt() {
        ready = false;
        stage = STAGE_IDLE;
        out.clear();
        writing = false;
        inbuf.reset();
        tx = null;
        chunk = 20;
        ctrlAddr = -1;
        notifyStarted = false;
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (Exception ignored) {
            }
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
        }
        gatt = null;
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, int st, final int ns) {
            h.post(() -> {
                if (g != gatt) return;
                if (ns == BluetoothProfile.STATE_CONNECTED) {
                    phase = "servizi";
                    setStatus("Collegato, preparo la comunicazione…");
                    // una piccola pausa prima di leggere i servizi aiuta i Bluetooth più vecchi
                    h.postDelayed(() -> {
                        if (g == gatt) g.discoverServices();
                    }, 600);
                } else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean wasReady = ready;
                    closeGatt();
                    speed = Double.NaN;
                    if (stopping) return;
                    if (!wasReady) {
                        connectFails++;
                        phase = "collegamento fallito (codice " + st + ", tentativo " + connectFails + ")";
                        if (connectFails >= 3 && store.scooterMac() == null) {
                            connectFails = 0;
                            fail("Non riesco a collegarmi (codice " + st + "). Spegni e riaccendi il monopattino, poi RIPROVA");
                            return;
                        }
                    } else {
                        connectFails = 0;
                    }
                    setStatus(wasReady ? "Monopattino scollegato, riprovo…" : "Collegamento non riuscito, riprovo…");
                    h.postDelayed(ScooterService.this::begin, 3000);
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, int st) {
            h.post(() -> {
                if (g != gatt) return;
                BluetoothGattService s = g.getService(NUS);
                UUID rxId = NUS_RX, txId = NUS_TX;
                if (s == null || s.getCharacteristic(NUS_RX) == null) {
                    s = g.getService(NB);
                    rxId = NB_RX;
                    txId = NB_TX;
                }
                if (s == null) {
                    fail("Questo dispositivo non sembra un monopattino Ninebot");
                    closeGatt();
                    return;
                }
                phase = (NUS_RX.equals(rxId) ? "servizio Nordic" : "servizio Ninebot") + ", attivo notifiche";
                tx = s.getCharacteristic(txId);
                BluetoothGattCharacteristic rx = s.getCharacteristic(rxId);
                if (tx == null || rx == null) {
                    fail("Servizio Bluetooth del monopattino incompleto");
                    closeGatt();
                    return;
                }
                rxChar = rx;
                notifyStarted = false;
                phase += " · MTU";
                enableNotify();
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int st) {
            h.post(() -> {
                if (g != gatt) return;
                // pacchetti sempre da 20 byte: con pacchetti più grandi il monopattino smetteva di rispondere
                if (!notifyStarted) enableNotify();
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            h.post(() -> {
                if (g == gatt) startHandshake();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            h.post(() -> {
                writing = false;
                h.postDelayed(ScooterService.this::pump, 8);
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            final byte[] v = c.getValue() != null ? c.getValue().clone() : null;
            h.post(() -> {
                if (g == gatt && v != null) onData(v);
            });
        }
    };

    private void enableNotify() {
        if (gatt == null || rxChar == null || notifyStarted) return;
        notifyStarted = true;
        phase = "notifiche (pacchetti da " + chunk + " B)";
        gatt.setCharacteristicNotification(rxChar, true);
        BluetoothGattDescriptor d = rxChar.getDescriptor(CCCD);
        if (d != null) {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!gatt.writeDescriptor(d)) startHandshake();
        } else {
            startHandshake();
        }
    }

    // ================= abbinamento cifrato =================

    private void startHandshake() {
        txFrames = 0;
        rxFrames = 0;
        rxBytes = 0;
        lastRx = "-";
        stage = STAGE_PRE;
        plain = false;
        tries = 0;
        sendPre();
    }

    /** 0x5B: chiede al modulo Bluetooth il suo numero casuale e il numero di serie. */
    private void sendPre() {
        if (stage != STAGE_PRE || gatt == null) return;
        tries++;
        if (tries > 6) {
            // nessuna risposta cifrata: provo il vecchio protocollo in chiaro
            plain = true;
            stage = STAGE_COMM;
            stageStart = SystemClock.elapsedRealtime();
            lastFrame = 0;
            setStatus("Provo il collegamento senza cifratura…");
            startPolling();
            return;
        }
        crypto = new NbCrypto(devName != null ? devName : "");
        bleAddr = (tries % 2 == 1) ? BLE : 0x04;
        phase = "abbinamento 1/3 (5B a 0x" + Integer.toHexString(bleAddr) + ")";
        send(frame(bleAddr, 0x5B, 0x00, new byte[0]));
        h.postDelayed(preR, 1500);
    }

    /** 0x5C: propone la nostra chiave; la prima volta serve premere il pulsante del monopattino. */
    private void sendPair() {
        if (stage != STAGE_PAIR || gatt == null) return;
        long t = SystemClock.elapsedRealtime() - stageStart;
        if (t > 60000) {
            needButton = false;
            fail("Abbinamento non confermato: premi RIPROVA e poi il pulsante di accensione del monopattino");
            closeGatt();
            return;
        }
        if (t > 2500 && !needButton) {
            needButton = true;
            setStatus("Premi una volta il pulsante di accensione del monopattino per abbinarlo");
        }
        phase = "abbinamento 2/3 (5C)";
        send(frame(bleAddr, 0x5C, 0x00, store.scooterKey()));
        h.postDelayed(pairR, 1000);
    }

    /** 0x5D: conferma con il numero di serie. */
    private void sendAuth() {
        if (stage != STAGE_AUTH || gatt == null) return;
        tries++;
        if (tries > 4) {
            setStatus("Abbinamento rifiutato, ricomincio…");
            startHandshake();
            return;
        }
        phase = "abbinamento 3/3 (5D)";
        send(frame(bleAddr, 0x5D, 0x00, serial));
        h.postDelayed(authR, 1500);
    }

    // ================= lettura dati =================

    private void startPolling() {
        h.removeCallbacks(poll);
        pollTick = 0;
        probeRound = 0;
        h.post(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (stage != STAGE_COMM || gatt == null) return;
            if (plain && lastFrame == 0 && SystemClock.elapsedRealtime() - stageStart > 6000) {
                fail("Il monopattino non risponde: chiudi l'app Segway sull'iPhone, spegni e riaccendi il monopattino, poi RIPROVA");
                closeGatt();
                return;
            }
            if (ctrlAddr < 0) ctrlAddr = ESC;
            phase = "dati";
            if (out.size() < 8 && pollOn) {
                // blocco rapido 0xB0: velocità, batteria, autonomia in una sola lettura (funzionava già)
                send(frame(ctrlAddr, READ, REG_QUICK, new byte[]{0x20}));
                if (pollTick % 10 == 0) {
                    send(frame(ctrlAddr, READ, REG_MODE, new byte[]{0x02}));
                    send(frame(ctrlAddr, READ, REG_GEAR_G2, new byte[]{0x02}));
                    send(frame(ctrlAddr, READ, REG_FUN_G2, new byte[]{0x02}));
                    send(frame(ctrlAddr, READ, REG_FUN, new byte[]{0x02}));
                }
            }
            pollTick++;
            h.postDelayed(this, 500);
        }
    };

    /*
     * SCRITTURE DISATTIVATE (27/11/2026): sul Max G2 il registro 0xF1 non è la modalità ma la velocità
     * massima della modalità Sport impostata nell'app, e la nostra scrittura l'ha abbassata a 15 km/h.
     * Anche 0x75 e 0x7D non sono quello che pensavamo. Finché non troviamo i registri giusti leggendo
     * (comando "sc scan" del banco prove), l'orologio non scrive niente sul monopattino.
     */
    static final String NO_WRITE = "Per ora si cambia dal monopattino: due pressioni sul pulsante = modalità, una = luci";

    void setMode(int m) {
        Dbg.log("sc", "cambio modalità bloccato (scritture disattivate)");
    }

    void toggleLight() {
        Dbg.log("sc", "luci bloccate (scritture disattivate)");
    }

    // ---- lettura di tutti i registri per scoprire quelli giusti (solo lettura, nessun rischio) ----
    final java.util.TreeMap<Integer, Integer> snap = new java.util.TreeMap<>();
    final java.util.TreeMap<Integer, Integer> prevSnap = new java.util.TreeMap<>();
    private volatile int scanDst = -1;

    void scan(int dst, int from, int to) {
        h.post(() -> {
            if (stage != STAGE_COMM && !plain) {
                Dbg.log("sc", "scansione: monopattino non collegato");
                return;
            }
            prevSnap.clear();
            prevSnap.putAll(snap);
            snap.clear();
            scanDst = dst;
            int k = 0;
            for (int r = from; r <= to; r += 0x10) {
                final int reg = r;
                h.postDelayed(() -> send(frame(dst, READ, reg, new byte[]{0x20})), 120L * k++);
            }
            h.postDelayed(() -> Dbg.log("sc", "scansione finita: " + snap.size() + " registri"), 120L * k + 800);
        });
    }

    String dump() {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (java.util.Map.Entry<Integer, Integer> e : snap.entrySet()) {
            b.append(String.format("%02X=%d ", e.getKey(), e.getValue()));
            if (++n % 8 == 0) b.append('\n');
        }
        return b.toString();
    }

    String diff() {
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<Integer, Integer> e : snap.entrySet()) {
            Integer old = prevSnap.get(e.getKey());
            if (old != null && !old.equals(e.getValue()))
                b.append(String.format("%02X: %d → %d\n", e.getKey(), old, e.getValue()));
        }
        return b.length() == 0 ? "nessuna differenza" : b.toString();
    }

    // ================= pacchetti =================

    private static byte[] frame(int dst, int cmd, int arg, byte[] data) {
        byte[] f = new byte[7 + data.length];
        f[0] = 0x5A;
        f[1] = (byte) 0xA5;
        f[2] = (byte) data.length;
        f[3] = (byte) APP;
        f[4] = (byte) dst;
        f[5] = (byte) cmd;
        f[6] = (byte) arg;
        System.arraycopy(data, 0, f, 7, data.length);
        return f;
    }

    private static byte[] withChecksum(byte[] f) {
        int sum = 0;
        for (int i = 2; i < f.length; i++) sum += f[i] & 0xFF;
        int crc = ~sum & 0xFFFF;
        byte[] o = new byte[f.length + 2];
        System.arraycopy(f, 0, o, 0, f.length);
        o[f.length] = (byte) (crc & 0xFF);
        o[f.length + 1] = (byte) ((crc >> 8) & 0xFF);
        return o;
    }

    // ---- banco prove ----
    /** Indirizzo del monopattino a cui mi sto collegando: il telecomando lo deve ignorare. */
    static volatile String connAddr = null;
    static volatile boolean sniff = false, sniffAll = false, pollOn = true;

    /** Manda un pacchetto a mano: destinatario, comando, argomento, dati. */
    void raw(int dst, int cmd, int arg, byte[] data) {
        if (cmd != READ) {
            Dbg.log("sc", "comando 0x" + Integer.toHexString(cmd) + " bloccato: sono permesse solo letture");
            return;
        }
        h.post(() -> {
            if (gatt == null || tx == null || (!plain && crypto == null)) {
                Dbg.log("sc", "prova: monopattino non collegato");
                return;
            }
            boolean was = sniff;
            sniff = true;
            send(frame(dst, cmd, arg, data));
            if (!was) h.postDelayed(() -> sniff = false, 4000);
        });
    }

    static String state() {
        return "stato=" + status + " · pronto=" + ready + " · " + diag() + " · vel=" + speed + " batt=" + battery
                + " aut=" + rangeKm + " modo=" + mode + " fun=" + funWord + " err=" + error;
    }

    String internals() {
        return "stage=" + stage + " bleAddr=0x" + Integer.toHexString(bleAddr) + " ctrl=0x" + Integer.toHexString(ctrlAddr)
                + " plain=" + plain + " chunk=" + chunk + " g2Seen=" + g2Seen + " fun7d=" + fun7dSeen
                + " mac=" + (gatt != null ? gatt.getDevice().getAddress() : "-") + " name=" + devName
                + " tx=" + (tx != null ? tx.getUuid() : "-") + " rx=" + (rxChar != null ? rxChar.getUuid() : "-");
    }

    private void send(byte[] f) {
        if (gatt == null || tx == null) return;
        if (sniff && (sniffAll || (f[6] & 0xFF) != REG_QUICK)) Dbg.log("sc", "TX " + Dbg.hex(f));
        byte[] wire = plain ? withChecksum(f) : crypto.encrypt(f);
        txFrames++;
        for (int i = 0; i < wire.length; i += chunk) {
            int n = Math.min(chunk, wire.length - i);
            byte[] c = new byte[n];
            System.arraycopy(wire, i, c, 0, n);
            out.add(c);
        }
        pump();
    }

    private void pump() {
        if (gatt == null || tx == null) return;
        if (writing && SystemClock.elapsedRealtime() - writeStart < 400) return;
        byte[] c = out.poll();
        if (c == null) {
            writing = false;
            return;
        }
        tx.setValue(c);
        tx.setWriteType((tx.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        writing = gatt.writeCharacteristic(tx);
        writeStart = SystemClock.elapsedRealtime();
        if (!writing) {
            out.addFirst(c);
            h.postDelayed(this::pump, 20);
        }
    }

    private void onData(byte[] v) {
        rxBytes += v.length;
        StringBuilder hx = new StringBuilder();
        for (int i = 0; i < Math.min(8, v.length); i++) hx.append(String.format("%02X", v[i]));
        lastRx = hx.toString();
        inbuf.write(v, 0, v.length);
        byte[] b = inbuf.toByteArray();
        int pos = 0;
        while (true) {
            int start = -1;
            for (int i = pos; i + 1 < b.length; i++) {
                if ((b[i] & 0xFF) == 0x5A && (b[i + 1] & 0xFF) == 0xA5) {
                    start = i;
                    break;
                }
            }
            if (start < 0) {
                pos = b.length > 0 && (b[b.length - 1] & 0xFF) == 0x5A ? b.length - 1 : b.length;
                break;
            }
            if (b.length - start < 3) {
                pos = start;
                break;
            }
            int len = b[start + 2] & 0xFF;
            int total = len + (plain ? 9 : 13);
            if (b.length - start < total) {
                pos = start;
                break;
            }
            byte[] f = new byte[total];
            System.arraycopy(b, start, f, 0, total);
            pos = start + total;
            byte[] dec = plain ? stripChecksum(f) : crypto.decrypt(f);
            if (dec != null && dec.length >= 7) onFrame(dec);
            else if (sniff) Dbg.log("sc", "RX non decifrato " + Dbg.hex(f));
        }
        inbuf.reset();
        if (pos < b.length) inbuf.write(b, pos, b.length - pos);
    }

    private static byte[] stripChecksum(byte[] f) {
        int sum = 0;
        for (int i = 2; i < f.length - 2; i++) sum += f[i] & 0xFF;
        int crc = ~sum & 0xFFFF;
        if ((f[f.length - 2] & 0xFF) != (crc & 0xFF) || (f[f.length - 1] & 0xFF) != ((crc >> 8) & 0xFF)) return null;
        byte[] o = new byte[f.length - 2];
        System.arraycopy(f, 0, o, 0, o.length);
        return o;
    }

    private void onFrame(byte[] d) {
        if (sniff && (sniffAll || (d[6] & 0xFF) != REG_QUICK)) Dbg.log("sc", "RX " + Dbg.hex(d));
        int len = d[2] & 0xFF;
        int src = d[3] & 0xFF;
        int cmd = d[5] & 0xFF;
        int arg = d[6] & 0xFF;
        int plen = Math.min(len, d.length - 7);
        byte[] p = new byte[Math.max(0, plen)];
        System.arraycopy(d, 7, p, 0, p.length);
        lastFrame = SystemClock.elapsedRealtime();
        rxFrames++;

        boolean fromBle = src == BLE || src == 0x04;
        if (fromBle && cmd == 0x5B && stage == STAGE_PRE && p.length >= 30) {
            bleAddr = src;
            serial = new byte[14];
            System.arraycopy(p, 16, serial, 0, 14);
            h.removeCallbacks(preR);
            needButton = false;
            boolean known = gatt != null && gatt.getDevice().getAddress().equals(store.scooterMac());
            if (arg != 0 && known && !forcePair) {
                // password già registrata sul monopattino: vado diretto all'autenticazione
                crypto.useAppKey(store.scooterKey());
                stage = STAGE_AUTH;
                tries = 0;
                setStatus("Riconosciuto, mi autentico…");
                sendAuth();
            } else {
                stage = STAGE_PAIR;
                stageStart = SystemClock.elapsedRealtime();
                setStatus("Abbino il monopattino…");
                sendPair();
            }
        } else if (fromBle && cmd == 0x5C && stage == STAGE_PAIR) {
            if (arg == 0x01) {
                stage = STAGE_AUTH;
                needButton = false;
                tries = 0;
                h.removeCallbacks(pairR);
                sendAuth();
            }
        } else if (fromBle && cmd == 0x5D && stage == STAGE_AUTH) {
            h.removeCallbacks(authR);
            if (arg == 0x01) {
                stage = STAGE_COMM;
                ready = true;
                forcePair = false;
                phase = "cerco la scheda dati";
                if (gatt != null) store.setScooter(gatt.getDevice().getAddress(), devName);
                setStatus("Collegato a " + devName + (isG2() ? " (Max G2)" : ""));
                startPolling();
            } else {
                forcePair = true;
                setStatus("Autenticazione rifiutata, rifaccio l'abbinamento…");
                h.postDelayed(this::startHandshake, 1000);
            }
        } else if (!fromBle && cmd == READ_ACK) {
            if (scanDst >= 0 && src == scanDst && arg != REG_QUICK) {
                for (int i = 0; i + 1 < p.length; i += 2) snap.put(arg + i / 2, (p[i] & 0xFF) | ((p[i + 1] & 0xFF) << 8));
            }
            if (ctrlAddr < 0 && arg == REG_BATT) {
                ctrlAddr = src;
                phase = "dati da 0x" + Integer.toHexString(src);
            }
            if (plain && !ready) {
                ready = true;
                if (gatt != null) store.setScooter(gatt.getDevice().getAddress(), devName);
                setStatus("Collegato a " + devName);
            }
            if (arg == REG_QUICK && p.length >= 32) {
                error = word(p, 0);
                battery = word(p, 4);
                int sp = Math.abs(word(p, 5));
                speed = sp > 1000 ? sp / 1000.0 : sp / 10.0;
                tripKm = (word(p, 9) & 0xFFFF) / 100.0;
                rangeKm = (word(p, 15) & 0xFFFF) / 100.0;
                notifyUi();
            } else if (arg == REG_BATT && p.length >= 2) {
                int b = word(p, 0);
                if (b >= 0 && b <= 100) battery = b;
                notifyUi();
            } else if (arg == REG_RANGE && p.length >= 2) {
                rangeKm = (word(p, 0) & 0xFFFF) / 100.0;
                notifyUi();
            } else if (arg == REG_SPEED && p.length >= 2) {
                int sp = Math.abs(word(p, 0));
                speed = sp > 1000 ? sp / 1000.0 : sp / 10.0;
                notifyUi();
            } else if (arg == REG_MODE && p.length >= 2) {
                rawMode = word(p, 0) & 0xFFFF;
                mode = rawMode;
                notifyUi();
            } else if (arg == REG_GEAR_G2 && p.length >= 2) {
                rawGear = word(p, 0) & 0xFFFF;
                g2Seen = true;
                notifyUi();
            } else if (arg == REG_FUN_G2 && p.length >= 2) {
                raw7d = word(p, 0) & 0xFFFF;
                fun7dSeen = true;
                if (funReg() == REG_FUN_G2) funWord = raw7d;
                notifyUi();
            } else if (arg == REG_FUN && p.length >= 2) {
                raw80 = word(p, 0) & 0xFFFF;
                if (funReg() == REG_FUN) funWord = raw80;
                notifyUi();
            }
        }
    }

    private static int word(byte[] p, int i) {
        return (short) ((p[2 * i] & 0xFF) | ((p[2 * i + 1] & 0xFF) << 8));
    }

    // ================= aiuti =================

    private void fail(String s) {
        ready = false;
        setStatus(s);
    }

    private void setStatus(String s) {
        status = s;
        Dbg.log("sc", s);
        notifyUi();
    }

    private void notifyUi() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotif < 1500) return;
        lastNotif = now;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && !stopping) nm.notify(8, buildNotification());
    }

    static String summary() {
        if (!ready) return status;
        StringBuilder sb = new StringBuilder();
        sb.append(Double.isNaN(speed) ? "--" : String.valueOf(Math.round(speed))).append(" km/h");
        if (battery >= 0) sb.append(" · ").append(battery).append("%");
        if (!Double.isNaN(rangeKm)) sb.append(" · ").append(Math.round(rangeKm)).append(" km");
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("scooter", "Monopattino",
                    NotificationManager.IMPORTANCE_LOW);
            if (nm != null) nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, "scooter");
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_DEFAULT);
        }
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, ScooterActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, ScooterService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(scooterName != null ? scooterName : "Monopattino")
                .setContentText(summary())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(R.drawable.ic_launcher, "SCOLLEGA", stop).build())
                .build();
    }
}
