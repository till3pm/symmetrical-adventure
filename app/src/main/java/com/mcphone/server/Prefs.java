package com.mcphone.server;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getApplicationContext().getSharedPreferences("mc", 0); }

    String s(String k, String d) { return sp.getString(k, d); }

    int i(String k, int d) {
        try { return Integer.parseInt(sp.getString(k, String.valueOf(d)).trim()); } catch (Exception e) { return d; }
    }

    boolean b(String k, boolean d) { return Boolean.parseBoolean(sp.getString(k, String.valueOf(d))); }

    void put(String k, Object v) { sp.edit().putString(k, String.valueOf(v)).apply(); }
}
