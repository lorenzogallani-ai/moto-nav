package it.lorenzo.motonav;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Elenco semplice di tutte le app installate, a caratteri grandi. */
public class AppsActivity extends Activity {

    private static class Entry {
        String label;
        Intent launch;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);

        final List<Entry> entries = new ArrayList<>();
        for (ResolveInfo ri : infos) {
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;
            Entry e = new Entry();
            e.label = String.valueOf(ri.loadLabel(pm));
            e.launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            entries.add(e);
        }
        Collections.sort(entries, (a, c) -> a.label.compareToIgnoreCase(c.label));

        List<String> labels = new ArrayList<>();
        for (Entry e : entries) labels.add(e.label);

        ListView lv = new ListView(this);
        lv.setBackgroundColor(Color.BLACK);
        lv.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, convert, parent);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
                t.setTextColor(Color.WHITE);
                t.setPadding(24, 22, 24, 22);
                return t;
            }
        });
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            try {
                startActivity(entries.get(pos).launch);
            } catch (Exception ignored) {
            }
        });
        setContentView(lv);
    }
}
