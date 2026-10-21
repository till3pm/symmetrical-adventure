package com.mcphone.server;

import android.content.Context;

import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Alpine-Linux per proot (wie UserLAnd). proot stammt aus der Termux-Paketquelle. */
final class Linux {
    static File base(Context c) { return new File(c.getFilesDir(), "linux"); }
    static File tools(Context c) { return new File(base(c), "tools"); }
    static File root(Context c) { return new File(base(c), "rootfs"); }

    static String androidJavaHome(Context c) {
        return Dirs.findJavaHome(Dirs.runtime(c));
    }

    static boolean ready(Context c) {
        String home = androidJavaHome(c);
        if (home != null && new File(home, "bin/java").canExecute()) return true;
        return new File(root(c), "usr/lib/jvm").exists()
                && new File(tools(c), "usr/bin/proot").exists();
    }

    static ProcessBuilder builder(Context c, Prefs p, List<String> inner, String wd, boolean bindServer) {
        File t = tools(c);
        List<String> cmd = new ArrayList<>(Arrays.asList(t + "/usr/bin/proot", "-0", "--link2symlink", "--kill-on-exit",
                "-r", root(c).getPath(), "-b", "/dev", "-b", "/proc", "-b", "/sys"));
        if (bindServer) { cmd.add("-b"); cmd.add(Dirs.server(c).getPath() + ":/server"); }
        cmd.addAll(Arrays.asList("-w", wd, "/usr/bin/env", "-i", "HOME=/root", "TERM=xterm", "LANG=C.UTF-8",
                "PATH=/usr/lib/jvm/default-jvm/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"));
        cmd.addAll(inner);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", t + "/usr/lib");
        env.put("PROOT_LOADER", t + "/usr/libexec/proot/loader");
        env.put("PROOT_TMP_DIR", c.getCacheDir().getPath());
        Dirs.applyNoTag(env);
        if (p.b("noSeccomp", false)) env.put("PROOT_NO_SECCOMP", "1");
        return pb;
    }

    static int run(ProcessBuilder pb, Net.Log log) throws Exception {
        Process pr = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream(), "UTF-8"))) {
            String l;
            while ((l = r.readLine()) != null) log.line(l);
        }
        return pr.waitFor();
    }

    private static boolean test(Context c, Prefs p, Net.Log log) {
        for (int i = 0; i < 2; i++) {
            try {
                if (run(builder(c, p, Arrays.asList("java", "-version"), "/", false), log) == 0) return true;
            } catch (Exception e) { log.line("✗ " + e.getMessage()); }
            if (p.b("noSeccomp", false)) break;
            log.line("… neuer Versuch ohne Seccomp");
            p.put("noSeccomp", true);
        }
        return false;
    }

    static void install(Context c, Prefs p, Net.Log log) throws Exception {
        // Android-kompatible JVM zuerst verwenden: Alpine/HotSpot benötigt
        // ausführbaren anonymen Speicher (JIT), den Android auf manchen Geräten blockiert.
        String existingHome = androidJavaHome(c);
        if (existingHome != null) {
            try {
                log.line("$ Android-Java testen");
                log.line("✓ " + Net.javaVersion(existingHome));
                log.line("✓ Android-Java bereit");
                return;
            } catch (Exception e) {
                log.line("… vorhandene Android-Java-Laufzeit unbrauchbar: " + e.getMessage());
            }
        }
        try {
            log.line("$ Android-kompatible Java-21-Laufzeit laden");
            File archive = new File(c.getCacheDir(), "android-jre21.tar.xz");
            Net.download(Net.javaUrl(), archive, log, "Java 21");
            try (InputStream in = new FileInputStream(archive)) {
                Net.extractRuntime(c, in, log);
            }
            archive.delete();
            String home = androidJavaHome(c);
            if (home == null) throw new IOException("Java-Home im Android-Runtime-Archiv nicht gefunden");
            log.line("$ " + home + "/bin/java -version");
            log.line("✓ " + Net.javaVersion(home));
            log.line("✓ Android-Java bereit");
            return;
        } catch (Exception e) {
            log.line("… Android-Java fehlgeschlagen: " + e.getMessage());
            log.line("… versuche Alpine-Java als Rückfall");
        }
        if (ready(c) && test(c, p, log)) { log.line("✓ Linux-Umgebung bereit"); return; }
        if (!new File(tools(c), "usr/bin/proot").exists()) fetchTools(c, log);
        if (!new File(root(c), "bin/busybox").exists()) fetchRootfs(c, log);
        File root = root(c);
        for (String d : new String[]{"dev", "proc", "sys", "tmp", "server", "etc"}) new File(root, d).mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(root, "etc/resolv.conf"))) {
            o.write("nameserver 8.8.8.8\nnameserver 1.1.1.1\n".getBytes("UTF-8"));
        }
        if (!new File(root, "usr/lib/jvm").exists()) {
            log.line("$ apk add openjdk21-jre-headless");
            List<String> in = Arrays.asList("/bin/sh", "-c", "apk add --no-cache openjdk21-jre-headless");
            int code = run(builder(c, p, in, "/", false), log);
            if (code != 0 && !p.b("noSeccomp", false)) {
                log.line("… neuer Versuch ohne Seccomp");
                p.put("noSeccomp", true);
                code = run(builder(c, p, in, "/", false), log);
            }
            if (code != 0) throw new IOException("Java-Installation in Linux fehlgeschlagen (Exit " + code + ")");
        }
        if (!test(c, p, log)) throw new IOException("Java startet in der Linux-Umgebung nicht");
        log.line("✓ Linux + Java bereit");
    }

    private static void fetchTools(Context c, Net.Log log) throws Exception {
        log.line("$ proot laden (Termux-Paketquelle)");
        String base = "https://packages.termux.dev/apt/termux-main/";
        String idx = Net.get(base + "dists/stable/main/binary-aarch64/Packages");
        Map<String, String[]> pk = new HashMap<>();
        String name = null, file = null, dep = "";
        for (String line : (idx + "\n\n").split("\n")) {
            if (line.trim().isEmpty()) {
                if (name != null && file != null) pk.put(name, new String[]{file, dep});
                name = null; file = null; dep = "";
            } else if (line.startsWith("Package: ")) name = line.substring(9).trim();
            else if (line.startsWith("Filename: ")) file = line.substring(10).trim();
            else if (line.startsWith("Depends: ")) dep = line.substring(9);
        }
        if (!pk.containsKey("proot")) throw new IOException("proot nicht in der Paketquelle gefunden");
        Set<String> need = new LinkedHashSet<>();
        Deque<String> q = new ArrayDeque<>();
        q.add("proot");
        while (!q.isEmpty()) {
            String n = q.poll();
            String[] e = pk.get(n);
            if (e == null || !need.add(n)) continue;
            for (String d : e[1].split(",")) {
                d = d.split("\\|")[0].replaceAll("\\(.*\\)", "").trim();
                if (!d.isEmpty()) q.add(d);
            }
        }
        File t = tools(c);
        Dirs.delete(t);
        t.mkdirs();
        for (String n : need) {
            File deb = new File(c.getCacheDir(), "pkg.deb");
            Net.download(base + pk.get(n)[0], deb, log, n);
            extractDeb(deb, t);
            deb.delete();
        }
        if (!new File(t, "usr/bin/proot").exists()) throw new IOException("proot fehlt im Paket");
        log.line("✓ proot bereit");
    }

    private static void fetchRootfs(Context c, Net.Log log) throws Exception {
        log.line("$ Alpine Linux laden");
        String dir = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/";
        Matcher m = Pattern.compile("alpine-minirootfs-[0-9.]+-aarch64\\.tar\\.gz").matcher(Net.get(dir + "latest-releases.yaml"));
        if (!m.find()) throw new IOException("Alpine-Abbild nicht gefunden");
        File tmp = new File(c.getCacheDir(), "rootfs.tgz");
        Net.download(dir + m.group(), tmp, log, "Alpine");
        File root = root(c);
        Dirs.delete(root);
        root.mkdirs();
        try (InputStream in = new FileInputStream(tmp)) { untar(in, root, null); }
        tmp.delete();
        log.line("✓ Alpine entpackt");
    }

    private static void extractDeb(File deb, File dest) throws IOException {
        try (ArArchiveInputStream ar = new ArArchiveInputStream(new BufferedInputStream(new FileInputStream(deb)))) {
            ArArchiveEntry e;
            while ((e = ar.getNextArEntry()) != null) {
                if (e.getName().startsWith("data.tar")) { untar(ar, dest, "data/data/com.termux/files/"); return; }
            }
        }
        throw new IOException("data.tar fehlt in " + deb.getName());
    }

    private static String clean(String n, String strip) {
        while (n.startsWith("./")) n = n.substring(2);
        if (strip != null) { if (!n.startsWith(strip)) return null; n = n.substring(strip.length()); }
        return n.isEmpty() ? null : n;
    }

    static void untar(InputStream raw, File dest, String strip) throws IOException {
        BufferedInputStream b = new BufferedInputStream(raw, 1 << 16);
        b.mark(16);
        byte[] m = new byte[6];
        int n = b.read(m);
        b.reset();
        InputStream dec;
        if (n >= 2 && (m[0] & 0xFF) == 0x1F && (m[1] & 0xFF) == 0x8B) dec = new GZIPInputStream(b);
        else if (n >= 6 && (m[0] & 0xFF) == 0xFD && m[1] == 0x37 && m[2] == 0x7A) dec = new XZCompressorInputStream(b);
        else throw new IOException("Archivformat nicht unterstützt (nur gzip/xz)");
        Path root = dest.toPath().normalize();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(dec)) {
            TarArchiveEntry e;
            while ((e = tar.getNextTarEntry()) != null) {
                String name = clean(e.getName(), strip);
                if (name == null) continue;
                File out = new File(dest, name);
                if (!out.toPath().normalize().startsWith(root)) continue;
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                if (e.isSymbolicLink()) {
                    Files.deleteIfExists(out.toPath());
                    try { Files.createSymbolicLink(out.toPath(), Paths.get(e.getLinkName())); } catch (Exception ignored) { }
                    continue;
                }
                if (e.isLink()) {
                    String ln = clean(e.getLinkName(), strip);
                    try { if (ln != null) Files.copy(new File(dest, ln).toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) { }
                    continue;
                }
                if (!e.isFile()) continue;
                Files.deleteIfExists(out.toPath());
                try (OutputStream o = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = tar.read(buf)) > 0) o.write(buf, 0, r);
                }
                out.setExecutable(true, false);
            }
        }
    }
}
