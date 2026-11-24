package it.lorenzo.motonav;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

/**
 * Fa vedere l'orologio all'iPhone come un telecomando Bluetooth multimediale
 * (HID su Bluetooth LE, tasti "consumer": play/pausa, volume, brano successivo/precedente).
 * iOS lo accetta senza app: i tasti comandano l'app che sta suonando (Spotify).
 */
public class RemoteService extends Service {

    static final String ACTION_KEY = "it.lorenzo.motonav.KEY";
    static final String ACTION_STOP = "it.lorenzo.motonav.STOP";
    static final String EXTRA_KEY = "key";

    // un bit per tasto, nello stesso ordine della REPORT_MAP
    static final int PLAY_PAUSE = 0x01;
    static final int NEXT = 0x02;
    static final int PREV = 0x04;
    static final int VOL_UP = 0x08;
    static final int VOL_DOWN = 0x10;
    static final int VOICE = 0x40;   // "Voice Command": Siri
    static final int POWER = 0x80;   // tasto laterale: tenuto 2 secondi apre Siri

    private static final byte[] REPORT_MAP = {
            0x05, 0x0C,             // Usage Page (Consumer)
            0x09, 0x01,             // Usage (Consumer Control)
            (byte) 0xA1, 0x01,      // Collection (Application)
            (byte) 0x85, 0x01,      //   Report ID 1
            0x15, 0x00,             //   Logical Minimum 0
            0x25, 0x01,             //   Logical Maximum 1
            0x75, 0x01,             //   Report Size 1
            (byte) 0x95, 0x08,      //   Report Count 8
            0x09, (byte) 0xCD,      //   Play/Pause
            0x09, (byte) 0xB5,      //   Scan Next Track
            0x09, (byte) 0xB6,      //   Scan Previous Track
            0x09, (byte) 0xE9,      //   Volume Increment
            0x09, (byte) 0xEA,      //   Volume Decrement
            0x09, (byte) 0xE2,      //   Mute
            0x09, (byte) 0xCF,      //   Voice Command (Siri)
            0x09, 0x30,             //   Power (tasto laterale)
            (byte) 0x81, 0x02,      //   Input (Data, Variable, Absolute)
            (byte) 0xC0             // End Collection
    };

    private static final UUID HID = uuid(0x1812);
    private static final UUID HID_INFO = uuid(0x2A4A);
    private static final UUID HID_REPORT_MAP = uuid(0x2A4B);
    private static final UUID HID_CONTROL = uuid(0x2A4C);
    private static final UUID HID_REPORT = uuid(0x2A4D);
    private static final UUID HID_PROTOCOL = uuid(0x2A4E);
    private static final UUID BATTERY = uuid(0x180F);
    private static final UUID BATTERY_LEVEL = uuid(0x2A19);
    private static final UUID DEVICE_INFO = uuid(0x180A);
    private static final UUID MANUFACTURER = uuid(0x2A29);
    private static final UUID PNP_ID = uuid(0x2A50);
    private static final UUID CCCD = uuid(0x2902);
    private static final UUID REPORT_REF = uuid(0x2908);

    // ---- stato letto dalla schermata ----
    static volatile String status = "Spento";
    static volatile boolean connected = false;
    static volatile Runnable listener;
    // diagnosi mostrata nella schermata ♪
    static volatile boolean notifyOn = false;
    static volatile String lastSend = "nessuno";
    static volatile int keyCount = 0;
    static RemoteService instance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothManager bm;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser adv;
    private BluetoothGattCharacteristic report;
    private BluetoothGattCharacteristic batteryLevel;
    private BluetoothDevice device;
    private final Queue<BluetoothGattService> pending = new LinkedList<>();
    private boolean advertising = false;

    static UUID uuid(int s) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", s));
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(7, buildNotification());
        if (server == null && !start()) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_KEY.equals(action)) press(intent.getIntExtra(EXTRA_KEY, 0));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (adv != null && advertising) adv.stopAdvertising(advCallback);
        } catch (Exception ignored) {
        }
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
        connected = false;
        instance = null;
        if (!status.startsWith("Questo") && !status.startsWith("Accendi")) setStatus("Spento");
        super.onDestroy();
    }

    // ================= avvio =================

    private boolean start() {
        bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null) {
            setStatus("Questo orologio non ha il Bluetooth utilizzabile");
            return false;
        }
        if (!ad.isEnabled()) {
            setStatus("Accendi il Bluetooth dell'orologio e riprova");
            return false;
        }
        adv = ad.getBluetoothLeAdvertiser();
        if (adv == null || !ad.isMultipleAdvertisementSupported()) {
            setStatus("Questo orologio non supporta la modalità telecomando Bluetooth LE");
            return false;
        }
        server = bm.openGattServer(this, gattCallback);
        if (server == null) {
            setStatus("Questo orologio non riesce ad aprire il servizio Bluetooth");
            return false;
        }
        pending.clear();
        pending.add(hidService());
        pending.add(batteryService());
        pending.add(deviceInfoService());
        setStatus("Preparo il telecomando…");
        addNextService();
        return true;
    }

    private void addNextService() {
        if (server == null) return;
        BluetoothGattService s = pending.poll();
        if (s == null) {
            startAdvertising();
        } else if (!server.addService(s)) {
            setStatus("Errore nel preparare il Bluetooth: riprova");
        }
    }

    private BluetoothGattService hidService() {
        BluetoothGattService s = new BluetoothGattService(HID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic info = new BluetoothGattCharacteristic(HID_INFO,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);
        info.setValue(new byte[]{0x11, 0x01, 0x00, 0x02});
        s.addCharacteristic(info);

        BluetoothGattCharacteristic map = new BluetoothGattCharacteristic(HID_REPORT_MAP,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);
        map.setValue(REPORT_MAP);
        s.addCharacteristic(map);

        BluetoothGattCharacteristic control = new BluetoothGattCharacteristic(HID_CONTROL,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
        control.setValue(new byte[]{0});
        s.addCharacteristic(control);

        BluetoothGattCharacteristic proto = new BluetoothGattCharacteristic(HID_PROTOCOL,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED | BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
        proto.setValue(new byte[]{0x01}); // modalità "report"
        s.addCharacteristic(proto);

        report = new BluetoothGattCharacteristic(HID_REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);
        report.setValue(new byte[]{0});
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        report.addDescriptor(cccd);
        BluetoothGattDescriptor ref = new BluetoothGattDescriptor(REPORT_REF,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED);
        ref.setValue(new byte[]{0x01, 0x01}); // report ID 1, tipo input
        report.addDescriptor(ref);
        s.addCharacteristic(report);
        return s;
    }

    private BluetoothGattService batteryService() {
        BluetoothGattService s = new BluetoothGattService(BATTERY, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        batteryLevel = new BluetoothGattCharacteristic(BATTERY_LEVEL,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        batteryLevel.setValue(new byte[]{(byte) batteryPct()});
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        batteryLevel.addDescriptor(cccd);
        s.addCharacteristic(batteryLevel);
        return s;
    }

    private BluetoothGattService deviceInfoService() {
        BluetoothGattService s = new BluetoothGattService(DEVICE_INFO, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic man = new BluetoothGattCharacteristic(MANUFACTURER,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ);
        man.setValue("MotoNav");
        s.addCharacteristic(man);
        BluetoothGattCharacteristic pnp = new BluetoothGattCharacteristic(PNP_ID,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ);
        pnp.setValue(new byte[]{0x02, 0x34, 0x12, 0x01, 0x00, 0x01, 0x00});
        s.addCharacteristic(pnp);
        return s;
    }

    private void startAdvertising() {
        if (adv == null || advertising) return;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(HID))
                .setIncludeTxPowerLevel(false)
                .build();
        AdvertiseData scan = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();
        try {
            adv.startAdvertising(settings, data, scan, advCallback);
        } catch (Exception e) {
            setStatus("Errore Bluetooth: " + e.getMessage());
        }
    }

    private void restartAdvertising() {
        try {
            if (adv != null && advertising) adv.stopAdvertising(advCallback);
        } catch (Exception ignored) {
        }
        advertising = false;
        startAdvertising();
    }

    private final AdvertiseCallback advCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings s) {
            advertising = true;
            if (!connected) setStatus("In attesa dell'iPhone · cerca \"" + deviceName() + "\" in Impostazioni → Bluetooth");
        }

        @Override
        public void onStartFailure(int code) {
            advertising = false;
            if (code == ADVERTISE_FAILED_ALREADY_STARTED) {
                advertising = true;
                return;
            }
            if (!connected) setStatus("Questo orologio non riesce a farsi vedere dall'iPhone (errore " + code + ")");
        }
    };

    // ================= tasti =================

    /** Preme e rilascia un tasto multimediale. */
    void press(int bits) {
        if (!connected || device == null || server == null || report == null) {
            setStatus(connected ? status : "iPhone non collegato · cerca \"" + deviceName() + "\" in Impostazioni → Bluetooth");
            return;
        }
        send((byte) bits);
        h.postDelayed(() -> send((byte) 0), 60);
    }

    /** Tiene premuto un tasto per un certo tempo (es. tasto laterale per Siri). */
    void hold(int bits, long ms) {
        if (!connected || device == null || server == null || report == null) {
            press(bits); // mostra il messaggio "iPhone non collegato"
            return;
        }
        send((byte) bits);
        h.postDelayed(() -> send((byte) 0), ms);
    }

    /** Attiva Siri con il metodo scelto nelle impostazioni. */
    static void siri(android.content.Context c) {
        boolean side = "laterale".equals(new Store(c).siriMethod());
        if (instance != null) {
            if (side) instance.hold(POWER, 2000);
            else instance.press(VOICE);
        } else {
            c.startService(new Intent(c, RemoteService.class));
            android.widget.Toast.makeText(c, "Collego l'iPhone… riprova tra qualche secondo", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void send(byte v) {
        try {
            report.setValue(new byte[]{v});
            boolean ok = server.notifyCharacteristicChanged(device, report, false);
            if (v != 0) {
                keyCount++;
                lastSend = ok ? "partito (" + keyCount + ")" : "rifiutato da Android";
                Runnable l = listener;
                if (l != null) h.post(l);
            }
        } catch (Exception e) {
            lastSend = "errore: " + e.getMessage();
        }
    }

    /** Riga di diagnosi: abbinamento, ricezione tasti attivata dall'iPhone, ultimo tasto. */
    static String diag() {
        RemoteService s = instance;
        String bonded = "?";
        try {
            if (s != null && s.device != null)
                bonded = s.device.getBondState() == BluetoothDevice.BOND_BONDED ? "sì" : "NO";
        } catch (Exception ignored) {
        }
        return "Abbinato: " + bonded + " · Ricezione tasti: " + (notifyOn ? "sì" : "NO")
                + " · Ultimo tasto: " + lastSend;
    }

    static void key(android.content.Context c, int bits) {
        if (instance != null) {
            instance.press(bits);
        } else {
            Intent i = new Intent(c, RemoteService.class).setAction(ACTION_KEY).putExtra(EXTRA_KEY, bits);
            c.startService(i);
        }
    }

    // ================= callback GATT =================

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int st, BluetoothGattService s) {
            h.post(RemoteService.this::addNextService);
        }

        @Override
        public void onConnectionStateChange(final BluetoothDevice dev, int st, int newState) {
            h.post(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    device = dev;
                    connected = true;
                    setStatus("Collegato a " + name(dev));
                    try {
                        // tiene aperta la connessione anche quando lo schermo si spegne
                        if (server != null) server.connect(dev, false);
                    } catch (Exception ignored) {
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (device != null && device.equals(dev)) {
                        device = null;
                        connected = false;
                        setStatus("iPhone scollegato · riavvicinalo, si ricollega da solo");
                        restartAdvertising();
                    }
                }
            });
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice dev, int id, int offset, BluetoothGattCharacteristic c) {
            if (BATTERY_LEVEL.equals(c.getUuid())) c.setValue(new byte[]{(byte) batteryPct()});
            respond(dev, id, offset, c.getValue());
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice dev, int id, BluetoothGattCharacteristic c,
                                                 boolean prepared, boolean needResponse, int offset, byte[] value) {
            if (value != null) c.setValue(value);
            if (needResponse && server != null) server.sendResponse(dev, id, BluetoothGatt.GATT_SUCCESS, 0, null);
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice dev, int id, int offset, BluetoothGattDescriptor d) {
            respond(dev, id, offset, d.getValue());
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice dev, int id, BluetoothGattDescriptor d,
                                             boolean prepared, boolean needResponse, int offset, byte[] value) {
            if (value != null) d.setValue(value);
            if (CCCD.equals(d.getUuid()) && d.getCharacteristic() != null
                    && HID_REPORT.equals(d.getCharacteristic().getUuid()) && value != null && value.length > 0) {
                notifyOn = (value[0] & 0x01) != 0;
                Runnable l = listener;
                if (l != null) h.post(l);
            }
            if (needResponse && server != null) server.sendResponse(dev, id, BluetoothGatt.GATT_SUCCESS, 0, null);
        }
    };

    private void respond(BluetoothDevice dev, int id, int offset, byte[] value) {
        if (server == null) return;
        if (value == null) value = new byte[0];
        if (offset > value.length) {
            server.sendResponse(dev, id, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            return;
        }
        server.sendResponse(dev, id, BluetoothGatt.GATT_SUCCESS, offset,
                Arrays.copyOfRange(value, offset, value.length));
    }

    // ================= aiuti =================

    private void setStatus(String s) {
        status = s;
        Runnable l = listener;
        if (l != null) h.post(l);
        if (server != null) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(7, buildNotification());
        }
    }

    private String deviceName() {
        try {
            String n = bm.getAdapter().getName();
            return n != null ? n : "orologio";
        } catch (Exception e) {
            return "orologio";
        }
    }

    private static String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n != null ? n : "iPhone";
        } catch (Exception e) {
            return "iPhone";
        }
    }

    private int batteryPct() {
        try {
            BatteryManager b = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int v = b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return v > 0 && v <= 100 ? v : 100;
        } catch (Exception e) {
            return 100;
        }
    }

    private PendingIntent keyIntent(int bits) {
        Intent i = new Intent(this, RemoteService.class).setAction(ACTION_KEY).putExtra(EXTRA_KEY, bits);
        return PendingIntent.getService(this, bits, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("remote", "Telecomando musica",
                    NotificationManager.IMPORTANCE_LOW);
            if (nm != null) nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, "remote");
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, RemoteActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(R.drawable.ic_note)
                .setContentTitle("Telecomando iPhone")
                .setContentText(status)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(R.drawable.ic_note, "VOL −", keyIntent(VOL_DOWN)).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_note, "▶ ❚❚", keyIntent(PLAY_PAUSE)).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_note, "VOL +", keyIntent(VOL_UP)).build())
                .build();
    }
}
