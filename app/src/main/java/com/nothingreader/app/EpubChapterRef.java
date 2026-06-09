package com.nothingreader.app;

final class EpubChapterRef {
    final String title;
    final String path;

    EpubChapterRef(String title, String path) {
        this.title = title == null ? "" : title;
        this.path = path == null ? "" : path;
    }
}
