package it.lorenzo.motonav;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Schermata ♪: telecomando per la musica dell'iPhone. */
public class RemoteActivity extends Activity {

    private static final int ORANGE = 0xFFFF8C00;
    private TextView statusView, diagView;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::showStatus;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(6), dp(4), dp(6), dp(6));

        statusView = new TextView(this);
        statusView.setTextColor(0xFFDDDDDD);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(4), dp(4), dp(4), dp(6));
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
        diagView = new TextView(this);
        diagView.setTextColor(0xFF9A9A9A);
        diagView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        diagView.setMaxLines(11);
        diagView.setGravity(Gravity.START);
        diagView.setGravity(Gravity.CENTER);
        diagView.setPadding(dp(4), 0, dp(4), dp(4));
        root.addView(diagView, new LinearLayout.LayoutParams(-1, -2));

        // play / pausa: il tasto più grande, comodo coi guanti
        TextView play = button("▶ ❚❚", 0xFF2E7D32, 44);
        play.setOnClickListener(v -> RemoteService.key(this, RemoteService.PLAY_PAUSE));
        root.addView(play, cell(-1, 0, 1.3f));

        LinearLayout vol = row();
        TextView down = button("VOL −", 0xFF1E5AA8, 28);
        TextView up = button("VOL +", 0xFF1E5AA8, 28);
        repeat(down, RemoteService.VOL_DOWN);
        repeat(up, RemoteService.VOL_UP);
        vol.addView(down, cell(0, -1, 1f));
        vol.addView(up, cell(0, -1, 1f));
        root.addView(vol, cell(-1, 0, 1f));

        LinearLayout tracks = row();
        TextView prev = button("⏮", 0xFF37474F, 28);
        TextView next = button("⏭", 0xFF37474F, 28);
        prev.setOnClickListener(v -> RemoteService.key(this, RemoteService.PREV));
        next.setOnClickListener(v -> RemoteService.key(this, RemoteService.NEXT));
        TextView siri = button("SIRI", 0xFF6A1B9A, 24);
        siri.setOnClickListener(v -> RemoteService.siri(this));
        tracks.addView(prev, cell(0, -1, 0.8f));
        tracks.addView(siri, cell(0, -1, 1.2f));
        tracks.addView(next, cell(0, -1, 0.8f));
        root.addView(tracks, cell(-1, 0, 0.7f));

        LinearLayout bottom = row();
        TextView reset = button("RIFAI ABBINAMENTO", 0xFF5D4037, 15);
        reset.setOnClickListener(v -> {
            RemoteService.forgetIphones(this);
            // rende l'orologio "visibile" per 2 minuti: così Android mostra la conferma
            // di abbinamento come finestra in primo piano e non nascosta tra le notifiche
            try {
                Intent vis = new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                vis.putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
                startActivity(vis);
            } catch (Exception ignored) {
            }
            android.widget.Toast.makeText(this,
                    "1) Consenti \"visibile\"  2) Sull'iPhone: Dissocia MAX e poi tocca MAX  3) Conferma SUBITO su tutti e due",
                    android.widget.Toast.LENGTH_LONG).show();
        });
        TextView off = button("SPEGNI", 0xFF333333, 18);
        off.setOnClickListener(v -> {
            startService(new Intent(this, RemoteService.class).setAction(RemoteService.ACTION_STOP));
            h.postDelayed(this::showStatus, 300);
        });
        TextView home = button("◀ HOME", 0xFF333333, 18);
        home.setOnClickListener(v -> finish());
        bottom.addView(reset, cell(0, -1, 1.3f));
        bottom.addView(off, cell(0, -1, 0.8f));
        bottom.addView(home, cell(0, -1, 1f));
        root.addView(bottom, cell(-1, dp(56), 0f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        RemoteService.listener = refresh;
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad != null && !ad.isEnabled()) {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 1);
            } catch (Exception ignored) {
            }
        } else {
            startService(new Intent(this, RemoteService.class));
        }
        showStatus();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1 && res == RESULT_OK) startService(new Intent(this, RemoteService.class));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (RemoteService.listener == refresh) RemoteService.listener = null;
    }

    private void showStatus() {
        statusView.setText(RemoteService.status);
        statusView.setTextColor(RemoteService.connected ? 0xFF81C784 : 0xFFDDDDDD);
        String np = RemoteService.nowPlaying();
        String log = RemoteService.eventLog();
        diagView.setText((np.isEmpty() ? "" : "♪ " + np + "\n") + RemoteService.diag()
                + (log.isEmpty() ? "" : "\n" + log));
    }

    /** Tenendo premuto il volume, continua a salire/scendere. */
    private void repeat(View v, final int key) {
        final Runnable[] loop = new Runnable[1];
        loop[0] = () -> {
            RemoteService.key(this, key);
            h.postDelayed(loop[0], 180);
        };
        v.setOnTouchListener((view, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    RemoteService.key(this, key);
                    h.postDelayed(loop[0], 450);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    h.removeCallbacks(loop[0]);
                    return true;
                default:
                    return true;
            }
        });
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private TextView button(String text, int color, int sp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(ORANGE));
        s.addState(new int[]{}, round(color));
        t.setBackground(s);
        t.setClickable(true);
        return t;
    }

    private GradientDrawable round(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        return g;
    }

    private LinearLayout.LayoutParams cell(int w, int hgt, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, hgt, weight);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
