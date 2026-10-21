package com.mcphone.server;

import android.content.Context;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.GZIPInputStream;

final class Net {
    interface Log { void line(String s); }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "MCPhoneServer/1.0 (Android)");
        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code + " bei " + url);
        return c;
    }

    static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try { return readAll(c.getInputStream()); } finally { c.disconnect(); }
    }

    static void download(String url, File dest, Log log, String label) throws IOException {
        File tmp = new File(dest.getPath() + ".part");
        tmp.delete();
        long total = -1;
        int lastPct = -1;
        IOException err = null;
        for (int attempt = 1; attempt <= 6; attempt++) {
            HttpURLConnection c = null;
            try {
                long have = tmp.length();
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "MCPhoneServer/1.0 (Android)");
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                int code = c.getResponseCode();
                if (code == 416) { tmp.delete(); throw new IOException("Neustart des Downloads"); }
                if (code >= 400) throw new IOException("HTTP " + code + " bei " + url);
                boolean resumed = code == 206;
                if (!resumed && have > 0) { tmp.delete(); have = 0; }
                long len = c.getContentLengthLong();
                if (len > 0) total = have + len;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp, resumed)) {
                    byte[] buf = new byte[1 << 16];
                    long done = have;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total) / 10 * 10;
                            if (pct != lastPct) { lastPct = pct; log.line(label + " " + pct + " %"); }
                        }
                    }
                }
                if (total > 0 && tmp.length() < total) throw new IOException("Download unvollständig");
                err = null;
                break;
            } catch (IOException e) {
                err = e;
                if (attempt < 6) {
                    log.line("… Verbindung unterbrochen, Versuch " + (attempt + 1) + "/6");
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                }
            } finally {
                if (c != null) c.disconnect();
            }
        }
        if (err != null) throw err;
        try {
            Files.move(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            try (InputStream in = new java.io.FileInputStream(tmp); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (IOException e2) {
                throw new IOException("Speichern fehlgeschlagen: " + e2 + " (Ziel: " + dest + ")");
            }
            tmp.delete();
        }
    }

    // ---------- Versionen ----------

    static List<String> versions(String software) throws Exception {
        List<String> r = new ArrayList<>();
        if (software.equals("paper")) {
            JSONObject v = new JSONObject(get("https://fill.papermc.io/v3/projects/paper")).getJSONObject("versions");
            Iterator<String> it = v.keys();
            while (it.hasNext()) {
                JSONArray a = v.getJSONArray(it.next());
                for (int i = 0; i < a.length(); i++) {
                    String id = a.getString(i);
                    if (!id.contains("-")) r.add(id);
                }
            }
        } else {
            JSONArray a = new JSONObject(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")).getJSONArray("versions");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!"release".equals(o.getString("type"))) continue;
                r.add(o.getString("id"));
                if (o.getString("id").equals("1.8.9")) break;
            }
        }
        return r;
    }

    static String serverUrl(String software, String version) throws Exception {
        if (software.equals("paper")) {
            JSONArray b = new JSONArray(get("https://fill.papermc.io/v3/projects/paper/versions/" + version + "/builds"));
            JSONObject pick = null;
            for (int i = 0; i < b.length(); i++) {
                JSONObject o = b.getJSONObject(i);
                if (pick == null) pick = o;
                if ("STABLE".equals(o.optString("channel"))) { pick = o; break; }
            }
            if (pick == null) throw new IOException("Kein Paper-Build fuer " + version);
            return pick.getJSONObject("downloads").getJSONObject("server:default").getString("url");
        }
        JSONArray a = new JSONObject(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")).getJSONArray("versions");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            if (o.getString("id").equals(version)) {
                JSONObject meta = new JSONObject(get(o.getString("url")));
                return meta.getJSONObject("downloads").getJSONObject("server").getString("url");
            }
        }
        throw new IOException("Version nicht gefunden: " + version);
    }

    static String javaUrl() throws Exception {
        JSONArray a = new JSONObject(get("https://api.github.com/repos/metinjakupi/openjdk-android-arm64/releases/latest")).getJSONArray("assets");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String nm = o.getString("name");
            if (nm.startsWith("jre21-arm64") && nm.endsWith(".tar.xz")) return o.getString("browser_download_url");
        }
        throw new IOException("Keine Java-Laufzeit gefunden");
    }

    static int javaFor(String v) {
        try {
            String[] a = v.split("[.-]");
            if (!a[0].equals("1")) return 21;
            int minor = Integer.parseInt(a[1]);
            int patch = a.length > 2 ? Integer.parseInt(a[2]) : 0;
            if (minor > 20 || (minor == 20 && patch >= 5)) return 21;
            if (minor >= 17) return 17;
            return 8;
        } catch (Exception e) { return 21; }
    }

    // ---------- Java-Laufzeit ----------

    static void extractRuntime(Context c, InputStream in, Log log) throws IOException {
        BufferedInputStream b = new BufferedInputStream(in, 1 << 16);
        b.mark(16);
        byte[] m = new byte[6];
        int n = b.read(m);
        b.reset();
        InputStream dec;
        if (n >= 2 && (m[0] & 0xFF) == 0x1F && (m[1] & 0xFF) == 0x8B) dec = new GZIPInputStream(b);
        else if (n >= 6 && (m[0] & 0xFF) == 0xFD && m[1] == 0x37 && m[2] == 0x7A) dec = new XZCompressorInputStream(b);
        else throw new IOException("Unbekanntes Format (erwartet .tar.xz oder .tar.gz)");

        File rt = Dirs.runtime(c);
        Dirs.delete(rt);
        rt.mkdirs();
        String base = rt.getCanonicalPath() + File.separator;
        int files = 0;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(dec)) {
            TarArchiveEntry e;
            while ((e = tar.getNextTarEntry()) != null) {
                File out = new File(rt, e.getName());
                if (!out.getCanonicalPath().startsWith(base)) continue;
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                if (e.isSymbolicLink()) {
                    try { Files.createSymbolicLink(out.toPath(), Paths.get(e.getLinkName())); } catch (Exception ignored) { }
                    continue;
                }
                if (!e.isFile()) continue;
                try (OutputStream o = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = tar.read(buf)) > 0) o.write(buf, 0, r);
                }
                out.setExecutable(true, false);
                if (++files % 400 == 0) log.line("… " + files + " Dateien entpackt");
            }
        }
        log.line("✓ " + files + " Dateien entpackt");
    }

    static String javaVersion(String home) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(home + "/bin/java", "-version").redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", Dirs.libPath(home));
        pb.environment().put("JAVA_HOME", home);
        Dirs.applyNoTag(pb.environment());
        Process p = pb.start();
        String out = readAll(p.getInputStream());
        int code = p.waitFor();
        if (code != 0) throw new IOException("java -version fehlgeschlagen:\n" + out.trim());
        return out.split("\n")[0].trim();
    }
}
