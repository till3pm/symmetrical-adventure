package com.mcphone.server;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

public class ConsoleActivity extends Activity implements ServerService.Listener {
    private Prefs p;
    private TextView status, sub, tPlayers, tUptime, tAddr, term, action;
    private ScrollView scroll;
    private EditText cmd;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { uptime(); h.postDelayed(this, 1000); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Dirs.init(this);
        p = new Prefs(this);
        if (!p.b("setup", false)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        build();
    }

    private void build() {
        int pad = Ui.dp(this, 16);
        LinearLayout outer = new LinearLayout(this);
        outer.setFitsSystemWindows(true);
        outer.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        outer.addView(root, new LinearLayout.LayoutParams(-1, -1));
        setContentView(outer);

        // Kopf
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "Minecraft Server", 22, Ui.TEXT);
        title.setTypeface(Ui.font(this), android.graphics.Typeface.BOLD);
        sub = Ui.text(this, "", 12, Ui.DIM);
        titles.addView(title);
        titles.addView(sub);
        status = Ui.text(this, "● offline", 12, Ui.DIM);
        status.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        status.setBackground(Ui.box(this, Ui.CARD, Ui.STROKE, 20));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(status);
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // Kacheln
        LinearLayout stats = new LinearLayout(this);
        tPlayers = Ui.text(this, "–", 18, Ui.TEXT);
        tUptime = Ui.text(this, "00:00:00", 18, Ui.TEXT);
        tAddr = Ui.text(this, "", 13, Ui.TEXT);
        addTile(stats, "Spieler", tPlayers, 1f);
        addTile(stats, "Laufzeit", tUptime, 1.1f);
        addTile(stats, "Adresse", tAddr, 1.5f);
        root.addView(stats, Ui.row(this, 16));

        // Konsole
        LinearLayout box = Ui.card(this);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 6));
        String bar = "● ● ●   user@phone:~/minecraft";
        SpannableString ss = new SpannableString(bar);
        ss.setSpan(new ForegroundColorSpan(Ui.ERR), 0, 1, 0);
        ss.setSpan(new ForegroundColorSpan(Ui.WARN), 2, 3, 0);
        ss.setSpan(new ForegroundColorSpan(Ui.OK), 4, 5, 0);
        ss.setSpan(new ForegroundColorSpan(Ui.DIM), 5, bar.length(), 0);
        TextView bartv = Ui.text(this, "", 12, Ui.DIM);
        bartv.setText(ss);
        box.addView(bartv);

        scroll = new ScrollView(this);
        term = Ui.text(this, "", 12.5f, Ui.TEXT);
        term.setLineSpacing(0, 1.15f);
        term.setTextIsSelectable(true);
        scroll.addView(term);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout in = new LinearLayout(this);
        in.setGravity(Gravity.CENTER_VERTICAL);
        in.addView(Ui.text(this, "❯", 16, Ui.ACCENT));
        cmd = new EditText(this);
        cmd.setBackground(null);
        cmd.setTextColor(Ui.TEXT);
        cmd.setHint("Befehl, z. B. list");
        cmd.setHintTextColor(0xFF9A968D);
        cmd.setTypeface(Ui.font(this));
        cmd.setTextSize(14);
        cmd.setSingleLine(true);
        cmd.setImeOptions(EditorInfo.IME_ACTION_SEND);
        cmd.setOnEditorActionListener((v, id, e) -> { sendCmd(); return true; });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
        cl.leftMargin = Ui.dp(this, 8);
        in.addView(cmd, cl);
        box.addView(in, new LinearLayout.LayoutParams(-1, -2));
        root.addView(box, new LinearLayout.LayoutParams(-1, 0, 1));
        ((LinearLayout.LayoutParams) box.getLayoutParams()).topMargin = Ui.dp(this, 12);

        // Aktionen
        LinearLayout acts = new LinearLayout(this);
        action = Ui.button(this, "Server starten", true);
        action.setOnClickListener(v -> toggle());
        TextView setup = Ui.button(this, "Setup", false);
        setup.setOnClickListener(v -> {
            if (ServerService.running) ServerService.log("» Bitte zuerst den Server stoppen.");
            else startActivity(new Intent(this, SetupActivity.class));
        });
        acts.addView(action, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.leftMargin = Ui.dp(this, 10);
        acts.addView(setup, sl);
        root.addView(acts, Ui.row(this, 12));

        TextView tip = Ui.text(this, "Tipp: Akku-Optimierung für diese App ausschalten →", 12, Ui.DIM);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, Ui.dp(this, 10), 0, 0);
        tip.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
        });
        root.addView(tip, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addTile(LinearLayout row, String label, TextView value, float weight) {
        LinearLayout t = Ui.card(this);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        TextView l = Ui.label(this, label);
        l.setTextSize(10);
        t.addView(l);
        t.addView(value, Ui.row(this, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weight);
        if (row.getChildCount() > 0) lp.leftMargin = Ui.dp(this, 8);
        row.addView(t, lp);
    }

    private void toggle() {
        if (!ServerService.running) {
            startForegroundService(new Intent(this, ServerService.class));
        } else {
            startService(new Intent(this, ServerService.class).setAction(ServerService.STOP));
        }
    }

    private void sendCmd() {
        String c = cmd.getText().toString().trim();
        if (c.isEmpty()) return;
        cmd.setText("");
        if (!ServerService.running) {
            ServerService.log("» Server läuft nicht – erst starten.");
            return;
        }
        ServerService.log("$ " + c);
        ServerService.send(c);
    }

    private void append(String s) {
        SpannableStringBuilder sb = new SpannableStringBuilder(s + "\n");
        int color = Ui.TEXT;
        if (s.startsWith("$") || s.startsWith("»")) color = Ui.ACCENT;
        else if (s.startsWith("✗") || s.contains("ERROR") || s.contains("Exception")) color = Ui.ERR;
        else if (s.contains("WARN")) color = Ui.WARN;
        else if (s.contains("Done (")) color = Ui.OK;
        sb.setSpan(new ForegroundColorSpan(color), 0, sb.length(), 0);
        int end = s.indexOf("]: ");
        if (s.startsWith("[") && end > 0) sb.setSpan(new ForegroundColorSpan(Ui.DIM), 0, end + 2, 0);
        term.append(sb);
        CharSequence cs = term.getText();
        if (cs.length() > 40000 && cs instanceof Editable) ((Editable) cs).delete(0, 10000);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void refresh() {
        boolean run = ServerService.running, rdy = ServerService.ready;
        status.setText(run ? (rdy ? "● online" : "● startet") : "● offline");
        status.setTextColor(run ? (rdy ? Ui.OK : Ui.WARN) : Ui.DIM);
        action.setText(run ? "Server stoppen" : "Server starten");
        action.setBackground(Ui.box(this, run ? 0 : Ui.ACCENT, run ? Ui.ERR : 0, 14));
        action.setTextColor(run ? Ui.ERR : Ui.ONACC);
        tPlayers.setText(run && rdy ? ServerService.players + " / " + p.i("maxPlayers", 10) : "–");
        tAddr.setText(localIp() + ":" + p.i("port", 25565));
        sub.setText(p.s("software", "paper") + " " + p.s("version", "") + " · " + p.i("ram", 1536) + " MB");
        uptime();
    }

    private void uptime() {
        if (!ServerService.running) { tUptime.setText("00:00:00"); return; }
        long s = (System.currentTimeMillis() - ServerService.startedAt) / 1000;
        tUptime.setText(String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60));
    }

    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return "–";
    }

    @Override public void line(String s) { append(s); }
    @Override public void state() { refresh(); }

    @Override protected void onResume() {
        super.onResume();
        if (term == null) return;
        term.setText("");
        for (String s : ServerService.snapshot()) append(s);
        if (ServerService.snapshot().isEmpty()) append("» Bereit. Drücke „Server starten“.");
        ServerService.listeners.add(this);
        refresh();
        h.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        ServerService.listeners.remove(this);
        h.removeCallbacks(tick);
    }
}
