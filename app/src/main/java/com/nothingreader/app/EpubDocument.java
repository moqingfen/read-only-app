package com.nothingreader.app;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class EpubDocument {
    String title;
    String author;
    File rootDir;
    final List<EpubChapterRef> chapters = new ArrayList<>();

    boolean hasChapters() {
        return !chapters.isEmpty();
    }
}
