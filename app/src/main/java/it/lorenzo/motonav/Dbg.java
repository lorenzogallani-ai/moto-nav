package it.lorenzo.motonav;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/** Diario unico dell'app: le ultime righe di tutto ciò che succede, lette dal banco prove. */
final class Dbg {
    private static final LinkedList<String> lines = new LinkedList<>();
    private static final int MAX = 400;
    private static long seq = 0;

    private Dbg() {
    }

    static synchronized void log(String tag, String msg) {
        String t = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY).format(new Date());
        lines.addLast(t + " [" + tag + "] " + msg);
        seq++;
        while (lines.size() > MAX) lines.removeFirst();
    }

    static synchronized long seq() {
        return seq;
    }

    /** Ultime n righe, dalla più vecchia alla più recente. */
    static synchronized String tail(int n) {
        StringBuilder sb = new StringBuilder();
        int skip = Math.max(0, lines.size() - n);
        int i = 0;
        for (String l : lines) {
            if (i++ < skip) continue;
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        s = s.replaceAll("[^0-9A-Fa-f]", "");
        byte[] o = new byte[s.length() / 2];
        for (int i = 0; i < o.length; i++) o[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return o;
    }
}
