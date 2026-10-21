package com.mcphone.server;

import android.content.Context;
import android.graphics.Typeface;

/** Laedt res/font/claude_font.* falls vorhanden, sonst die System-Serifenschrift. */
final class Fonts {
    static Typeface load(Context c) {
        int id = c.getResources().getIdentifier("claude_font", "font", c.getPackageName());
        if (id != 0) {
            try {
                Typeface t = c.getResources().getFont(id);
                if (t != null) return t;
            } catch (Exception ignored) { }
        }
        return Typeface.SERIF;
    }
}
