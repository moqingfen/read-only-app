package com.nothingreader.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class ReaderContent {
    static final int CACHE_VERSION = 2;
    String title;
    String author;
    String fullText;
    int parserVersion = CACHE_VERSION;
    final List<Chapter> chapters = new ArrayList<>();

    static ReaderContent fromChapters(String title, String author, List<Chapter> sourceChapters) {
        Builder builder = builder(title, author);
        for (Chapter chapter : sourceChapters) {
            builder.addChapter(chapter.title, chapter.text, chapter.source, chapter.level);
        }
        ReaderContent content = builder.build();
        if (content.fullText.isEmpty()) {
            content.fullText = "无法读取正文。";
        }
        if (content.title.isEmpty()) {
            content.title = sourceChapters.isEmpty() ? "未命名" : cleanTitle(sourceChapters.get(0).title);
        }
        if (content.title.isEmpty()) {
            content.title = "未命名";
        }
        return content;
    }

    static Builder builder(String title, String author) {
        return new Builder(title, author);
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("title", title);
        json.put("author", author);
        json.put("fullText", fullText);
        json.put("parserVersion", parserVersion);
        JSONArray chapterArray = new JSONArray();
        for (Chapter chapter : chapters) {
            JSONObject item = new JSONObject();
            item.put("title", chapter.title == null ? "" : chapter.title);
            item.put("source", chapter.source == null ? "" : chapter.source);
            item.put("startOffset", chapter.startOffset);
            item.put("level", chapter.level);
            chapterArray.put(item);
        }
        json.put("chapters", chapterArray);
        return json;
    }

    static ReaderContent fromJson(JSONObject json) {
        ReaderContent content = new ReaderContent();
        content.title = cleanTitle(json.optString("title"));
        content.author = cleanTitle(json.optString("author"));
        content.fullText = json.optString("fullText", "");
        content.parserVersion = json.optInt("parserVersion", 0);
        JSONArray chapterArray = json.optJSONArray("chapters");
        if (chapterArray != null) {
            for (int i = 0; i < chapterArray.length(); i++) {
                JSONObject item = chapterArray.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Chapter chapter = new Chapter(
                        cleanTitle(item.optString("title")),
                        "",
                        item.optString("source"),
                        item.optInt("level", 1)
                );
                chapter.startOffset = item.optInt("startOffset");
                content.chapters.add(chapter);
            }
        }
        if (content.title.isEmpty()) {
            content.title = "未命名";
        }
        if (content.fullText == null || content.fullText.isEmpty()) {
            content.fullText = "无法读取正文。";
        }
        if (content.chapters.isEmpty()) {
            Chapter chapter = new Chapter(content.title, "", "");
            chapter.startOffset = 0;
            content.chapters.add(chapter);
        }
        return content;
    }

    private static String cleanTitle(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    static final class Builder {
        private final ReaderContent content = new ReaderContent();
        private final StringBuilder text = new StringBuilder();

        Builder(String title, String author) {
            content.title = cleanTitle(title);
            content.author = cleanTitle(author);
            content.parserVersion = CACHE_VERSION;
        }

        void addChapter(String title, String chapterText, String source) {
            addChapter(title, chapterText, source, 1);
        }

        void addChapter(String title, String chapterText, String source, int level) {
            String cleanChapterTitle = cleanTitle(title);
            String cleanChapterText = chapterText == null ? "" : chapterText.trim();
            if (cleanChapterTitle.isEmpty() && cleanChapterText.isEmpty()) {
                return;
            }
            Chapter normalized = new Chapter(cleanChapterTitle.isEmpty() ? "正文" : cleanChapterTitle, "", source, level);
            normalized.startOffset = text.length();
            if (!normalized.title.isEmpty()) {
                text.append(normalized.title).append("\n\n");
            }
            text.append(cleanChapterText).append("\n\n");
            content.chapters.add(normalized);
        }

        boolean hasChapters() {
            return !content.chapters.isEmpty();
        }

        ReaderContent build() {
            content.fullText = text.toString().trim();
            if (content.fullText.isEmpty()) {
                content.fullText = "无法读取正文。";
            }
            if (content.title.isEmpty()) {
                content.title = content.chapters.isEmpty() ? "未命名" : cleanTitle(content.chapters.get(0).title);
            }
            if (content.title.isEmpty()) {
                content.title = "未命名";
            }
            return content;
        }
    }
}
