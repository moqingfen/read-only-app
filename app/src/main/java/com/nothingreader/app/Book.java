package com.nothingreader.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class Book {
    String id;
    String title;
    String author;
    String format;
    String fileName;
    String localPath;
    String coverPath;
    String locator;
    long addedAt;
    long lastOpenedAt;
    float progress;
    final List<Bookmark> bookmarks = new ArrayList<>();

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("title", title);
        json.put("author", author);
        json.put("format", format);
        json.put("fileName", fileName);
        json.put("localPath", localPath);
        json.put("coverPath", coverPath);
        json.put("locator", locator);
        json.put("addedAt", addedAt);
        json.put("lastOpenedAt", lastOpenedAt);
        json.put("progress", progress);
        JSONArray bookmarkArray = new JSONArray();
        for (Bookmark bookmark : bookmarks) {
            bookmarkArray.put(bookmark.toJson());
        }
        json.put("bookmarks", bookmarkArray);
        return json;
    }

    static Book fromJson(JSONObject json) {
        Book book = new Book();
        book.id = json.optString("id");
        book.title = json.optString("title");
        book.author = json.optString("author");
        book.format = json.optString("format");
        book.fileName = json.optString("fileName");
        book.localPath = json.optString("localPath");
        String coverPath = json.optString("coverPath", "");
        book.coverPath = coverPath.trim().isEmpty() ? null : coverPath;
        String locator = json.optString("locator", "");
        book.locator = locator.trim().isEmpty() ? null : locator;
        book.addedAt = json.optLong("addedAt");
        book.lastOpenedAt = json.optLong("lastOpenedAt");
        book.progress = (float) json.optDouble("progress", 0.0);
        JSONArray bookmarkArray = json.optJSONArray("bookmarks");
        if (bookmarkArray != null) {
            for (int i = 0; i < bookmarkArray.length(); i++) {
                JSONObject item = bookmarkArray.optJSONObject(i);
                if (item != null) {
                    book.bookmarks.add(Bookmark.fromJson(item));
                }
            }
        }
        return book;
    }
}
