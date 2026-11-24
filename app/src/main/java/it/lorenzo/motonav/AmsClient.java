package it.lorenzo.motonav;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;

import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.UUID;

/**
 * Apple Media Service (AMS): il canale ufficiale Apple con cui orologi e accessori Bluetooth
 * comandano la musica dell'iPhone (play/pausa, volume, brani) e leggono il titolo della canzone.
 * Usa lo stesso collegamento già aperto dall'iPhone verso l'orologio.
 */
class AmsClient {

    static final UUID AMS = UUID.fromString("89d3502b-0f36-433a-8ef4-c502ad55f8dc");
    static final UUID REMOTE_CMD = UUID.fromString("9b3c81d8-57b1-4a8a-b8df-0e56f7ca51c2");
    static final UUID ENTITY_UPDATE = UUID.fromString("2f7cabce-808d-411f-9a0c-bb92ba96c102");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final int CMD_TOGGLE = 2, CMD_NEXT = 3, CMD_PREV = 4, CMD_VOL_UP = 5, CMD_VOL_DOWN = 6;

    private final Context ctx;
    private final Handler h;
    private final Runnable onChange;

    private BluetoothGatt gatt;
    private BluetoothDevice dev;
    private BluetoothGattCharacteristic cmd, entity;
    private final LinkedList<Runnable> ops = new LinkedList<>();
    private boolean busy = false;
    private int discoverTries = 0;

    volatile boolean ready = false;
    volatile String state = "non avviato";
    volatile String artist = "", title = "";

    AmsClient(Context ctx, Handler h, Runnable onChange) {
        this.ctx = ctx;
        this.h = h;
        this.onChange = onChange;
    }

    void start(BluetoothDevice d) {
        stop();
        dev = d;
        discoverTries = 0;
        state = "collegamento…";
        changed();
        try {
            gatt = Build.VERSION.SDK_INT >= 23
                    ? d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                    : d.connectGatt(ctx, false, cb);
        } catch (Exception e) {
            state = "errore: " + e.getMessage();
            changed();
        }
    }

    void stop() {
        ready = false;
        ops.clear();
        busy = false;
        cmd = null;
        entity = null;
        if (gatt != null) {
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
        }
        gatt = null;
    }

    /** Dopo l'abbinamento l'iPhone mostra i suoi servizi: li riscopro. */
    void onBonded(BluetoothDevice d) {
        if (gatt != null && !ready && d.equals(dev)) {
            discoverTries = 0;
            rediscover();
        }
    }

    boolean command(int id) {
        if (!ready || gatt == null || cmd == null) return false;
        enqueue(() -> {
            cmd.setValue(new byte[]{(byte) id});
            cmd.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (!gatt.writeCharacteristic(cmd)) done();
        });
        return true;
    }

    String nowPlaying() {
        if (title.isEmpty()) return "";
        return artist.isEmpty() ? title : artist + " – " + title;
    }

    // ---------------- interni ----------------

    private void rediscover() {
        if (gatt == null) return;
        try {
            // svuota la cache dei servizi (metodo nascosto di Android)
            gatt.getClass().getMethod("refresh").invoke(gatt);
        } catch (Exception ignored) {
        }
        h.postDelayed(() -> {
            if (gatt != null) gatt.discoverServices();
        }, 600);
    }

    private void enqueue(Runnable r) {
        ops.add(r);
        if (!busy) next();
    }

    private void next() {
        Runnable r = ops.poll();
        if (r == null) {
            busy = false;
            return;
        }
        busy = true;
        try {
            r.run();
        } catch (Exception e) {
            done();
        }
    }

    private void done() {
        busy = false;
        h.post(this::next);
    }

    private void subscribe(BluetoothGattCharacteristic c) {
        enqueue(() -> {
            gatt.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d == null) {
                done();
                return;
            }
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!gatt.writeDescriptor(d)) done();
        });
    }

    private void changed() {
        if (onChange != null) h.post(onChange);
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int st, int ns) {
            h.post(() -> {
                if (g != gatt) return;
                if (ns == BluetoothProfile.STATE_CONNECTED) {
                    state = "cerco il servizio musica…";
                    changed();
                    g.discoverServices();
                } else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                    ready = false;
                    state = "scollegato";
                    changed();
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int st) {
            h.post(() -> {
                if (g != gatt) return;
                BluetoothGattService s = g.getService(AMS);
                if (s == null) {
                    discoverTries++;
                    state = "servizio musica non visibile (" + discoverTries + ")";
                    changed();
                    if (discoverTries < 6) h.postDelayed(AmsClient.this::rediscover, 2500);
                    return;
                }
                cmd = s.getCharacteristic(REMOTE_CMD);
                entity = s.getCharacteristic(ENTITY_UPDATE);
                if (cmd == null) {
                    state = "servizio musica incompleto";
                    changed();
                    return;
                }
                subscribe(cmd);
                if (entity != null) {
                    subscribe(entity);
                    // chiede artista (0) e titolo (2) della canzone in corso (entità 2 = traccia)
                    enqueue(() -> {
                        entity.setValue(new byte[]{2, 0, 2});
                        entity.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                        if (!gatt.writeCharacteristic(entity)) done();
                    });
                }
                enqueue(() -> {
                    ready = true;
                    state = "pronto";
                    changed();
                    done();
                });
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            h.post(AmsClient.this::done);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            h.post(AmsClient.this::done);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (!ENTITY_UPDATE.equals(c.getUuid())) return;
            byte[] v = c.getValue();
            if (v == null || v.length < 3) return;
            final int ent = v[0], attr = v[1];
            final String text = new String(v, 3, v.length - 3, StandardCharsets.UTF_8);
            h.post(() -> {
                if (ent == 2 && attr == 0) artist = text;
                if (ent == 2 && attr == 2) title = text;
                changed();
            });
        }
    };
}
