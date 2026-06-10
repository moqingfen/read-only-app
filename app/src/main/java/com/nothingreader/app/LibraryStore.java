package com.nothingreader.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class LibraryStore {
    private static final int MAX_CACHE_TEXT_CHARS = 2_000_000;
    private static final long MAX_CACHE_FILE_BYTES = 6L * 1024L * 1024L;

    private final Context context;
    private final File libraryFile;
    private final File booksDir;
    private final File cacheDir;
    private final File coversDir;

    LibraryStore(Context context) {
        this.context = context.getApplicationContext();
        this.libraryFile = new File(context.getFilesDir(), "library.json");
        this.booksDir = new File(context.getFilesDir(), "books");
        this.cacheDir = new File(context.getFilesDir(), "content-cache");
        this.coversDir = new File(context.getFilesDir(), "covers");
        if (!booksDir.exists()) {
            booksDir.mkdirs();
        }
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        if (!coversDir.exists()) {
            coversDir.mkdirs();
        }
    }

    List<Book> loadBooks() {
        ArrayList<Book> books = new ArrayList<>();
        if (!libraryFile.exists()) {
            return books;
        }
        try {
            String json = DocumentParser.readPlainFile(libraryFile);
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) {
                    books.add(Book.fromJson(item));
                }
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return books;
    }

    void saveBooks(List<Book> books) {
        JSONArray array = new JSONArray();
        try {
            for (Book book : books) {
                array.put(book.toJson());
            }
            byte[] bytes = array.toString(2).getBytes("UTF-8");
            try (FileOutputStream output = new FileOutputStream(libraryFile, false)) {
                output.write(bytes);
            }
        } catch (Exception ignored) {
        }
    }

    Book importBook(Uri uri) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        String displayName = getDisplayName(resolver, uri);
        String extension = getExtension(displayName);
        String format = formatFromExtension(extension);
        if (format.isEmpty()) {
            format = formatFromMime(resolver.getType(uri));
        }
        if (format.isEmpty()) {
            throw new IllegalArgumentException("仅支持 EPUB、TXT、Markdown、PDF。");
        }
        if (extension.isEmpty()) {
            extension = extensionForFormat(format);
        }

        String id = UUID.randomUUID().toString();
        File target = new File(booksDir, id + "." + extension);
        try (InputStream input = resolver.openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target, false)) {
            if (input == null) {
                throw new IllegalArgumentException("无法打开文件。");
            }
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }

        BookMetadata metadata;
        try {
            metadata = DocumentParser.parseMetadata(target, format, displayName);
        } catch (Exception exception) {
            metadata = new BookMetadata(stripExtension(displayName), "");
        }
        long now = System.currentTimeMillis();
        Book book = new Book();
        book.id = id;
        book.title = metadata.title.isEmpty() ? stripExtension(displayName) : metadata.title;
        book.author = metadata.author;
        book.format = format;
        book.fileName = displayName;
        book.localPath = target.getAbsolutePath();
        if ("EPUB".equals(format)) {
            File cover = new File(coversDir, id + ".cover");
            if (DocumentParser.extractEpubCover(target, cover)) {
                book.coverPath = cover.getAbsolutePath();
            }
        }
        book.addedAt = now;
        book.lastOpenedAt = now;
        book.progress = 0.0f;
        return book;
    }

    Book findExisting(Uri uri, List<Book> books) {
        if (uri == null || books == null || books.isEmpty()) {
            return null;
        }
        ContentResolver resolver = context.getContentResolver();
        String displayName = getDisplayName(resolver, uri);
        if (displayName == null || displayName.trim().isEmpty()) {
            return null;
        }
        long size = getFileSize(resolver, uri);
        for (Book book : books) {
            if (book.fileName == null || !book.fileName.equals(displayName)) {
                continue;
            }
            if (size <= 0) {
                return book;
            }
            File local = book.localPath == null ? null : new File(book.localPath);
            if (local != null && local.exists() && local.length() == size) {
                return book;
            }
        }
        return null;
    }

    private static long getFileSize(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    void deleteBook(Book book) {
        if (book.localPath != null) {
            File file = new File(book.localPath);
            if (file.exists()) {
                file.delete();
            }
        }
        if (book.coverPath != null && !book.coverPath.trim().isEmpty()) {
            File cover = new File(book.coverPath);
            if (cover.exists()) {
                cover.delete();
            }
        }
        deleteCachedContent(book);
    }

    ReaderContent loadCachedContent(Book book) {
        File cache = cacheFile(book);
        if (cache == null || !cache.exists()) {
            return null;
        }
        try {
            if (cache.length() > MAX_CACHE_FILE_BYTES) {
                cache.delete();
                return null;
            }
            File source = new File(book.localPath);
            JSONObject json = new JSONObject(DocumentParser.readPlainFile(cache));
            if (json.optLong("sourceLength", -1) != source.length()
                    || json.optLong("sourceModified", -1) != source.lastModified()) {
                cache.delete();
                return null;
            }
            JSONObject content = json.optJSONObject("content");
            return content == null ? null : ReaderContent.fromJson(content);
        } catch (Exception exception) {
            cache.delete();
            return null;
        }
    }

    void saveCachedContent(Book book, ReaderContent content) {
        File cache = cacheFile(book);
        if (cache == null || content == null) {
            return;
        }
        try {
            if (content.fullText != null && content.fullText.length() > MAX_CACHE_TEXT_CHARS) {
                if (cache.exists()) {
                    cache.delete();
                }
                return;
            }
            File source = new File(book.localPath);
            JSONObject json = new JSONObject();
            json.put("sourceLength", source.length());
            json.put("sourceModified", source.lastModified());
            json.put("content", content.toJson());
            try (FileOutputStream output = new FileOutputStream(cache, false)) {
                output.write(json.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    private void deleteCachedContent(Book book) {
        File cache = cacheFile(book);
        if (cache != null && cache.exists()) {
            cache.delete();
        }
    }

    private File cacheFile(Book book) {
        if (book == null || book.id == null || book.id.trim().isEmpty()) {
            return null;
        }
        return new File(cacheDir, book.id + ".json");
    }

    private static String getDisplayName(ContentResolver resolver, Uri uri) {
        String name = null;
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    name = cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            String path = uri.getLastPathSegment();
            name = path == null ? "book" : path;
        }
        return name;
    }

    static String formatFromExtension(String extension) {
        String normalized = extension == null ? "" : extension.toLowerCase(Locale.US);
        if ("epub".equals(normalized)) {
            return "EPUB";
        }
        if ("txt".equals(normalized) || "text".equals(normalized)) {
            return "TXT";
        }
        if ("md".equals(normalized) || "markdown".equals(normalized) || "mdown".equals(normalized)) {
            return "MD";
        }
        if ("pdf".equals(normalized)) {
            return "PDF";
        }
        return "";
    }

    static String formatFromMime(String mime) {
        String normalized = mime == null ? "" : mime.toLowerCase(Locale.US);
        if (normalized.contains("epub")) {
            return "EPUB";
        }
        if (normalized.contains("markdown")) {
            return "MD";
        }
        if (normalized.contains("pdf")) {
            return "PDF";
        }
        if (normalized.startsWith("text/")) {
            return "TXT";
        }
        return "";
    }

    private static String extensionForFormat(String format) {
        if ("EPUB".equals(format)) {
            return "epub";
        }
        if ("MD".equals(format)) {
            return "md";
        }
        if ("PDF".equals(format)) {
            return "pdf";
        }
        return "txt";
    }

    static String getExtension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    static String stripExtension(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "未命名";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
