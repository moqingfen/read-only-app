package com.nothingreader.app;

import android.os.Build;
import android.text.Html;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

final class DocumentParser {
    private static final int PREVIEW_BYTES = 128 * 1024;

    private DocumentParser() {
    }

    static ReaderContent parse(File file, String format, String fallbackName) throws Exception {
        if ("EPUB".equals(format)) {
            return parseEpub(file, fallbackName);
        }
        if ("MD".equals(format)) {
            return parseMarkdown(file, fallbackName);
        }
        if ("TXT".equals(format)) {
            return parseTxt(file, fallbackName);
        }
        if ("PDF".equals(format)) {
            throw new IllegalArgumentException("PDF 使用分页渲染阅读。");
        }
        throw new IllegalArgumentException("Unsupported format");
    }

    static BookMetadata parseMetadata(File file, String format, String fallbackName) throws Exception {
        if ("EPUB".equals(format)) {
            return parseEpubMetadata(file, fallbackName);
        }
        if ("MD".equals(format)) {
            String raw = readPreviewFile(file);
            String title = firstMarkdownTitle(raw);
            if (title.isEmpty()) {
                title = LibraryStore.stripExtension(fallbackName);
            }
            return new BookMetadata(title, "");
        }
        if ("TXT".equals(format)) {
            String raw = readPreviewFile(file);
            String normalized = normalizeText(raw);
            String title = firstNonEmptyLine(normalized);
            if (title.isEmpty() || title.length() > 80) {
                title = LibraryStore.stripExtension(fallbackName);
            }
            return new BookMetadata(title, "");
        }
        if ("PDF".equals(format)) {
            return new BookMetadata(LibraryStore.stripExtension(fallbackName), "");
        }
        throw new IllegalArgumentException("Unsupported format");
    }

    static String readPlainFile(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            return decodeText(readAllBytes(input));
        }
    }

    static String markdownToHtml(String markdown) {
        return new MarkdownHtmlRenderer(markdown == null ? "" : markdown).render();
    }

    private static String readPreviewFile(File file) throws Exception {
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(PREVIEW_BYTES, Math.max(0, (int) Math.min(file.length(), PREVIEW_BYTES))));
            byte[] buffer = new byte[8192];
            int remaining = PREVIEW_BYTES;
            int read;
            while (remaining > 0 && (read = input.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                output.write(buffer, 0, read);
                remaining -= read;
            }
            bytes = output.toByteArray();
        }
        try {
            return decodeText(bytes);
        } catch (Exception exception) {
            if (bytes.length > 4) {
                return decodeText(Arrays.copyOf(bytes, bytes.length - 4));
            }
            throw exception;
        }
    }

    private static ReaderContent parseTxt(File file, String fallbackName) throws Exception {
        String raw = readPlainFile(file);
        String normalized = normalizeText(raw);
        String title = firstNonEmptyLine(normalized);
        if (title.isEmpty() || title.length() > 80) {
            title = LibraryStore.stripExtension(fallbackName);
        }
        return ReaderContent.fromChapters(title, "", splitTextChapters(normalized, title));
    }

    private static ReaderContent parseMarkdown(File file, String fallbackName) throws Exception {
        String raw = readPlainFile(file);
        String title = firstMarkdownTitle(raw);
        if (title.isEmpty()) {
            title = LibraryStore.stripExtension(fallbackName);
        }
        return ReaderContent.fromChapters(title, "", splitMarkdownChapters(raw, title));
    }

    private static ReaderContent parseEpub(File file, String fallbackName) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            String opfPath = findOpfPath(zip);
            String opfBase = basePath(opfPath);
            String opfXml = readZipEntry(zip, opfPath);
            Document opf = parseXml(opfXml);

            String title = textByTag(opf, "dc:title");
            if (title.isEmpty()) {
                title = textByTag(opf, "title");
            }
            if (title.isEmpty()) {
                title = LibraryStore.stripExtension(fallbackName);
            }
            String author = textByTag(opf, "dc:creator");
            if (author.isEmpty()) {
                author = textByTag(opf, "creator");
            }

            Map<String, ManifestItem> manifest = parseManifest(opf, opfBase);
            Map<String, String> toc = parseEpubToc(zip, manifest);
            ReaderContent.Builder contentBuilder = ReaderContent.builder(title, author);
            int readableChapterCount = 0;
            NodeList spineItems = opf.getElementsByTagName("itemref");
            for (int i = 0; i < spineItems.getLength(); i++) {
                Node node = spineItems.item(i);
                if (!(node instanceof Element)) {
                    continue;
                }
                String idRef = ((Element) node).getAttribute("idref");
                ManifestItem item = manifest.get(idRef);
                if (item == null || !item.looksReadable()) {
                    continue;
                }
                String html = readZipEntryOrNull(zip, item.path);
                if (html == null) {
                    continue;
                }
                String chapterText = htmlToText(html);
                if (chapterText.isEmpty()) {
                    continue;
                }
                String chapterTitle = toc.get(normalizeTocPath(item.path));
                if (chapterTitle == null || chapterTitle.trim().isEmpty()) {
                    chapterTitle = extractHtmlTitle(html);
                }
                if (chapterTitle == null || chapterTitle.trim().isEmpty()) {
                    chapterTitle = "章节 " + (readableChapterCount + 1);
                }
                contentBuilder.addChapter(chapterTitle, chapterText, item.path);
                readableChapterCount++;
            }
            if (!contentBuilder.hasChapters()) {
                throw new IllegalArgumentException("EPUB 正文为空。");
            }
            return contentBuilder.build();
        }
    }

    static EpubDocument prepareEpubWeb(File file, File outputDir, String fallbackName) throws Exception {
        deleteTree(outputDir);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IllegalArgumentException("无法准备 EPUB 缓存。");
        }
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                File target = safeExtractTarget(outputDir, entry.getName());
                if (target == null) {
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                try (InputStream input = zip.getInputStream(entry);
                     FileOutputStream output = new FileOutputStream(target, false)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
            }

            String opfPath = findOpfPath(zip);
            String opfBase = basePath(opfPath);
            Document opf = parseXml(readZipEntry(zip, opfPath));
            EpubDocument document = new EpubDocument();
            document.rootDir = outputDir;
            document.title = textByTag(opf, "dc:title");
            if (document.title.isEmpty()) {
                document.title = textByTag(opf, "title");
            }
            if (document.title.isEmpty()) {
                document.title = LibraryStore.stripExtension(fallbackName);
            }
            document.author = textByTag(opf, "dc:creator");
            if (document.author.isEmpty()) {
                document.author = textByTag(opf, "creator");
            }

            Map<String, ManifestItem> manifest = parseManifest(opf, opfBase);
            Map<String, String> toc = parseEpubToc(zip, manifest);
            NodeList spineItems = opf.getElementsByTagName("itemref");
            int readableChapterCount = 0;
            for (int i = 0; i < spineItems.getLength(); i++) {
                Node node = spineItems.item(i);
                if (!(node instanceof Element)) {
                    continue;
                }
                String idRef = ((Element) node).getAttribute("idref");
                ManifestItem item = manifest.get(idRef);
                if (item == null || !item.looksReadable()) {
                    continue;
                }
                File chapterFile = findExtractedFile(outputDir, item.path);
                if (chapterFile == null || !chapterFile.exists()) {
                    continue;
                }
                String chapterTitle = toc.get(normalizeTocPath(item.path));
                if (chapterTitle == null || chapterTitle.trim().isEmpty()) {
                    chapterTitle = extractHtmlTitle(readPlainFile(chapterFile));
                }
                if (chapterTitle == null || chapterTitle.trim().isEmpty()) {
                    chapterTitle = "章节 " + (readableChapterCount + 1);
                }
                document.chapters.add(new EpubChapterRef(chapterTitle, relativePath(outputDir, chapterFile)));
                readableChapterCount++;
            }
            if (!document.hasChapters()) {
                throw new IllegalArgumentException("EPUB 正文为空。");
            }
            return document;
        }
    }

    static boolean extractEpubCover(File file, File outputFile) {
        try (ZipFile zip = new ZipFile(file)) {
            String opfPath = findOpfPath(zip);
            String opfBase = basePath(opfPath);
            Document opf = parseXml(readZipEntry(zip, opfPath));
            Map<String, ManifestItem> manifest = parseManifest(opf, opfBase);
            ManifestItem cover = null;
            for (ManifestItem item : manifest.values()) {
                String lower = item.path.toLowerCase(Locale.US);
                if (item.properties.contains("cover-image")
                        || lower.contains("cover")
                        || lower.endsWith("cover.jpg")
                        || lower.endsWith("cover.jpeg")
                        || lower.endsWith("cover.png")) {
                    if (item.looksImage()) {
                        cover = item;
                        break;
                    }
                }
            }
            if (cover == null) {
                return false;
            }
            ZipEntry entry = findZipEntry(zip, cover.path);
            if (entry == null) {
                return false;
            }
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (InputStream input = zip.getInputStream(entry);
                 FileOutputStream output = new FileOutputStream(outputFile, false)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            return outputFile.exists() && outputFile.length() > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static BookMetadata parseEpubMetadata(File file, String fallbackName) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            String opfPath = findOpfPath(zip);
            String opfXml = readZipEntry(zip, opfPath);
            Document opf = parseXml(opfXml);

            String title = textByTag(opf, "dc:title");
            if (title.isEmpty()) {
                title = textByTag(opf, "title");
            }
            if (title.isEmpty()) {
                title = LibraryStore.stripExtension(fallbackName);
            }
            String author = textByTag(opf, "dc:creator");
            if (author.isEmpty()) {
                author = textByTag(opf, "creator");
            }
            return new BookMetadata(title, author);
        }
    }

    private static String findOpfPath(ZipFile zip) throws Exception {
        try {
            String container = readZipEntry(zip, "META-INF/container.xml");
            Document containerDoc = parseXml(container);
            NodeList rootFiles = containerDoc.getElementsByTagName("rootfile");
            if (rootFiles.getLength() > 0) {
                Element rootFile = (Element) rootFiles.item(0);
                String opfPath = rootFile.getAttribute("full-path");
                if (opfPath != null && !opfPath.trim().isEmpty()) {
                    return normalizeTocPath(opfPath);
                }
            }
        } catch (Exception ignored) {
        }
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name != null && name.toLowerCase(Locale.US).endsWith(".opf")) {
                return name;
            }
        }
        throw new IllegalArgumentException("Invalid EPUB container");
    }

    private static Map<String, ManifestItem> parseManifest(Document opf, String opfBase) {
        Map<String, ManifestItem> manifest = new HashMap<>();
        NodeList items = opf.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Node node = items.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            Element element = (Element) node;
            String id = element.getAttribute("id");
            String href = element.getAttribute("href");
            String mediaType = element.getAttribute("media-type");
            String properties = element.getAttribute("properties");
            if (!id.isEmpty() && !href.isEmpty()) {
                manifest.put(id, new ManifestItem(resolveZipPath(opfBase, href), mediaType, properties));
            }
        }
        return manifest;
    }

    private static Map<String, String> parseEpubToc(ZipFile zip, Map<String, ManifestItem> manifest) {
        Map<String, String> toc = new HashMap<>();
        ManifestItem ncx = null;
        ManifestItem nav = null;
        for (ManifestItem item : manifest.values()) {
            if (item.mediaType.contains("application/x-dtbncx+xml") || item.path.toLowerCase(Locale.US).endsWith(".ncx")) {
                ncx = item;
            }
            if (item.properties.contains("nav")) {
                nav = item;
            }
        }
        if (ncx != null) {
            try {
                Document ncxDoc = parseXml(readZipEntry(zip, ncx.path));
                NodeList navPoints = ncxDoc.getElementsByTagName("navPoint");
                String base = basePath(ncx.path);
                for (int i = 0; i < navPoints.getLength(); i++) {
                    Element navPoint = (Element) navPoints.item(i);
                    String label = textByTag(navPoint, "text");
                    NodeList contentNodes = navPoint.getElementsByTagName("content");
                    if (!label.isEmpty() && contentNodes.getLength() > 0) {
                        Element content = (Element) contentNodes.item(0);
                        String path = resolveZipPath(base, stripFragment(content.getAttribute("src")));
                        toc.put(normalizeTocPath(path), label);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (toc.isEmpty() && nav != null) {
            try {
                String html = readZipEntry(zip, nav.path);
                String base = basePath(nav.path);
                String[] anchors = html.split("(?i)<a\\s+");
                for (String anchor : anchors) {
                    int hrefIndex = anchor.toLowerCase(Locale.US).indexOf("href=");
                    if (hrefIndex < 0) {
                        continue;
                    }
                    String href = extractQuotedAttribute(anchor.substring(hrefIndex + 5));
                    String label = htmlToText("<p>" + anchor + "</p>");
                    if (!href.isEmpty() && !label.isEmpty()) {
                        toc.put(normalizeTocPath(resolveZipPath(base, stripFragment(href))), label);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return toc;
    }

    private static List<Chapter> splitTextChapters(String text, String fallbackTitle) {
        ArrayList<Chapter> chapters = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        String currentTitle = fallbackTitle;
        StringBuilder current = new StringBuilder();
        boolean foundChapter = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (looksLikeChapterHeading(trimmed) && current.length() > 120) {
                chapters.add(new Chapter(currentTitle, current.toString().trim(), "", textChapterLevel(currentTitle)));
                current.setLength(0);
                currentTitle = trimmed;
                foundChapter = true;
            } else {
                current.append(line).append('\n');
            }
        }
        if (current.length() > 0) {
            chapters.add(new Chapter(foundChapter ? currentTitle : fallbackTitle, current.toString().trim(), "", textChapterLevel(currentTitle)));
        }
        return chapters;
    }

    private static List<Chapter> splitMarkdownChapters(String markdown, String fallbackTitle) {
        ArrayList<Chapter> chapters = new ArrayList<>();
        String[] lines = markdown.split("\\r?\\n");
        String currentTitle = fallbackTitle;
        int currentLevel = 1;
        String currentSource = "";
        int headingIndex = 0;
        StringBuilder current = new StringBuilder();
        boolean hasHeading = false;
        boolean inCodeFence = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCodeFence = !inCodeFence;
                current.append(line).append('\n');
                continue;
            }
            if (!inCodeFence && trimmed.matches("^#{1,6}\\s+.+")) {
                int level = markdownHeadingLevel(trimmed);
                if (current.length() > 0) {
                    chapters.add(new Chapter(currentTitle, cleanMarkdown(current.toString()), currentSource, currentLevel));
                    current.setLength(0);
                }
                currentTitle = trimmed.replaceFirst("^#{1,6}\\s+", "").trim();
                currentLevel = level;
                currentSource = markdownAnchorForIndex(headingIndex++);
                hasHeading = true;
            } else {
                current.append(line).append('\n');
            }
        }
        if (current.length() > 0 || chapters.isEmpty()) {
            chapters.add(new Chapter(hasHeading ? currentTitle : fallbackTitle, cleanMarkdown(current.toString()), currentSource, currentLevel));
        }
        return chapters;
    }

    private static boolean looksLikeChapterHeading(String value) {
        if (value.length() < 2 || value.length() > 64) {
            return false;
        }
        return value.matches("^(第\\s*[一二三四五六七八九十百千万零〇两0-9]+\\s*[章节回部卷集].*)$")
                || value.matches("(?i)^(chapter|section)\\s+[0-9ivxlcdm]+.*$")
                || value.matches("^\\d{1,4}[.、]\\s*\\S+.*$")
                || value.matches("^[一二三四五六七八九十百千万零〇两]+[、.]\\s*\\S+.*$")
                || value.matches("^(序章|楔子|前言|引子|正文|尾声|后记|番外|附录)(\\s*[:：].*)?$")
                || value.matches("^第\\s*[一二三四五六七八九十百千万零〇两0-9]+\\s*卷.*$")
                || value.matches("^卷\\s*[一二三四五六七八九十百千万零〇两0-9]+.*$");
    }

    private static int textChapterLevel(String value) {
        if (value == null) {
            return 1;
        }
        String trimmed = value.trim();
        if (trimmed.matches("^第\\s*[一二三四五六七八九十百千万零〇两0-9]+\\s*卷.*$")
                || trimmed.matches("^卷\\s*[一二三四五六七八九十百千万零〇两0-9]+.*$")) {
            return 1;
        }
        return 2;
    }

    private static int markdownHeadingLevel(String value) {
        int level = 0;
        while (level < value.length() && value.charAt(level) == '#') {
            level++;
        }
        return Math.max(1, Math.min(6, level));
    }

    private static String markdownAnchorForIndex(int index) {
        return "md-heading-" + Math.max(0, index);
    }

    private static String cleanMarkdown(String markdown) {
        String text = markdown;
        text = text.replaceAll("(?m)^\\s*>\\s?", "");
        text = text.replaceAll("(?m)^\\s{0,3}[-*+]\\s+", "• ");
        text = text.replaceAll("(?m)^\\s{0,3}\\d+[.)]\\s+", "• ");
        text = text.replaceAll("!\\[([^]]*)]\\([^)]*\\)", "$1");
        text = text.replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1");
        text = text.replaceAll("`([^`]*)`", "$1");
        text = text.replace("**", "").replace("__", "").replace("*", "").replace("_", "");
        return normalizeText(text);
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String escapeAttribute(String value) {
        return escapeHtml(value == null ? "" : value.trim()).replace(" ", "%20");
    }

    private static final class MarkdownHtmlRenderer {
        private final String[] lines;
        private final StringBuilder html = new StringBuilder();
        private final StringBuilder paragraph = new StringBuilder();
        private final StringBuilder code = new StringBuilder();
        private boolean inCodeFence;
        private boolean inUnorderedList;
        private boolean inOrderedList;
        private int headingIndex;

        MarkdownHtmlRenderer(String markdown) {
            lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        }

        String render() {
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String trimmed = line.trim();
                if (inCodeFence) {
                    if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                        html.append("<pre><code>").append(escapeHtml(code.toString())).append("</code></pre>\n");
                        code.setLength(0);
                        inCodeFence = false;
                    } else {
                        code.append(line).append('\n');
                    }
                    continue;
                }
                if (trimmed.isEmpty()) {
                    flushParagraph();
                    closeLists();
                    continue;
                }
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                    flushParagraph();
                    closeLists();
                    inCodeFence = true;
                    code.setLength(0);
                    continue;
                }
                if (isTableStart(i)) {
                    flushParagraph();
                    closeLists();
                    i = appendTable(i);
                    continue;
                }
                if (trimmed.matches("^#{1,6}\\s+.+")) {
                    flushParagraph();
                    closeLists();
                    int level = markdownHeadingLevel(trimmed);
                    String title = trimmed.replaceFirst("^#{1,6}\\s+", "").trim();
                    html.append("<h").append(level).append(" id=\"")
                            .append(markdownAnchorForIndex(headingIndex++))
                            .append("\">")
                            .append(inlineMarkdown(title))
                            .append("</h").append(level).append(">\n");
                    continue;
                }
                if (trimmed.matches("^[-*_]{3,}\\s*$")) {
                    flushParagraph();
                    closeLists();
                    html.append("<hr>\n");
                    continue;
                }
                if (trimmed.matches("^>\\s?.+")) {
                    flushParagraph();
                    closeLists();
                    html.append("<blockquote><p>")
                            .append(inlineMarkdown(trimmed.replaceFirst("^>\\s?", "")))
                            .append("</p></blockquote>\n");
                    continue;
                }
                if (trimmed.matches("^[-*+]\\s+.+")) {
                    flushParagraph();
                    if (inOrderedList) {
                        html.append("</ol>\n");
                        inOrderedList = false;
                    }
                    if (!inUnorderedList) {
                        html.append("<ul>\n");
                        inUnorderedList = true;
                    }
                    html.append("<li>").append(inlineMarkdown(trimmed.replaceFirst("^[-*+]\\s+", ""))).append("</li>\n");
                    continue;
                }
                if (trimmed.matches("^\\d+[.)]\\s+.+")) {
                    flushParagraph();
                    if (inUnorderedList) {
                        html.append("</ul>\n");
                        inUnorderedList = false;
                    }
                    if (!inOrderedList) {
                        html.append("<ol>\n");
                        inOrderedList = true;
                    }
                    html.append("<li>").append(inlineMarkdown(trimmed.replaceFirst("^\\d+[.)]\\s+", ""))).append("</li>\n");
                    continue;
                }
                closeLists();
                if (paragraph.length() > 0) {
                    paragraph.append(' ');
                }
                paragraph.append(trimmed);
            }
            if (inCodeFence) {
                html.append("<pre><code>").append(escapeHtml(code.toString())).append("</code></pre>\n");
            }
            flushParagraph();
            closeLists();
            return html.toString();
        }

        private void flushParagraph() {
            if (paragraph.length() == 0) {
                return;
            }
            html.append("<p>").append(inlineMarkdown(paragraph.toString())).append("</p>\n");
            paragraph.setLength(0);
        }

        private void closeLists() {
            if (inUnorderedList) {
                html.append("</ul>\n");
                inUnorderedList = false;
            }
            if (inOrderedList) {
                html.append("</ol>\n");
                inOrderedList = false;
            }
        }

        private boolean isTableStart(int index) {
            if (index + 1 >= lines.length || !lines[index].contains("|")) {
                return false;
            }
            return lines[index + 1].trim().matches("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
        }

        private int appendTable(int index) {
            List<String> header = splitTableRow(lines[index]);
            html.append("<table><thead><tr>");
            for (String cell : header) {
                html.append("<th>").append(inlineMarkdown(cell)).append("</th>");
            }
            html.append("</tr></thead><tbody>");
            int i = index + 2;
            while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
                List<String> cells = splitTableRow(lines[i]);
                html.append("<tr>");
                for (String cell : cells) {
                    html.append("<td>").append(inlineMarkdown(cell)).append("</td>");
                }
                html.append("</tr>");
                i++;
            }
            html.append("</tbody></table>\n");
            return i - 1;
        }

        private List<String> splitTableRow(String line) {
            ArrayList<String> cells = new ArrayList<>();
            String value = line.trim();
            if (value.startsWith("|")) {
                value = value.substring(1);
            }
            if (value.endsWith("|")) {
                value = value.substring(0, value.length() - 1);
            }
            for (String cell : value.split("\\|", -1)) {
                cells.add(cell.trim());
            }
            return cells;
        }

        private String inlineMarkdown(String value) {
            String escaped = escapeHtml(value);
            escaped = replaceImages(escaped);
            escaped = escaped.replaceAll("`([^`]+)`", "<code>$1</code>");
            escaped = escaped.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
            escaped = escaped.replaceAll("__([^_]+)__", "<strong>$1</strong>");
            escaped = escaped.replaceAll("~~([^~]+)~~", "<del>$1</del>");
            escaped = escaped.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<em>$1</em>");
            escaped = escaped.replaceAll("(?<!_)_([^_]+)_(?!_)", "<em>$1</em>");
            escaped = escaped.replaceAll("\\[([^]]+)]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
            return escaped;
        }

        private String replaceImages(String value) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("!\\[([^]]*)]\\(([^)]+)\\)");
            java.util.regex.Matcher matcher = pattern.matcher(value);
            StringBuffer buffer = new StringBuffer();
            while (matcher.find()) {
                String alt = matcher.group(1);
                String src = matcher.group(2);
                String caption = alt == null || alt.trim().isEmpty() ? "" : "<figcaption>" + alt + "</figcaption>";
                String safeSrc = src == null ? "" : src.trim().replace(" ", "%20");
                String replacement = "<figure><img src=\"" + safeSrc + "\" alt=\"" + alt + "\">" + caption + "</figure>";
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(buffer);
            return buffer.toString();
        }
    }

    private static String firstMarkdownTitle(String markdown) {
        String[] lines = markdown.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.matches("^#\\s+.+")) {
                return trimmed.replaceFirst("^#\\s+", "").trim();
            }
        }
        return "";
    }

    private static String htmlToText(String html) {
        String cleaned = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|section|article|h[1-6]|li|tr)>", "\n")
                .replaceAll("(?i)<li[^>]*>", "• ");
        CharSequence spanned;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            spanned = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY);
        } else {
            spanned = Html.fromHtml(cleaned);
        }
        return normalizeText(spanned.toString());
    }

    private static String extractHtmlTitle(String html) {
        String h1 = firstRegexGroup(html, "(?is)<h[1-3][^>]*>(.*?)</h[1-3]>");
        if (!h1.isEmpty()) {
            return htmlToText(h1);
        }
        String title = firstRegexGroup(html, "(?is)<title[^>]*>(.*?)</title>");
        return title.isEmpty() ? "" : htmlToText(title);
    }

    private static String normalizeText(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        normalized = normalized.replace('\u00A0', ' ');
        normalized = normalized.replace('\t', ' ');
        normalized = normalized.replaceAll("(?m)[ \\x0B\\f]+$", "");
        normalized = normalized.replaceAll("(?m)^\\s+$", "");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.trim();
    }

    private static String firstNonEmptyLine(String text) {
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }

    private static String firstRegexGroup(String text, String regex) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private static Document parseXml(String xml) throws Exception {
        String withoutDoctype = removeDoctype(xml);
        try {
            return parseXmlStrict(withoutDoctype);
        } catch (Exception exception) {
            return parseXmlStrict(replaceCommonXmlEntities(withoutDoctype));
        }
    }

    private static Document parseXmlStrict(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            factory.setExpandEntityReferences(false);
        } catch (Exception ignored) {
        }
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean enabled) {
        try {
            factory.setFeature(feature, enabled);
        } catch (Exception ignored) {
        }
    }

    private static String removeDoctype(String xml) {
        if (xml == null) {
            return "";
        }
        return xml.replaceFirst("(?is)<!DOCTYPE[^>]*(\\[[\\s\\S]*?])?\\s*>", "");
    }

    private static String replaceCommonXmlEntities(String xml) {
        return xml
                .replace("&nbsp;", " ")
                .replace("&copy;", "(c)")
                .replace("&reg;", "(r)")
                .replace("&ldquo;", "\"")
                .replace("&rdquo;", "\"")
                .replace("&lsquo;", "'")
                .replace("&rsquo;", "'");
    }

    private static String textByTag(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        if (nodes.getLength() == 0 && tag.contains(":")) {
            nodes = document.getElementsByTagName(tag.substring(tag.indexOf(':') + 1));
        }
        if (nodes.getLength() == 0) {
            return "";
        }
        return nodes.item(0).getTextContent().trim();
    }

    private static String textByTag(Element element, String tag) {
        NodeList nodes = element.getElementsByTagName(tag);
        if (nodes.getLength() == 0 && tag.contains(":")) {
            nodes = element.getElementsByTagName(tag.substring(tag.indexOf(':') + 1));
        }
        if (nodes.getLength() == 0) {
            return "";
        }
        return nodes.item(0).getTextContent().trim();
    }

    private static String readZipEntry(ZipFile zip, String path) throws Exception {
        String value = readZipEntryOrNull(zip, path);
        if (value == null) {
            throw new IllegalArgumentException("Missing EPUB entry: " + path);
        }
        return value;
    }

    private static String readZipEntryOrNull(ZipFile zip, String path) throws Exception {
        ZipEntry entry = findZipEntry(zip, path);
        if (entry == null) {
            return null;
        }
        try (InputStream input = zip.getInputStream(entry)) {
            return decodeText(readAllBytes(input));
        }
    }

    private static ZipEntry findZipEntry(ZipFile zip, String path) {
        if (path == null) {
            return null;
        }
        ArrayList<String> candidates = new ArrayList<>();
        addZipCandidate(candidates, path);
        addZipCandidate(candidates, path.replace("%20", " "));
        try {
            addZipCandidate(candidates, URLDecoder.decode(path, "UTF-8"));
        } catch (Exception ignored) {
        }
        if (path.startsWith("/")) {
            addZipCandidate(candidates, path.substring(1));
        }
        for (String candidate : candidates) {
            ZipEntry direct = zip.getEntry(candidate);
            if (direct != null) {
                return direct;
            }
        }
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String normalized = normalizeTocPath(entry.getName());
            for (String candidate : candidates) {
                if (normalized.equals(normalizeTocPath(candidate))) {
                    return entry;
                }
            }
        }
        return null;
    }

    private static File safeExtractTarget(File root, String path) throws Exception {
        if (root == null || path == null || path.trim().isEmpty()) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.contains("../") || normalized.startsWith("..")) {
            return null;
        }
        File target = new File(root, normalized);
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            return null;
        }
        return target;
    }

    private static File findExtractedFile(File root, String path) throws Exception {
        ArrayList<String> candidates = new ArrayList<>();
        addZipCandidate(candidates, path);
        if (path != null) {
            addZipCandidate(candidates, path.replace("%20", " "));
            try {
                addZipCandidate(candidates, URLDecoder.decode(path, "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        for (String candidate : candidates) {
            File file = safeExtractTarget(root, candidate);
            if (file != null && file.exists()) {
                return file;
            }
        }
        return null;
    }

    private static String relativePath(File root, File file) throws Exception {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (filePath.equals(rootPath)) {
            return "";
        }
        if (filePath.startsWith(rootPath + File.separator)) {
            return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
        }
        return file.getName();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        file.delete();
    }

    private static void addZipCandidate(ArrayList<String> candidates, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!candidates.contains(normalized)) {
            candidates.add(normalized);
        }
    }

    private static byte[] readAllBytes(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String decodeText(byte[] bytes) throws Exception {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2
                && (bytes[0] & 0xFF) == 0xFE
                && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        String utf16Guess = guessUtf16WithoutBom(bytes);
        if (!utf16Guess.isEmpty()) {
            return utf16Guess;
        }
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ignored) {
        }
        return bestLegacyChineseDecode(bytes);
    }

    private static String guessUtf16WithoutBom(byte[] bytes) {
        if (bytes.length < 12) {
            return "";
        }
        int evenZeros = 0;
        int oddZeros = 0;
        int sample = Math.min(bytes.length, 2048);
        for (int i = 0; i < sample; i++) {
            if (bytes[i] == 0) {
                if ((i & 1) == 0) {
                    evenZeros++;
                } else {
                    oddZeros++;
                }
            }
        }
        int pairs = Math.max(1, sample / 2);
        if (oddZeros > pairs * 0.35f && evenZeros < pairs * 0.08f) {
            return new String(bytes, StandardCharsets.UTF_16LE);
        }
        if (evenZeros > pairs * 0.35f && oddZeros < pairs * 0.08f) {
            return new String(bytes, StandardCharsets.UTF_16BE);
        }
        return "";
    }

    private static String bestLegacyChineseDecode(byte[] bytes) {
        String[] names = new String[]{"GB18030", "Big5", "windows-1252"};
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (String name : names) {
            try {
                String decoded = new String(bytes, Charset.forName(name));
                int score = textDecodeScore(decoded);
                if (score > bestScore) {
                    bestScore = score;
                    best = decoded;
                }
            } catch (Exception ignored) {
            }
        }
        return best.isEmpty() ? new String(bytes, Charset.forName("GB18030")) : best;
    }

    private static int textDecodeScore(String text) {
        int score = 0;
        int sample = Math.min(text.length(), 4096);
        for (int i = 0; i < sample; i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFD') {
                score -= 40;
            } else if ((ch >= '\u4E00' && ch <= '\u9FFF') || (ch >= '\u3400' && ch <= '\u4DBF')) {
                score += 3;
            } else if ("，。！？；：“”‘’、（）《》".indexOf(ch) >= 0) {
                score += 2;
            } else if (ch == '\n' || ch == '\t' || (ch >= 32 && ch < 127)) {
                score += 1;
            } else if (Character.isISOControl(ch)) {
                score -= 12;
            }
        }
        return score;
    }

    private static String basePath(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String resolveZipPath(String base, String href) {
        String cleanHref = stripFragment(href).replace('\\', '/');
        String combined = base == null || base.isEmpty() ? cleanHref : base + "/" + cleanHref;
        ArrayDeque<String> stack = new ArrayDeque<>();
        for (String part : combined.split("/+")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else {
                stack.addLast(part);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (String part : stack) {
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private static String stripFragment(String href) {
        if (href == null) {
            return "";
        }
        int hash = href.indexOf('#');
        return hash >= 0 ? href.substring(0, hash) : href;
    }

    private static String normalizeTocPath(String path) {
        return stripFragment(path).replace("%20", " ").trim();
    }

    private static String extractQuotedAttribute(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        char quote = trimmed.charAt(0);
        if (quote == '"' || quote == '\'') {
            int end = trimmed.indexOf(quote, 1);
            return end > 1 ? trimmed.substring(1, end) : "";
        }
        int end = trimmed.indexOf(' ');
        int close = trimmed.indexOf('>');
        if (end < 0 || (close >= 0 && close < end)) {
            end = close;
        }
        return end > 0 ? trimmed.substring(0, end) : trimmed;
    }

    private static final class ManifestItem {
        final String path;
        final String mediaType;
        final String properties;

        ManifestItem(String path, String mediaType, String properties) {
            this.path = path;
            this.mediaType = mediaType == null ? "" : mediaType.toLowerCase(Locale.US);
            this.properties = properties == null ? "" : properties.toLowerCase(Locale.US);
        }

        boolean looksReadable() {
            String lower = path.toLowerCase(Locale.US);
            return mediaType.contains("html")
                    || mediaType.contains("xhtml")
                    || lower.endsWith(".xhtml")
                    || lower.endsWith(".html")
                    || lower.endsWith(".htm");
        }

        boolean looksImage() {
            String lower = path.toLowerCase(Locale.US);
            return mediaType.startsWith("image/")
                    || lower.endsWith(".jpg")
                    || lower.endsWith(".jpeg")
                    || lower.endsWith(".png")
                    || lower.endsWith(".gif")
                    || lower.endsWith(".webp");
        }
    }
}
