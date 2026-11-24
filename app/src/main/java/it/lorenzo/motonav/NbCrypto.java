package it.lorenzo.motonav;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifratura Bluetooth dei Ninebot "legacy" (Max G30, ESx, serie E/F con BLE 1xx/2xx/3xx).
 * Porting in Java di NinebotCrypto (scooterhacking, AGPL-3.0, basato sul reverse engineering di majsi):
 * https://github.com/scooterhacking/NinebotCrypto
 */
class NbCrypto {

    private static final byte[] FW_DATA = {
            (byte) 0x97, (byte) 0xCF, (byte) 0xB8, 0x02, (byte) 0x84, 0x41, 0x43, (byte) 0xDE,
            0x56, 0x00, 0x2B, 0x3B, 0x34, 0x78, 0x0A, 0x5D};

    private final byte[] name;
    private final byte[] randomBle = new byte[16];
    private final byte[] randomApp = new byte[16];
    private final byte[] key = new byte[16];
    private long msgIt = 0;

    NbCrypto(String bleName) {
        name = bleName.getBytes(StandardCharsets.US_ASCII);
        sha1Key(name, FW_DATA);
    }

    /** Cifra un pacchetto in chiaro "5A A5 LEN SRC DST CMD ARG DATI" (senza checksum). */
    synchronized byte[] encrypt(byte[] data) {
        int payloadLen = data.length - 3;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(data, 3, payload, 0, payloadLen);
        byte[] out = new byte[payloadLen + 9];
        System.arraycopy(data, 0, out, 0, 3);

        if (msgIt == 0) {
            byte[] crc = crcFirst(payload);
            payload = cryptoFirst(payload);
            System.arraycopy(payload, 0, out, 3, payloadLen);
            out[payloadLen + 3] = 0;
            out[payloadLen + 4] = 0;
            out[payloadLen + 5] = crc[0];
            out[payloadLen + 6] = crc[1];
            out[payloadLen + 7] = 0;
            out[payloadLen + 8] = 0;
            msgIt++;
        } else {
            msgIt++;
            byte[] crc = crcNext(data, msgIt);
            payload = cryptoNext(payload, msgIt);
            System.arraycopy(payload, 0, out, 3, payloadLen);
            out[payloadLen + 3] = crc[0];
            out[payloadLen + 4] = crc[1];
            out[payloadLen + 5] = crc[2];
            out[payloadLen + 6] = crc[3];
            out[payloadLen + 7] = (byte) ((msgIt >> 8) & 0xFF);
            out[payloadLen + 8] = (byte) (msgIt & 0xFF);
            if (data.length >= 23 && (data[0] & 0xFF) == 0x5A && (data[1] & 0xFF) == 0xA5
                    && data[2] == 0x10 && data[3] == 0x3E && data[4] == 0x21
                    && data[5] == 0x5C && data[6] == 0x00) {
                System.arraycopy(data, 7, randomApp, 0, 16);
            }
        }
        return out;
    }

    /** Decifra un pacchetto ricevuto. Restituisce null se è un messaggio vecchio/ripetuto. */
    synchronized byte[] decrypt(byte[] data) {
        if (data.length < 9) return null;
        byte[] dec = new byte[data.length - 6];
        System.arraycopy(data, 0, dec, 0, 3);

        long newIt = msgIt;
        if ((newIt & 0x8000L) > 0 && ((data[data.length - 2] & 0xFF) >> 7) == 0) newIt += 0x10000L;
        newIt = (newIt & 0xFFFF0000L) + ((data[data.length - 2] & 0xFFL) << 8) + (data[data.length - 1] & 0xFFL);

        int payloadLen = data.length - 9;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(data, 3, payload, 0, payloadLen);

        if (newIt == 0) {
            payload = cryptoFirst(payload);
            System.arraycopy(payload, 0, dec, 3, payloadLen);
            if ((dec[0] & 0xFF) == 0x5A && (dec[1] & 0xFF) == 0xA5 && dec[2] == 0x1E
                    && dec[3] == 0x21 && dec[4] == 0x3E && dec[5] == 0x5B && dec.length >= 23) {
                System.arraycopy(dec, 7, randomBle, 0, 16);
                sha1Key(name, randomBle);
            }
            return dec;
        } else if (newIt > msgIt) {
            payload = cryptoNext(payload, newIt);
            System.arraycopy(payload, 0, dec, 3, payloadLen);
            if ((dec[0] & 0xFF) == 0x5A && (dec[1] & 0xFF) == 0xA5 && dec[2] == 0x00
                    && dec[3] == 0x21 && dec[4] == 0x3E && dec[5] == 0x5C && dec[6] == 0x01) {
                sha1Key(randomApp, randomBle);
            }
            msgIt = newIt;
            return dec;
        }
        return null;
    }

    // ---------------- interni ----------------

    private byte[] cryptoFirst(byte[] data) {
        byte[] res = new byte[data.length];
        byte[] ks = aes(FW_DATA, key);
        for (int i = 0; i < data.length; i++) res[i] = (byte) (data[i] ^ ks[i % 16]);
        return res;
    }

    private byte[] cryptoNext(byte[] data, long it) {
        byte[] res = new byte[data.length];
        byte[] block = new byte[16];
        block[0] = 1;
        putIt(block, it);
        System.arraycopy(randomBle, 0, block, 5, 8);
        int idx = 0;
        while (idx < data.length) {
            block[15]++;
            byte[] ks = aes(block, key);
            int n = Math.min(16, data.length - idx);
            for (int i = 0; i < n; i++) res[idx + i] = (byte) (data[idx + i] ^ ks[i]);
            idx += n;
        }
        return res;
    }

    private static byte[] crcFirst(byte[] data) {
        int crc = 0;
        for (byte b : data) crc += b & 0xFF;
        crc = ~crc & 0xFFFF;
        return new byte[]{(byte) (crc & 0xFF), (byte) ((crc >> 8) & 0xFF)};
    }

    private byte[] crcNext(byte[] data, long it) {
        byte[] block = new byte[16];
        int payloadLen = data.length - 3;
        block[0] = 89;
        putIt(block, it);
        System.arraycopy(randomBle, 0, block, 5, 8);
        block[15] = (byte) payloadLen;

        byte[] x = aes(block, key);
        byte[] in = new byte[16];
        System.arraycopy(data, 0, in, 0, 3);
        x = aes(xor(in, x), key);

        int idx = 3;
        while (payloadLen > 0) {
            int n = Math.min(16, payloadLen);
            in = new byte[16];
            System.arraycopy(data, idx, in, 0, n);
            x = aes(xor(in, x), key);
            payloadLen -= n;
            idx += n;
        }
        block[0] = 1;
        block[15] = 0;
        byte[] ks = aes(block, key);
        return new byte[]{(byte) (ks[0] ^ x[0]), (byte) (ks[1] ^ x[1]), (byte) (ks[2] ^ x[2]), (byte) (ks[3] ^ x[3])};
    }

    private static void putIt(byte[] b, long it) {
        b[1] = (byte) ((it >> 24) & 0xFF);
        b[2] = (byte) ((it >> 16) & 0xFF);
        b[3] = (byte) ((it >> 8) & 0xFF);
        b[4] = (byte) (it & 0xFF);
    }

    private void sha1Key(byte[] a, byte[] b) {
        byte[] d = new byte[32];
        System.arraycopy(a, 0, d, 0, Math.min(16, a.length));
        System.arraycopy(b, 0, d, 16, Math.min(16, b.length));
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(d);
            System.arraycopy(h, 0, key, 0, 16);
        } catch (Exception ignored) {
        }
    }

    private static byte[] aes(byte[] data, byte[] k) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"));
            return c.doFinal(data);
        } catch (Exception e) {
            return new byte[16];
        }
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] r = new byte[16];
        for (int i = 0; i < 16; i++) r[i] = (byte) (a[i] ^ b[i]);
        return r;
    }
}
