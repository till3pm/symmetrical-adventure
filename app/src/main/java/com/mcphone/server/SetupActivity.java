package com.mcphone.server;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SetupActivity extends Activity {
    private static final String[] TITLES = {"Software", "Version", "Spiel", "Leistung", "Installation"};

    private Prefs p;
    private int step;
    private LinearLayout body;
    private ScrollView bodyScroll;
    private TextView stepLbl, title, back, next, logView;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, List<String>> cache = new HashMap<>();
    private final StringBuilder logBuf = new StringBuilder();
    private String loadErr;
    private boolean installing, installed;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Dirs.init(this);
        p = new Prefs(this);
        step = Math.max(0, Math.min(4, p.i("step", 0)));
        int pad = Ui.dp(this, 20);

        LinearLayout outer = new LinearLayout(this);
        outer.setFitsSystemWindows(true);
        outer.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        outer.addView(root, new LinearLayout.LayoutParams(-1, -1));
        setContentView(outer);

        stepLbl = Ui.text(this, "", 12, Ui.ACCENT);
        title = Ui.text(this, "", 30, Ui.TEXT);
        title.setTypeface(Ui.font(this), Typeface.BOLD);
        root.addView(stepLbl);
        root.addView(title, Ui.row(this, 2));

        bodyScroll = new ScrollView(this);
        bodyScroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        bodyScroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1);
        sp.topMargin = Ui.dp(this, 16);
        root.addView(bodyScroll, sp);

        LinearLayout nav = new LinearLayout(this);
        back = Ui.button(this, "Zurück", false);
        next = Ui.button(this, "Weiter", true);
        back.setOnClickListener(v -> { if (step > 0) { step--; show(); } });
        next.setOnClickListener(v -> onNext());
        nav.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 2);
        nl.leftMargin = Ui.dp(this, 10);
        nav.addView(next, nl);
        root.addView(nav, Ui.row(this, 12));

        show();
    }

    private void onNext() {
        if (step == 1 && p.s("version", "").isEmpty()) return;
        if (step < 4) { step++; show(); return; }
        p.put("setup", true);
        p.put("step", 0);
        startActivity(new Intent(this, ConsoleActivity.class));
        finish();
    }

    private void show() {
        p.put("step", step);
        stepLbl.setText(String.format("%02d / %02d", step + 1, TITLES.length));
        title.setText(TITLES[step]);
        body.removeAllViews();
        logView = null;
        switch (step) {
            case 0: s0(); break;
            case 1: s1(); break;
            case 2: s2(); break;
            case 3: s3(); break;
            default: s4();
        }
        back.setVisibility(step == 0 ? View.INVISIBLE : View.VISIBLE);
        next.setText(step == 4 ? "Fertig" : "Weiter");
        updateNav();
    }

    private void updateNav() {
        boolean ok = step < 4 || (installed && !installing);
        next.setEnabled(ok);
        next.setAlpha(ok ? 1f : 0.35f);
    }

    // ---------- Schritt 0: Software ----------

    private void s0() {
        body.addView(Ui.text(this, "$ mc-setup --init", 13, Ui.ACCENT));
        TextView t = Ui.text(this, "Dieser Assistent richtet einen Minecraft-Server ein, der direkt auf deinem Handy läuft. "
                + "Du wählst Software, Version und die wichtigsten Einstellungen – den Rest übernimmt die App.", 16, Ui.TEXT);
        t.setLineSpacing(0, 1.2f);
        body.addView(t, Ui.row(this, 12));

        LinearLayout c = Ui.card(this);
        c.addView(Ui.label(this, "Server-Software"));
        final TextView d = Ui.text(this, "", 13, Ui.DIM);
        int sel = p.s("software", "paper").equals("vanilla") ? 0 : 1;
        d.setText(softwareDesc(sel));
        c.addView(Ui.chips(this, new String[]{"Vanilla", "Paper"}, sel, i -> {
            p.put("software", i == 0 ? "vanilla" : "paper");
            p.put("version", "");
            installed = false;
            d.setText(softwareDesc(i));
        }), Ui.row(this, 10));
        c.addView(d, Ui.row(this, 10));
        body.addView(c, Ui.row(this, 20));
    }

    private String softwareDesc(int i) {
        return i == 0 ? "Der offizielle Mojang-Server. Reines Minecraft ohne Plugins."
                : "Optimierter Server mit besserer Performance – die bessere Wahl fürs Handy. Plugins möglich.";
    }

    // ---------- Schritt 1: Version ----------

    private void s1() {
        final String sw = p.s("software", "paper");
        List<String> v = cache.get(sw);
        if (v == null) {
            if (loadErr != null) {
                body.addView(Ui.text(this, "✗ " + loadErr, 14, Ui.ERR));
                TextView retry = Ui.button(this, "Erneut versuchen", false);
                retry.setOnClickListener(x -> { loadErr = null; show(); });
                body.addView(retry, Ui.row(this, 14));
            } else {
                body.addView(Ui.text(this, "$ Versionen werden geladen …", 14, Ui.DIM));
                load(sw);
            }
            return;
        }
        if (p.s("version", "").isEmpty() && !v.isEmpty()) {
            String pick = v.get(0);
            for (String s : v) if (s.startsWith("1.21")) { pick = s; break; }
            p.put("version", pick);
        }
        final TextView hint = Ui.text(this, "", 13, Ui.DIM);
        hint.setText(javaHint(p.s("version", "")));
        body.addView(hint);

        final List<TextView> rows = new ArrayList<>();
        for (final String ver : v) {
            final TextView r = Ui.text(this, ver, 16, Ui.TEXT);
            r.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
            r.setTag(ver);
            r.setOnClickListener(x -> {
                p.put("version", ver);
                installed = false;
                styleRows(rows, ver);
                hint.setText(javaHint(ver));
            });
            rows.add(r);
            body.addView(r, Ui.row(this, 6));
        }
        styleRows(rows, p.s("version", ""));
    }

    private void styleRows(List<TextView> rows, String sel) {
        for (TextView r : rows) {
            boolean on = r.getTag().equals(sel);
            r.setText((on ? "▸ " : "   ") + r.getTag());
            r.setTextColor(on ? Ui.ONACC : Ui.TEXT);
            r.setBackground(Ui.box(this, on ? Ui.ACCENT : Ui.CARD, on ? 0 : Ui.STROKE, 12));
        }
    }

    private String javaHint(String ver) {
        return ver.isEmpty() ? "" : "Minecraft " + ver + " braucht Java " + Net.javaFor(ver) + " oder neuer.";
    }

    private void load(final String sw) {
        io.execute(() -> {
            try {
                final List<String> v = Net.versions(sw);
                ui.post(() -> { cache.put(sw, v); loadErr = null; if (step == 1) show(); });
            } catch (Exception e) {
                final String m = String.valueOf(e.getMessage());
                ui.post(() -> { loadErr = m; if (step == 1) show(); });
            }
        });
    }

    // ---------- Schritt 2: Spiel ----------

    private void s2() {
        label("Server-Name (MOTD)");
        body.addView(field("motd", "Ein Minecraft-Server auf dem Handy", "", false), Ui.row(this, 6));
        label("Max. Spieler");
        body.addView(field("maxPlayers", "10", "", true), Ui.row(this, 6));
        choice("Schwierigkeit", "difficulty", new String[]{"Friedlich", "Leicht", "Normal", "Schwer"},
                new String[]{"peaceful", "easy", "normal", "hard"}, "normal");
        choice("Spielmodus", "gamemode", new String[]{"Survival", "Kreativ", "Abenteuer"},
                new String[]{"survival", "creative", "adventure"}, "survival");
        choice("PvP", "pvp", new String[]{"An", "Aus"}, new String[]{"true", "false"}, "true");
        choice("Online-Modus (Account-Prüfung)", "onlineMode", new String[]{"An", "Aus"},
                new String[]{"true", "false"}, "true");
        TextView n = Ui.text(this, "Aus = auch nicht-offizielle Clients können beitreten, aber jeder kann sich mit fremdem Namen anmelden.", 12, Ui.DIM);
        body.addView(n, Ui.row(this, 6));
    }

    private void label(String s) {
        body.addView(Ui.label(this, s), Ui.row(this, 18));
    }

    private void choice(String lbl, final String key, String[] labels, final String[] values, String def) {
        label(lbl);
        String cur = p.s(key, def);
        int sel = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) sel = i;
        p.put(key, values[sel]);
        body.addView(Ui.chips(this, labels, sel, i -> p.put(key, values[i])), Ui.row(this, 8));
    }

    private EditText field(final String key, String def, String hint, boolean number) {
        EditText e = Ui.input(this, p.s(key, def), hint, number);
        if (p.s(key, "").isEmpty() && !def.isEmpty()) p.put(key, def);
        e.addTextChangedListener(new Ui.Watch() {
            @Override public void afterTextChanged(Editable s) {
                String v = s.toString().trim();
                if (!number || !v.isEmpty()) p.put(key, v);
            }
        });
        return e;
    }

    // ---------- Schritt 3: Leistung ----------

    private void s3() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        int totalMb = (int) (mi.totalMem / 1048576L);
        final int min = 512;
        final int max = Math.max(min, (totalMb * 6 / 10) / 256 * 256);
        int cur = Math.max(min, Math.min(max, p.i("ram", 1536)));
        cur = min + (cur - min) / 256 * 256;
        p.put("ram", cur);

        label("Arbeitsspeicher für den Server");
        final TextView val = Ui.text(this, cur + " MB", 22, Ui.TEXT);
        body.addView(val, Ui.row(this, 6));
        SeekBar sb = new SeekBar(this);
        sb.setMax((max - min) / 256);
        sb.setProgress((cur - min) / 256);
        ColorStateList accent = ColorStateList.valueOf(Ui.ACCENT);
        sb.setProgressTintList(accent);
        sb.setThumbTintList(accent);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int prog, boolean user) {
                int mb = min + prog * 256;
                p.put("ram", mb);
                val.setText(mb + " MB");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        body.addView(sb, Ui.row(this, 6));
        body.addView(Ui.text(this, "Dein Handy hat ca. " + totalMb + " MB RAM. Es werden höchstens 60 % angeboten, damit Android genug übrig bleibt.", 12, Ui.DIM), Ui.row(this, 4));

        label("Port");
        body.addView(field("port", "25565", "", true), Ui.row(this, 6));
        choice("Sichtweite (Chunks)", "viewDistance", new String[]{"4", "6", "8", "10"},
                new String[]{"4", "6", "8", "10"}, "6");
        body.addView(Ui.text(this, "Kleinere Sichtweite = weniger Last für das Handy.", 12, Ui.DIM), Ui.row(this, 6));
    }

    // ---------- Schritt 4: Installation ----------

    private void s4() {
        TextView t = Ui.text(this, "Eine Linux-Umgebung (Alpine mit Java) und die Server-Datei werden automatisch geladen und eingerichtet. "
                + "Mit der Installation akzeptierst du die Minecraft-EULA (aka.ms/MinecraftEULA).", 14, Ui.TEXT);
        t.setLineSpacing(0, 1.2f);
        body.addView(t);
        LinearLayout card = Ui.card(this);
        logView = Ui.text(this, logBuf.length() == 0 ? "$ bereit" : logBuf.toString(), 12, Ui.TEXT);
        logView.setMinHeight(Ui.dp(this, 150));
        card.addView(logView);
        body.addView(card, Ui.row(this, 16));
        TextView go = Ui.button(this, "Erneut versuchen", false);
        go.setOnClickListener(v -> install());
        body.addView(go, Ui.row(this, 14));
        installed = (p.s("software", "paper") + " " + p.s("version", "")).equals(p.s("installedFor", ""))
                && new File(Dirs.server(this), "server.jar").exists();
        if (installed) { if (logBuf.length() == 0) log("✓ Bereits installiert. Tippe auf „Fertig“."); }
        else if (!installing) install();
    }

    private void log(final String s) {
        ui.post(() -> {
            logBuf.append(s).append('\n');
            if (logView != null) {
                logView.setText(logBuf);
                bodyScroll.post(() -> bodyScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void install() {
        if (installing) return;
        installing = true;
        installed = false;
        updateNav();
        final String sw = p.s("software", "paper");
        final String ver = p.s("version", "");
        io.execute(() -> {
            try {
                Net.Log lg = this::log;
                Linux.install(this, p, lg);
                lg.line("$ Server-Datei laden (" + sw + " " + ver + ")");
                String su = Net.serverUrl(sw, ver);
                Dirs.server(this).mkdirs();
                Net.download(su, new File(Dirs.server(this), "server.jar"), lg, "server.jar");
                p.put("installedFor", sw + " " + ver);
                lg.line("✓ Alles bereit. Tippe auf „Fertig“.");
                installed = true;
            } catch (Exception e) {
                log("✗ " + e.getMessage());
            }
            installing = false;
            ui.post(this::updateNav);
        });
    }
}
