package it.lorenzo.motonav;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Schermata del banco prove: stato del collegamento con Claude e codice da inserire su GitHub. */
public class BridgeActivity extends Activity {

    private TextView info, code;
    private TextView toggle;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            show();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(10), dp(6), dp(10), dp(8));

        TextView title = new TextView(this);
        title.setText("Banco prove · Claude");
        title.setTextColor(0xFFFF8C00);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        info = new TextView(this);
        info.setTextColor(0xFFDDDDDD);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(6), 0, dp(4));
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));

        code = new TextView(this);
        code.setTextColor(Color.WHITE);
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        code.setGravity(Gravity.CENTER);
        root.addView(code, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        toggle = button("", 0xFF2E7D32);
        toggle.setOnClickListener(v -> {
            if (Bridge.on(this)) startService(new Intent(this, Bridge.class).setAction(Bridge.ACTION_STOP));
            else startService(new Intent(this, Bridge.class));
            h.postDelayed(this::show, 300);
        });
        TextView home = button("◀ HOME", 0xFF444444);
        home.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        row.addView(toggle, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(64), 1f);
        lp2.setMargins(dp(4), 0, dp(4), 0);
        row.addView(home, lp2);
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    private void show() {
        boolean on = Bridge.on(this);
        toggle.setText(on ? "SPEGNI" : "ATTIVA");
        ((GradientDrawable) toggle.getBackground()).setColor(on ? 0xFF8B1E1E : 0xFF2E7D32);
        String c = Bridge.userCode;
        if (!on) {
            info.setText("Spento. Collega l'orologio al Wi-Fi (o all'hotspot dell'iPhone) e tocca ATTIVA.");
            code.setText("");
        } else if (c != null) {
            info.setText("Sul telefono apri github.com/login/device e scrivi questo codice:");
            code.setText(c);
        } else {
            info.setText(Bridge.state);
            code.setText(Bridge.linked ? "✓" : "…");
        }
    }

    private TextView button(String t, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        v.setBackground(g);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
