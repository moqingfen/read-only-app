package com.nothingreader.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/** 本地阅读统计：按天累计时长 + 按书累计时长，存于 SharedPreferences。 */
final class ReadingStats {
    private static final String PREFS = "reading_stats";
    private static final String KEY_DAYS = "days";
    private static final String KEY_BOOKS = "books";
    private static final long MAX_SESSION_MILLIS = 30L * 60L * 1000L;
    private static final int MAX_DAYS_KEPT = 400;

    private ReadingStats() {
    }

    static void addSession(Context context, String bookId, long millis) {
        if (millis <= 0) {
            return;
        }
        long capped = Math.min(millis, MAX_SESSION_MILLIS);
        SharedPreferences prefs = prefs(context);
        try {
            JSONObject days = new JSONObject(prefs.getString(KEY_DAYS, "{}"));
            String today = dayKey(System.currentTimeMillis());
            days.put(today, days.optLong(today, 0) + capped);
            trimDays(days);
            JSONObject books = new JSONObject(prefs.getString(KEY_BOOKS, "{}"));
            if (bookId != null && !bookId.trim().isEmpty()) {
                books.put(bookId, books.optLong(bookId, 0) + capped);
            }
            prefs.edit()
                    .putString(KEY_DAYS, days.toString())
                    .putString(KEY_BOOKS, books.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    static long todayMillis(Context context) {
        try {
            JSONObject days = new JSONObject(prefs(context).getString(KEY_DAYS, "{}"));
            return days.optLong(dayKey(System.currentTimeMillis()), 0);
        } catch (Exception exception) {
            return 0;
        }
    }

    static long totalMillis(Context context) {
        try {
            JSONObject days = new JSONObject(prefs(context).getString(KEY_DAYS, "{}"));
            long total = 0;
            Iterator<String> keys = days.keys();
            while (keys.hasNext()) {
                total += days.optLong(keys.next(), 0);
            }
            return total;
        } catch (Exception exception) {
            return 0;
        }
    }

    static int streakDays(Context context) {
        try {
            JSONObject days = new JSONObject(prefs(context).getString(KEY_DAYS, "{}"));
            int streak = 0;
            Calendar calendar = Calendar.getInstance();
            // 今天没读不打断连续记录，从今天或昨天开始数。
            if (days.optLong(dayKey(calendar.getTimeInMillis()), 0) <= 0) {
                calendar.add(Calendar.DAY_OF_YEAR, -1);
            }
            while (days.optLong(dayKey(calendar.getTimeInMillis()), 0) > 0) {
                streak++;
                calendar.add(Calendar.DAY_OF_YEAR, -1);
                if (streak > MAX_DAYS_KEPT) {
                    break;
                }
            }
            return streak;
        } catch (Exception exception) {
            return 0;
        }
    }

    static long bookMillis(Context context, String bookId) {
        if (bookId == null) {
            return 0;
        }
        try {
            JSONObject books = new JSONObject(prefs(context).getString(KEY_BOOKS, "{}"));
            return books.optLong(bookId, 0);
        } catch (Exception exception) {
            return 0;
        }
    }

    static String formatDuration(long millis) {
        long minutes = millis / 60000L;
        if (minutes < 1) {
            return "不足 1 分钟";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours <= 0) {
            return minutes + " 分钟";
        }
        return hours + " 小时 " + rest + " 分钟";
    }

    private static void trimDays(JSONObject days) {
        if (days.length() <= MAX_DAYS_KEPT) {
            return;
        }
        String oldest = null;
        Iterator<String> keys = days.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (oldest == null || key.compareTo(oldest) < 0) {
                oldest = key;
            }
        }
        if (oldest != null) {
            days.remove(oldest);
        }
    }

    private static String dayKey(long time) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(time));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
