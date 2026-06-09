package com.nothingreader.app;

final class Chapter {
    String title;
    String text;
    int startOffset;
    String source;
    int level = 1;

    Chapter(String title, String text, String source) {
        this.title = title;
        this.text = text;
        this.source = source;
    }

    Chapter(String title, String text, String source, int level) {
        this(title, text, source);
        this.level = Math.max(1, level);
    }
}
