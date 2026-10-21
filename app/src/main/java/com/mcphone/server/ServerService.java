package com.mcphone.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class ServerService extends Service {
    interface Listener { void line(String s); void state(); }

    static final String STOP = "com.mcphone.server.STOP";
    static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final ArrayList<String> history = new ArrayList<>();
    static volatile boolean running, ready;
    static volatile int players;
    static volatile long startedAt;
    private static volatile Process proc;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wl;

    static void log(String s) {
        synchronized (history) {
            history.add(s);
            if (history.size() > 800) history.remove(0);
        }
        ui.post(() -> { for (Listener l : listeners) l.line(s); });
    }

    static List<String> snapshot() {
        synchronized (history) { return new ArrayList<>(history); }
    }

    static void state() {
        ui.post(() -> { for (Listener l : listeners) l.state(); });
    }

    static void send(String cmd) {
        final Process p = proc;
        if (p == null) return;
        new Thread(() -> {
            try {
                OutputStream o = p.getOutputStream();
                o.write((cmd + "\n").getBytes("UTF-8"));
                o.flush();
            } catch (IOException ignored) { }
        }).start();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        Dirs.init(this);
        if (i != null && STOP.equals(i.getAction())) {
            if (running) stopServer(); else stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        startForeground(1, notification("Server startet …"));
        running = true;
        ready = false;
        players = 0;
        startedAt = System.currentTimeMillis();
        state();
        new Thread(this::runServer, "mc-server").start();
        return START_NOT_STICKY;
    }

    private void stopServer() {
        log("» stoppe Server …");
        send("stop");
        new Thread(() -> {
            try {
                Process p = proc;
                if (p != null && !p.waitFor(40, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException ignored) { }
        }).start();
    }

    private void runServer() {
        try {
            Prefs p = new Prefs(this);
            File dir = Dirs.server(this);
            dir.mkdirs();
            if (!Linux.ready(this) || !new File(dir, "server.jar").exists()) {
                log("✗ Linux-Umgebung oder server.jar fehlt – bitte Setup erneut ausführen.");
                return;
            }
            writeConfig(p, dir);
            int ram = p.i("ram", 1536);
            List<String> javaArgs = java.util.Arrays.asList(
                    "-Xmx" + ram + "M", "-Xms" + Math.min(ram, 512) + "M", "-XX:+UseSerialGC",
                    "-Dfile.encoding=UTF-8", "-jar", "server.jar", "nogui");
            String androidJava = Linux.androidJavaHome(this);
            ProcessBuilder pb;
            if (androidJava != null && new File(androidJava, "bin/java").canExecute()) {
                // Die Android-gebaute JVM direkt starten; proot/Alpine-OpenJDK
                // kann an Androids W^X-/execmem-Schutz scheitern.
                List<String> cmd = new ArrayList<>();
                cmd.add(androidJava + "/bin/java");
                cmd.addAll(javaArgs);
                pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
                Map<String, String> env = pb.environment();
                env.put("JAVA_HOME", androidJava);
                env.put("LD_LIBRARY_PATH", Dirs.libPath(androidJava));
                Dirs.applyNoTag(env);
                env.put("HOME", getFilesDir().getAbsolutePath());
                env.put("LANG", "C.UTF-8");
            } else {
                List<String> inner = new ArrayList<>();
                inner.add("java");
                inner.addAll(javaArgs);
                pb = Linux.builder(this, p, inner, "/server", true);
            }

            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcphone:server");
            wl.acquire();

            log(Dirs.noTag == null ? "⚠ libnotag.so fehlt – Heap-Tagging bleibt aktiv" : "✓ libnotag.so: " + Dirs.noTag);
            log("$ java -Xmx" + ram + "M -jar server.jar nogui");
            proc = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    parse(line);
                    log(line);
                }
            }
            log("» Server beendet (Exit " + proc.waitFor() + ")");
        } catch (Exception e) {
            log("✗ " + e.getMessage());
        } finally {
            running = false;
            ready = false;
            proc = null;
            if (wl != null && wl.isHeld()) wl.release();
            state();
            stopForeground(true);
            stopSelf();
        }
    }

    private void parse(String line) {
        if (line.contains("Done (") && line.contains("For help")) {
            ready = true;
        } else if (line.contains(" joined the game")) {
            players++;
        } else if (line.contains(" left the game")) {
            players = Math.max(0, players - 1);
        } else {
            return;
        }
        state();
        getSystemService(NotificationManager.class)
                .notify(1, notification(ready ? "Online · " + players + " Spieler" : "Server startet …"));
    }

    private void writeConfig(Prefs p, File dir) throws IOException {
        try (FileOutputStream o = new FileOutputStream(new File(dir, "eula.txt"))) {
            o.write("eula=true\n".getBytes("UTF-8"));
        }
        File f = new File(dir, "server.properties");
        Properties pr = new Properties();
        if (f.exists()) try (FileInputStream in = new FileInputStream(f)) { pr.load(in); }
        pr.setProperty("server-port", String.valueOf(p.i("port", 25565)));
        pr.setProperty("motd", p.s("motd", "Ein Minecraft-Server auf dem Handy"));
        pr.setProperty("max-players", String.valueOf(p.i("maxPlayers", 10)));
        pr.setProperty("difficulty", p.s("difficulty", "normal"));
        pr.setProperty("gamemode", p.s("gamemode", "survival"));
        pr.setProperty("pvp", p.s("pvp", "true"));
        pr.setProperty("online-mode", p.s("onlineMode", "true"));
        pr.setProperty("view-distance", String.valueOf(p.i("viewDistance", 6)));
        try (FileOutputStream o = new FileOutputStream(f)) { pr.store(o, "Erzeugt von MC Server"); }
    }

    private Notification notification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel("srv") == null)
            nm.createNotificationChannel(new NotificationChannel("srv", "Server", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, ConsoleActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ServerService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "srv")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Minecraft Server")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat), "Stoppen", stop).build())
                .build();
    }

    @Override public void onDestroy() {
        Process p = proc;
        if (p != null) p.destroy();
        super.onDestroy();
    }
}
