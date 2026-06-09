package com.nothingreader.app;

import android.content.Context;
import android.content.SharedPreferences;

final class ReaderSettings {
    private static final String PREFS = "reader_settings";
    float fontSp = 19.0f;
    float lineMultiplier = 1.45f;
    String theme = "paper";
    String shelfSort = "recent";
    boolean shelfGrid = true;
    boolean pageMode = true;

    static ReaderSettings load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ReaderSettings settings = new ReaderSettings();
        settings.fontSp = prefs.getFloat("fontSp", 19.0f);
        settings.lineMultiplier = prefs.getFloat("lineMultiplier", 1.45f);
        settings.theme = prefs.getString("theme", "paper");
        settings.shelfSort = prefs.getString("shelfSort", "recent");
        settings.shelfGrid = prefs.getBoolean("shelfGrid", true);
        settings.pageMode = prefs.getBoolean("pageMode", true);
        return settings;
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putFloat("fontSp", fontSp)
                .putFloat("lineMultiplier", lineMultiplier)
                .putString("theme", theme)
                .putString("shelfSort", shelfSort)
                .putBoolean("shelfGrid", shelfGrid)
                .putBoolean("pageMode", pageMode)
                .apply();
    }
}
