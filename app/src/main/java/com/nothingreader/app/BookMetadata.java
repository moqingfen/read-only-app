package com.nothingreader.app;

final class BookMetadata {
    final String title;
    final String author;

    BookMetadata(String title, String author) {
        this.title = title == null ? "" : title.trim();
        this.author = author == null ? "" : author.trim();
    }
}
