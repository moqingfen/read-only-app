package com.nothingreader.app;

import org.json.JSONException;
import org.json.JSONObject;

final class Bookmark {
    String id;
    String label;
    String snippet;
    String chapterTitle;
    float progress;
    long createdAt;

    Bookmark(String id, String label, String snippet, String chapterTitle, float progress, long createdAt) {
        this.id = id;
        this.label = label;
        this.snippet = snippet;
        this.chapterTitle = chapterTitle;
        this.progress = progress;
        this.createdAt = createdAt;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("label", label);
        json.put("snippet", snippet);
        json.put("chapterTitle", chapterTitle);
        json.put("progress", progress);
        json.put("createdAt", createdAt);
        return json;
    }

    static Bookmark fromJson(JSONObject json) {
        return new Bookmark(
                json.optString("id"),
                json.optString("label"),
                json.optString("snippet"),
                json.optString("chapterTitle"),
                (float) json.optDouble("progress", 0.0),
                json.optLong("createdAt", System.currentTimeMillis())
        );
    }
}
