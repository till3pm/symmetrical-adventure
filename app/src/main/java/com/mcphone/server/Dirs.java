package com.mcphone.server;

import android.content.Context;
import android.text.TextUtils;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

final class Dirs {
    static volatile String noTag;

    /** Pfad der kleinen Bibliothek, die Androids Heap-Pointer-Tagging im Java-Prozess abschaltet. */
    static void init(Context c) {
        File f = new File(c.getApplicationInfo().nativeLibraryDir, "libnotag.so");
        noTag = f.exists() ? f.getPath() : null;
    }

    static void applyNoTag(java.util.Map<String, String> env) {
        if (noTag != null) env.put("LD_PRELOAD", noTag);
        env.put("MEMTAG_OPTIONS", "off");
    }

    static File runtime(Context c) { return new File(c.getFilesDir(), "runtime"); }
    static File server(Context c) { return new File(c.getFilesDir(), "server"); }

    static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }

    /** Sucht bin/java rekursiv und gibt das Java-Home zurueck. */
    static String findJavaHome(File dir) { return find(dir, 0); }

    private static String find(File dir, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null || depth > 4) return null;
        for (File f : fs) {
            if (f.isFile() && f.getName().equals("java") && dir.getName().equals("bin"))
                return dir.getParentFile().getPath();
        }
        for (File f : fs) {
            if (f.isDirectory()) {
                String r = find(f, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** Alle Ordner unterhalb von lib/, die .so-Dateien enthalten. */
    static String libPath(String home) {
        Set<String> s = new LinkedHashSet<>();
        collect(new File(home, "lib"), s, 0);
        return TextUtils.join(":", s);
    }

    private static void collect(File d, Set<String> s, int depth) {
        File[] fs = d.listFiles();
        if (fs == null || depth > 3) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, s, depth + 1);
            else if (f.getName().endsWith(".so")) s.add(f.getParent());
        }
    }
}
