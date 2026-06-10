package com.nothingreader.app;

import org.json.JSONException;
import org.json.JSONObject;

final class Annotation {
    String id;
    int startOffset;
    int endOffset;
    String snippet;
    String note;
    String chapterTitle;
    long createdAt;

    Annotation(String id, int startOffset, int endOffset, String snippet, String note, String chapterTitle, long createdAt) {
        this.id = id;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.snippet = snippet;
        this.note = note;
        this.chapterTitle = chapterTitle;
        this.createdAt = createdAt;
    }

    boolean hasNote() {
        return note != null && !note.trim().isEmpty();
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("startOffset", startOffset);
        json.put("endOffset", endOffset);
        json.put("snippet", snippet == null ? "" : snippet);
        json.put("note", note == null ? "" : note);
        json.put("chapterTitle", chapterTitle == null ? "" : chapterTitle);
        json.put("createdAt", createdAt);
        return json;
    }

    static Annotation fromJson(JSONObject json) {
        return new Annotation(
                json.optString("id"),
                json.optInt("startOffset"),
                json.optInt("endOffset"),
                json.optString("snippet"),
                json.optString("note"),
                json.optString("chapterTitle"),
                json.optLong("createdAt", System.currentTimeMillis())
        );
    }
}
