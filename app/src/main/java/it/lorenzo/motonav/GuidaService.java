package it.lorenzo.motonav;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Attivo finché la modalità GUIDA è accesa.
 * - Toglie la schermata di blocco: il tasto superiore spegne e riaccende lo schermo
 *   e si torna subito alla mappa (funziona con blocco "Scorrimento" o "Nessuno", non con PIN).
 * - Tiene viva MotoNav in sottofondo (notifica fissa), insieme alla navigazione di OsmAnd.
 */
public class GuidaService extends Service {

    private KeyguardManager.KeyguardLock lock;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(9, buildNotification());
        Gps.keepWarm(this, true);
        if (lock == null) {
            try {
                KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                if (km != null) {
                    lock = km.newKeyguardLock("motonav-guida");
                    lock.disableKeyguard();
                }
            } catch (Exception ignored) {
            }
        }
        return START_STICKY;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onDestroy() {
        Gps.keepWarm(this, false);
        try {
            if (lock != null) lock.reenableKeyguard();
        } catch (Exception ignored) {
        }
        lock = null;
        super.onDestroy();
    }

    /** true se il blocco schermo ha un PIN/sequenza: in quel caso non possiamo saltarlo. */
    static boolean secureLock(android.content.Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(KEYGUARD_SERVICE);
        return km != null && km.isKeyguardSecure();
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("guida", "Modalità guida",
                    NotificationManager.IMPORTANCE_LOW);
            if (nm != null) nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, "guida");
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Guida attiva")
                .setContentText("Tasto superiore: schermo acceso/spento, la navigazione continua")
                .setOngoing(true)
                .setContentIntent(open)
                .build();
    }
}
