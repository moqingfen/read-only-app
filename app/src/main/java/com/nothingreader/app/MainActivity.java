package com.nothingreader.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextWatcher;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.Context;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final int REQUEST_IMPORT = 701;
    private static final int MAX_MEMORY_CACHE_TEXT_CHARS = 1_500_000;
    private static final int MAX_MEMORY_CACHE_BOOKS = 2;
    private static final int MAX_SCROLL_MODE_TEXT_CHARS = 700_000;
    private static final int LAZY_PAGINATION_TEXT_CHARS = 700_000;

    private interface PageTurnHandler {
        void turn(int direction);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saveProgressRunnable = this::saveCurrentProgress;

    private FrameLayout root;
    private LibraryStore store;
    private ReaderSettings settings;
    private Palette palette;
    private List<Book> books = new ArrayList<>();
    private final Map<String, ReaderContent> contentCache = new HashMap<>();

    private Book currentBook;
    private ReaderContent currentContent;
    private ScrollView readerScroll;
    private TextView readerText;
    private LinearLayout readerToolbar;
    private LinearLayout readerFooter;
    private TextView progressView;
    private final ArrayList<Integer> pageStarts = new ArrayList<>();
    private int pageIndex;
    private boolean lazyPagination;
    private int lazyContentWidth;
    private int lazyContentHeight;
    private int lazyEstimatedChars;
    private int lazyApproxPageCount;
    private int safeInsetLeft;
    private int safeInsetTop;
    private int safeInsetRight;
    private int safeInsetBottom;
    private float touchDownX;
    private float touchDownY;
    private boolean restoringScroll;
    private boolean readerChromeVisible = true;
    private boolean lastExpandedLayout;
    private String shelfFormatFilter = "ALL";
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pdfDescriptor;
    private ImageView pdfImage;
    private ScrollView pdfScroll;
    private HorizontalScrollView pdfHorizontalScroll;
    private FrameLayout pdfReadingFrame;
    private FrameLayout pdfPageHolder;
    private ScaleGestureDetector pdfScaleDetector;
    private Bitmap pdfBitmap;
    private float pdfZoom = 1.0f;
    private boolean pdfFitHeight;
    private int pdfPageIndex;
    private int pdfPageCount;
    private WebView epubWebView;
    private EpubDocument currentEpubDocument;
    private int epubChapterIndex;
    private WebView markdownWebView;
    private String activeSearchQuery = "";
    private int activeSearchOffset = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new LibraryStore(this);
        settings = ReaderSettings.load(this);
        books = store.loadBooks();
        root = new FrameLayout(this);
        root.setClipToPadding(false);
        enableEdgeToEdge();
        installWindowInsets();
        setContentView(root);
        lastExpandedLayout = isExpandedLayout();
        installLayoutModeWatcher();
        showShelf();
        showOpeningCover();
    }

    private void installWindowInsets() {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets systemBars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                left = systemBars.left;
                top = systemBars.top;
                right = systemBars.right;
                bottom = systemBars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            boolean changed = left != safeInsetLeft
                    || top != safeInsetTop
                    || right != safeInsetRight
                    || bottom != safeInsetBottom;
            safeInsetLeft = left;
            safeInsetTop = top;
            safeInsetRight = right;
            safeInsetBottom = bottom;
            if (changed && root.getChildCount() > 0) {
                view.post(this::renderCurrentSurface);
            }
            return insets;
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            root.post(root::requestApplyInsets);
        }
    }

    private void enableEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(edgeToEdgeFlags());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
    }

    private void renderCurrentSurface() {
        if (currentBook != null) {
            saveCurrentProgress();
            if (isMarkdownBook(currentBook)) {
                if (currentContent != null) {
                    showMarkdownReader(currentBook, currentContent);
                } else {
                    openBook(currentBook);
                }
            } else if (isEpubBook(currentBook)) {
                if (currentEpubDocument != null) {
                    showEpubReader(currentBook, currentEpubDocument);
                } else {
                    showEpubReader(currentBook);
                }
            } else if (isPdfBook(currentBook)) {
                showPdfReader(currentBook);
            } else if (currentContent != null) {
                showReader(currentBook, currentContent);
            } else {
                showShelf();
            }
            return;
        }
        showShelf();
    }

    private void installLayoutModeWatcher() {
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            boolean expanded = isExpandedLayout();
            if (expanded == lastExpandedLayout) {
                return;
            }
            lastExpandedLayout = expanded;
            renderCurrentSurface();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrentProgress();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        lastExpandedLayout = isExpandedLayout();
        renderCurrentSurface();
    }

    @Override
    public void onBackPressed() {
        if (currentBook != null) {
            saveCurrentProgress();
            showShelf();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
        }
        importBook(uri);
    }

    private void showOpeningCover() {
        final FrameLayout cover = new FrameLayout(this);
        cover.setAlpha(0.0f);
        cover.setClickable(true);
        cover.setOnClickListener(view -> dismissOpeningCover(cover));

        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.opening_cover);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(image, matchParent());

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(
                safeInsetLeft + (isExpandedLayout() ? dp(56) : dp(32)),
                safeInsetTop + (isExpandedLayout() ? dp(70) : dp(64)),
                safeInsetRight + dp(24),
                safeInsetBottom
        );
        cover.addView(titleBlock, matchParent());

        TextView brand = text("read only", isExpandedLayout() ? 60 : 44, Color.rgb(17, 17, 17), Typeface.BOLD);
        brand.setIncludeFontPadding(false);
        titleBlock.addView(brand);

        TextView slogan = monoText("nothing but reading", isExpandedLayout() ? 17 : 14, Color.rgb(215, 25, 32));
        titleBlock.addView(slogan);
        setMargins(slogan, dp(3), dp(14), 0, 0);

        TextView formats = monoText("EPUB  TXT  MD  PDF", isExpandedLayout() ? 13 : 12, Color.argb(185, 17, 17, 17));
        titleBlock.addView(formats);
        setMargins(formats, dp(3), dp(22), 0, 0);

        root.addView(cover, matchParent());
        cover.animate().alpha(1.0f).setDuration(220).start();
        handler.postDelayed(() -> dismissOpeningCover(cover), 1700);
    }

    private void dismissOpeningCover(View cover) {
        if (cover.getParent() == null) {
            return;
        }
        cover.animate().alpha(0.0f).setDuration(260).withEndAction(() -> {
            if (cover.getParent() instanceof ViewGroup) {
                ((ViewGroup) cover.getParent()).removeView(cover);
            }
        }).start();
    }

    private void showShelf() {
        closePdfRenderer();
        closeEpubWebView();
        closeMarkdownWebView();
        currentBook = null;
        currentContent = null;
        readerScroll = null;
        readerText = null;
        readerToolbar = null;
        readerFooter = null;
        readerChromeVisible = true;
        lazyPagination = false;
        refreshPalette();
        applySystemChrome(palette.background);
        root.setBackgroundColor(palette.background);
        root.removeAllViews();

        boolean expanded = isExpandedLayout();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(expanded ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        int horizontalPadding = expanded ? dp(24) : dp(18);
        int verticalPadding = expanded ? dp(20) : dp(14);
        page.setPadding(
                safeInsetLeft + horizontalPadding,
                safeInsetTop + verticalPadding,
                safeInsetRight + horizontalPadding,
                safeInsetBottom + verticalPadding
        );
        page.setBackgroundColor(palette.background);
        root.addView(page, matchParent());

        LinearLayout chrome = page;
        LinearLayout contentColumn = page;
        if (expanded) {
            chrome = new LinearLayout(this);
            chrome.setOrientation(LinearLayout.VERTICAL);
            chrome.setPadding(0, 0, dp(18), 0);
            page.addView(chrome, new LinearLayout.LayoutParams(shelfRailWidthPx(), ViewGroup.LayoutParams.MATCH_PARENT));

            View divider = new View(this);
            divider.setBackgroundColor(palette.hairline);
            page.addView(divider, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

            contentColumn = new LinearLayout(this);
            contentColumn.setOrientation(LinearLayout.VERTICAL);
            contentColumn.setPadding(dp(22), 0, 0, 0);
            page.addView(contentColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        chrome.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(expanded ? 66 : 60)));

        TextView title = text("read only", expanded ? 29 : 27, palette.text, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView theme = iconButton(themeGlyph(), "主题");
        theme.setOnClickListener(view -> {
            cycleTheme();
            showShelf();
        });
        header.addView(theme);
        setMargins(theme, dp(8), 0, 0, 0);

        TextView importButton = iconButton("+", "导入");
        importButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        importButton.setOnClickListener(view -> launchImport());
        header.addView(importButton);
        setMargins(importButton, dp(8), 0, 0, 0);

        View searchBar = shelfSearchBar();
        chrome.addView(searchBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        setMargins(searchBar, 0, dp(6), 0, dp(8));

        TextView dots = monoText("................", 18, palette.accent);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        chrome.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        LinearLayout stats = new LinearLayout(this);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        chrome.addView(stats, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        stats.addView(pill(books.size() + " 本", false));
        TextView recent = pill(recentSummary(), false);
        stats.addView(recent);
        setMargins(recent, dp(8), 0, 0, 0);

        LinearLayout controls = shelfControls();
        chrome.addView(controls);
        setMargins(controls, 0, dp(8), 0, dp(8));

        LinearLayout formats = shelfFormatFilters();
        chrome.addView(formats);
        setMargins(formats, 0, 0, 0, expanded ? dp(18) : dp(10));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        contentColumn.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (books.isEmpty()) {
            LinearLayout empty = card();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(36), dp(20), dp(36));
            TextView emptyText = text("书架为空", 24, palette.text, Typeface.BOLD);
            emptyText.setGravity(Gravity.CENTER);
            empty.addView(emptyText);
            TextView action = pill("+ 导入 EPUB / TXT / MD / PDF", true);
            action.setGravity(Gravity.CENTER);
            action.setOnClickListener(view -> launchImport());
            empty.addView(action);
            setMargins(action, 0, dp(18), 0, 0);
            list.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }

        ArrayList<Book> sorted = sortedBooks();
        Book latest = latestBook(sorted);
        if (latest != null) {
            list.addView(continuePanel(latest));
        }
        if (settings.shelfGrid || expanded) {
            addBookGrid(list, sorted, expanded ? 4 : 3);
        } else {
            for (Book book : sorted) {
                list.addView(bookCard(book));
            }
            list.addView(importListCard());
        }
    }

    private View shelfSearchBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), 0, dp(10), 0);
        bar.setBackground(border(palette.surface, palette.hairline, 24, 1));
        bar.setClickable(true);
        bar.setOnClickListener(view -> showShelfSearchDialog());

        TextView icon = text("⌕", 20, palette.muted, Typeface.BOLD);
        icon.setIncludeFontPadding(false);
        bar.addView(icon);

        TextView hint = text("搜索书名、作者、格式", 15, palette.muted, Typeface.NORMAL);
        hint.setSingleLine(true);
        bar.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        setMargins(hint, dp(10), 0, dp(10), 0);

        TextView local = pill("本地", false);
        bar.addView(local);
        return bar;
    }

    private LinearLayout shelfFormatFilters() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addFormatFilter(row, "ALL", "全部 " + books.size());
        addFormatFilter(row, "EPUB", "EPUB " + countFormat("EPUB"));
        addFormatFilter(row, "TXT", "TXT " + countFormat("TXT"));
        addFormatFilter(row, "MD", "MD " + countFormat("MD"));
        addFormatFilter(row, "PDF", "PDF " + countFormat("PDF"));
        return row;
    }

    private void addFormatFilter(LinearLayout row, String format, String label) {
        TextView item = pill(label, format.equals(shelfFormatFilter));
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        item.setOnClickListener(view -> {
            shelfFormatFilter = format;
            showShelf();
        });
        row.addView(item);
        if (row.getChildCount() > 1) {
            setMargins(item, dp(5), 0, 0, 0);
        }
    }

    private int countFormat(String format) {
        int count = 0;
        for (Book book : books) {
            if (format.equals(book.format)) {
                count++;
            }
        }
        return count;
    }

    private LinearLayout shelfControls() {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        TextView recent = pill("默认", "recent".equals(settings.shelfSort));
        TextView updated = pill("更新", "added".equals(settings.shelfSort));
        TextView title = pill("书名", "title".equals(settings.shelfSort));
        TextView progress = pill("进度", "progress".equals(settings.shelfSort));
        TextView grid = pill(settings.shelfGrid ? "封面" : "详情", settings.shelfGrid);

        recent.setOnClickListener(view -> changeShelfSort("recent"));
        updated.setOnClickListener(view -> changeShelfSort("added"));
        title.setOnClickListener(view -> changeShelfSort("title"));
        progress.setOnClickListener(view -> changeShelfSort("progress"));
        grid.setOnClickListener(view -> {
            settings.shelfGrid = !settings.shelfGrid;
            settings.save(this);
            showShelf();
        });

        controls.addView(recent);
        controls.addView(updated);
        controls.addView(progress);
        controls.addView(title);
        Space spacer = new Space(this);
        controls.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        controls.addView(grid);
        setMargins(updated, dp(6), 0, 0, 0);
        setMargins(progress, dp(6), 0, 0, 0);
        setMargins(title, dp(8), 0, 0, 0);
        return controls;
    }

    private void changeShelfSort(String sort) {
        settings.shelfSort = sort;
        settings.save(this);
        showShelf();
    }

    private ArrayList<Book> sortedBooks() {
        ArrayList<Book> sorted = new ArrayList<>();
        for (Book book : books) {
            if ("ALL".equals(shelfFormatFilter) || shelfFormatFilter.equals(book.format)) {
                sorted.add(book);
            }
        }
        if ("title".equals(settings.shelfSort)) {
            Collections.sort(sorted, (left, right) -> nonEmpty(left.title, "").compareToIgnoreCase(nonEmpty(right.title, "")));
        } else if ("progress".equals(settings.shelfSort)) {
            Collections.sort(sorted, (left, right) -> Float.compare(clamp(right.progress), clamp(left.progress)));
        } else if ("added".equals(settings.shelfSort)) {
            Collections.sort(sorted, (left, right) -> Long.compare(right.addedAt, left.addedAt));
        } else {
            Collections.sort(sorted, (left, right) -> Long.compare(right.lastOpenedAt, left.lastOpenedAt));
        }
        return sorted;
    }

    private void addBookGrid(LinearLayout list, ArrayList<Book> sorted, int columns) {
        if (sorted.isEmpty()) {
            LinearLayout empty = card();
            empty.setGravity(Gravity.CENTER);
            empty.addView(text("这个筛选下没有书", 18, palette.text, Typeface.BOLD));
            TextView reset = pill("查看全部", true);
            reset.setOnClickListener(view -> {
                shelfFormatFilter = "ALL";
                showShelf();
            });
            empty.addView(reset);
            setMargins(reset, 0, dp(14), 0, 0);
            list.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        LinearLayout row = null;
        int total = sorted.size() + 1;
        for (int i = 0; i < total; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            View card = i < sorted.size() ? bookTile(sorted.get(i)) : importTile();
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            params.setMargins(i % columns == 0 ? 0 : dp(8), 0, i % columns == columns - 1 ? 0 : dp(8), dp(12));
            row.addView(card, params);
        }
        int remainder = total % columns;
        if (row != null && remainder > 0) {
            for (int i = remainder; i < columns; i++) {
                Space space = new Space(this);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 1, 1);
                params.setMargins(dp(8), 0, 0, 0);
                row.addView(space, params);
            }
        }
    }

    private View continuePanel(Book book) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(14), dp(14));
        panel.setBackground(border(palette.text, palette.text, 6, 1));
        panel.setClickable(true);
        panel.setOnClickListener(view -> openBook(book));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        panel.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView eyebrow = monoText("continue reading", 11, palette.readerBackground);
        copy.addView(eyebrow);

        TextView title = text(nonEmpty(book.title, "未命名"), 18, palette.readerBackground, Typeface.BOLD);
        title.setMaxLines(1);
        copy.addView(title);
        setMargins(title, 0, dp(6), 0, 0);

        String meta = book.format + " / " + Math.round(clamp(book.progress) * 100) + "%";
        if (!book.bookmarks.isEmpty()) {
            meta += " / " + book.bookmarks.size() + " 书签";
        }
        TextView sub = text(meta, 12, Color.argb(210, Color.red(palette.readerBackground), Color.green(palette.readerBackground), Color.blue(palette.readerBackground)), Typeface.NORMAL);
        copy.addView(sub);
        setMargins(sub, 0, dp(5), 0, 0);

        TextView action = text("›", 26, palette.readerBackground, Typeface.BOLD);
        action.setGravity(Gravity.CENTER);
        action.setIncludeFontPadding(false);
        panel.addView(action, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(14));
        panel.setLayoutParams(params);
        return panel;
    }

    private Book latestBook(List<Book> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return Collections.max(candidates, Comparator.comparingLong(book -> book.lastOpenedAt));
    }

    private View bookTile(Book book) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setClickable(true);
        tile.setOnClickListener(view -> openBook(book));
        tile.setOnLongClickListener(view -> {
            showBookActions(book);
            return true;
        });

        FrameLayout cover = new FrameLayout(this);
        cover.setPadding(dp(10), dp(10), dp(10), dp(10));
        cover.setBackground(border(palette.surface, palette.hairline, 4, 1));
        tile.addView(cover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(isExpandedLayout() ? 170 : 146)));

        ImageView artwork = new ImageView(this);
        applyBookArtwork(artwork, book);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setAlpha("dark".equals(settings.theme) ? 0.72f : 1.0f);
        cover.addView(artwork, matchParent());

        LinearLayout titlePlate = new LinearLayout(this);
        titlePlate.setOrientation(LinearLayout.VERTICAL);
        titlePlate.setPadding(dp(10), dp(9), dp(10), dp(9));
        titlePlate.setBackground(border(Color.argb(226, 247, 247, 242), Color.TRANSPARENT, 4, 0));
        FrameLayout.LayoutParams titlePlateParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
        titlePlateParams.setMargins(dp(8), dp(8), dp(8), 0);
        cover.addView(titlePlate, titlePlateParams);

        TextView title = text(nonEmpty(book.title, "未命名"), isExpandedLayout() ? 16 : 14, Color.rgb(17, 17, 17), Typeface.BOLD);
        title.setMaxLines(3);
        title.setIncludeFontPadding(false);
        titlePlate.addView(title);

        String author = book.author == null ? "" : book.author.trim();
        if (!author.isEmpty()) {
            TextView authorView = text(author, 10, Color.rgb(91, 91, 85), Typeface.NORMAL);
            authorView.setMaxLines(1);
            titlePlate.addView(authorView);
            setMargins(authorView, 0, dp(5), 0, 0);
        }

        TextView format = pill(book.format, true);
        format.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        FrameLayout.LayoutParams formatParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28), Gravity.BOTTOM | Gravity.RIGHT);
        formatParams.setMargins(0, 0, dp(8), dp(8));
        cover.addView(format, formatParams);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(Math.round(clamp(book.progress) * 1000));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            progress.getProgressDrawable().setColorFilter(palette.accent, PorterDuff.Mode.SRC_IN);
            progress.getProgressDrawable().setAlpha(210);
        }
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM);
        progressParams.setMargins(dp(8), 0, dp(66), dp(10));
        cover.addView(progress, progressParams);

        TextView below = text(nonEmpty(book.title, "未命名"), 13, palette.text, Typeface.BOLD);
        below.setMaxLines(2);
        tile.addView(below);
        setMargins(below, 0, dp(8), 0, 0);

        String detail = Math.round(clamp(book.progress) * 100) + "%";
        if (book.author != null && !book.author.trim().isEmpty()) {
            detail += " / " + book.author.trim();
        }
        TextView meta = text(detail, 11, palette.muted, Typeface.NORMAL);
        meta.setMaxLines(1);
        tile.addView(meta);
        setMargins(meta, 0, dp(3), 0, 0);
        return tile;
    }

    private View importTile() {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setClickable(true);
        tile.setOnClickListener(view -> launchImport());

        FrameLayout cover = new FrameLayout(this);
        cover.setBackground(border(palette.surface, palette.hairline, 4, 1));
        tile.addView(cover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(isExpandedLayout() ? 170 : 146)));

        TextView plus = text("+", 38, palette.muted, Typeface.NORMAL);
        plus.setGravity(Gravity.CENTER);
        plus.setIncludeFontPadding(false);
        cover.addView(plus, matchParent());

        TextView label = text("导入本地书", 13, palette.text, Typeface.BOLD);
        label.setMaxLines(1);
        tile.addView(label);
        setMargins(label, 0, dp(8), 0, 0);

        TextView meta = text("EPUB / TXT / MD / PDF", 10, palette.muted, Typeface.NORMAL);
        meta.setMaxLines(1);
        tile.addView(meta);
        setMargins(meta, 0, dp(3), 0, 0);
        return tile;
    }

    private View importListCard() {
        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setClickable(true);
        card.setOnClickListener(view -> launchImport());
        card.addView(text("+ 导入本地书", 18, palette.text, Typeface.BOLD));
        TextView meta = text("支持 EPUB / TXT / Markdown / PDF", 13, palette.muted, Typeface.NORMAL);
        card.addView(meta);
        setMargins(meta, 0, dp(6), 0, 0);
        return card;
    }

    private int coverColor(Book book) {
        int[] colors = new int[]{
                palette.surface,
                Color.rgb(239, 242, 239),
                Color.rgb(244, 239, 232),
                Color.rgb(238, 240, 246),
                Color.rgb(246, 244, 238)
        };
        int hash = nonEmpty(book.title, book.fileName).hashCode();
        return colors[Math.abs(hash) % colors.length];
    }

    private int formatCoverResource(String format) {
        if ("TXT".equals(format)) {
            return R.drawable.cover_txt;
        }
        if ("MD".equals(format)) {
            return R.drawable.cover_md;
        }
        if ("PDF".equals(format)) {
            return R.drawable.cover_pdf;
        }
        return R.drawable.cover_epub;
    }

    private void applyBookArtwork(ImageView artwork, Book book) {
        String coverPath = book == null || book.coverPath == null ? "" : book.coverPath.trim();
        File cover = coverPath.isEmpty() ? null : new File(coverPath);
        if (cover != null && cover.exists() && cover.length() > 0) {
            artwork.setImageURI(Uri.fromFile(cover));
            return;
        }
        artwork.setImageResource(formatCoverResource(book == null ? null : book.format));
    }

    private View bookCard(Book book) {
        LinearLayout card = card();
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setClickable(true);
        card.setOnClickListener(view -> openBook(book));
        card.setOnLongClickListener(view -> {
            showBookActions(book);
            return true;
        });

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout cover = new FrameLayout(this);
        cover.setBackground(border(palette.surface, palette.hairline, 4, 1));
        row.addView(cover, new LinearLayout.LayoutParams(dp(74), dp(98)));

        ImageView artwork = new ImageView(this);
        applyBookArtwork(artwork, book);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(artwork, matchParent());

        TextView format = pill(book.format, true);
        format.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        FrameLayout.LayoutParams formatParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24), Gravity.BOTTOM | Gravity.RIGHT);
        formatParams.setMargins(0, 0, dp(5), dp(5));
        cover.addView(format, formatParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        setMargins(labels, dp(14), 0, 0, 0);

        TextView title = text(nonEmpty(book.title, "未命名"), 21, palette.text, Typeface.BOLD);
        title.setMaxLines(2);
        labels.addView(title);

        String meta = book.format;
        if (book.author != null && !book.author.trim().isEmpty()) {
            meta += " / " + book.author.trim();
        }
        TextView subtitle = text(meta, 13, palette.muted, Typeface.NORMAL);
        labels.addView(subtitle);
        setMargins(subtitle, 0, dp(6), 0, 0);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(Math.round(clamp(book.progress) * 1000));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            progress.getProgressDrawable().setColorFilter(palette.accent, PorterDuff.Mode.SRC_IN);
            progress.getProgressDrawable().setAlpha(210);
        }
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        progressParams.setMargins(0, dp(14), 0, 0);
        labels.addView(progress, progressParams);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        labels.addView(bottom);
        setMargins(bottom, 0, dp(12), 0, 0);

        bottom.addView(text(Math.round(clamp(book.progress) * 100) + "%", 13, palette.text, Typeface.BOLD));
        TextView opened = text("最近 " + formatTime(book.lastOpenedAt), 13, palette.muted, Typeface.NORMAL);
        bottom.addView(opened);
        setMargins(opened, dp(12), 0, 0, 0);
        if (!book.bookmarks.isEmpty()) {
            TextView marks = text(book.bookmarks.size() + " 书签", 13, palette.muted, Typeface.NORMAL);
            bottom.addView(marks);
            setMargins(marks, dp(12), 0, 0, 0);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, dp(2));
        card.setLayoutParams(params);
        return card;
    }

    private void showShelfSearchDialog() {
        Dialog dialog = baseDialog("搜索书架");
        LinearLayout body = dialogBody(dialog);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("书名、作者、格式");
        input.setTextColor(palette.text);
        input.setHintTextColor(palette.muted);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(border(palette.readerBackground, palette.hairline, 4, 1));
        body.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        body.addView(results);
        setMargins(results, 0, dp(10), 0, 0);

        renderShelfSearchResults("", results, dialog);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderShelfSearchResults(s.toString(), results, dialog);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        showDialog(dialog);
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 180);
    }

    private void renderShelfSearchResults(String query, LinearLayout results, Dialog dialog) {
        results.removeAllViews();
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int count = 0;
        ArrayList<Book> sorted = new ArrayList<>(books);
        Collections.sort(sorted, (left, right) -> Long.compare(right.lastOpenedAt, left.lastOpenedAt));
        for (Book book : sorted) {
            String haystack = (nonEmpty(book.title, "") + " "
                    + nonEmpty(book.author, "") + " "
                    + nonEmpty(book.format, "") + " "
                    + nonEmpty(book.fileName, "")).toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !haystack.contains(normalized)) {
                continue;
            }
            TextView row = dialogRow(nonEmpty(book.title, "未命名"), book.format + " / " + Math.round(clamp(book.progress) * 100) + "% / 最近 " + formatTime(book.lastOpenedAt));
            row.setOnClickListener(view -> {
                dialog.dismiss();
                openBook(book);
            });
            results.addView(row);
            count++;
            if (count >= 20) {
                break;
            }
        }
        if (count == 0) {
            results.addView(dialogRow("没有找到", "换个关键词试试"));
        }
    }

    private void launchImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/epub+zip",
                "application/pdf",
                "application/octet-stream",
                "text/plain",
                "text/markdown",
                "text/x-markdown"
        });
        try {
            startActivityForResult(Intent.createChooser(intent, "导入"), REQUEST_IMPORT);
        } catch (ActivityNotFoundException exception) {
            toast("无法打开文件选择器。");
        }
    }

    private void importBook(Uri uri) {
        showBusy("导入中");
        new Thread(() -> {
            try {
                Book book = store.importBook(uri);
                books.add(book);
                store.saveBooks(books);
                runOnUiThread(() -> {
                    toast("已导入");
                    showShelf();
                    openBook(book);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    showShelf();
                    toast(nonEmpty(exception.getMessage(), "导入失败"));
                });
            }
        }).start();
    }

    private void openBook(Book book) {
        if (isEpubBook(book)) {
            showEpubReader(book);
            return;
        }
        if (isPdfBook(book)) {
            showPdfReader(book);
            return;
        }
        ReaderContent memoryContent = contentCache.get(book.id);
        if (memoryContent != null && memoryContent.parserVersion >= ReaderContent.CACHE_VERSION) {
            if (isMarkdownBook(book)) {
                showMarkdownReader(book, memoryContent);
            } else {
                showReader(book, memoryContent);
            }
            return;
        }
        showBusy("打开中");
        new Thread(() -> {
            try {
                ReaderContent content = store.loadCachedContent(book);
                if (content == null || content.parserVersion < ReaderContent.CACHE_VERSION) {
                    content = DocumentParser.parse(new File(book.localPath), book.format, book.fileName);
                    store.saveCachedContent(book, content);
                }
                rememberContent(book, content);
                ReaderContent resolvedContent = content;
                runOnUiThread(() -> {
                    if (isMarkdownBook(book)) {
                        showMarkdownReader(book, resolvedContent);
                    } else {
                        showReader(book, resolvedContent);
                    }
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    showShelf();
                    toast(nonEmpty(exception.getMessage(), "无法打开"));
                });
            } catch (OutOfMemoryError error) {
                contentCache.clear();
                System.gc();
                runOnUiThread(() -> {
                    showShelf();
                    toast("文件过大，已释放内存，请重试或换较小文件");
                });
            }
        }).start();
    }

    private void rememberContent(Book book, ReaderContent content) {
        if (book == null || content == null || book.id == null) {
            return;
        }
        int textLength = content.fullText == null ? 0 : content.fullText.length();
        if (textLength > MAX_MEMORY_CACHE_TEXT_CHARS) {
            contentCache.clear();
            return;
        }
        contentCache.put(book.id, content);
        while (contentCache.size() > MAX_MEMORY_CACHE_BOOKS) {
            String firstKey = contentCache.keySet().iterator().next();
            contentCache.remove(firstKey);
        }
    }

    private void showEpubReader(Book book) {
        closePdfRenderer();
        closeMarkdownWebView();
        currentBook = book;
        currentContent = null;
        readerScroll = null;
        readerText = null;
        pageStarts.clear();
        lazyPagination = false;
        showBusy("打开 EPUB");
        new Thread(() -> {
            try {
                File epubDir = epubRenderDir(book);
                EpubDocument document = DocumentParser.prepareEpubWeb(new File(book.localPath), epubDir, book.fileName);
                runOnUiThread(() -> showEpubReader(book, document));
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    showShelf();
                    toast(nonEmpty(exception.getMessage(), "无法打开 EPUB"));
                });
            } catch (OutOfMemoryError error) {
                System.gc();
                runOnUiThread(() -> {
                    showShelf();
                    toast("EPUB 文件过大，已释放内存");
                });
            }
        }).start();
    }

    private void showEpubReader(Book book, EpubDocument document) {
        closeEpubWebView();
        currentBook = book;
        currentEpubDocument = document;
        int count = Math.max(1, document.chapters.size());
        float scaledProgress = clamp(book.progress) * count;
        epubChapterIndex = Math.max(0, Math.min(count - 1, (int) Math.floor(scaledProgress)));
        float restoreEpubScroll = clamp(scaledProgress - epubChapterIndex);
        if (book.locator != null && book.locator.startsWith("epub:")) {
            try {
                String[] parts = book.locator.split(":");
                if (parts.length >= 2) {
                    epubChapterIndex = Math.max(0, Math.min(count - 1, Integer.parseInt(parts[1])));
                }
                if (parts.length >= 3) {
                    restoreEpubScroll = clamp(Float.parseFloat(parts[2]));
                }
            } catch (Exception ignored) {
            }
        }
        book.lastOpenedAt = System.currentTimeMillis();
        store.saveBooks(books);
        refreshPalette();
        applySystemChrome(palette.readerBackground);
        root.setBackgroundColor(palette.readerBackground);
        root.removeAllViews();
        readerToolbar = null;
        readerFooter = null;
        readerChromeVisible = true;

        boolean expanded = isExpandedLayout();
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(safeInsetLeft, safeInsetTop, safeInsetRight, safeInsetBottom);
        shell.setBackgroundColor(palette.readerBackground);
        root.addView(shell, matchParent());

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(expanded ? dp(18) : dp(12), dp(8), expanded ? dp(18) : dp(12), dp(8));
        toolbar.setBackgroundColor(palette.readerBackground);
        shell.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        readerToolbar = toolbar;

        TextView back = iconButton("‹", "返回");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        back.setOnClickListener(view -> {
            saveCurrentProgress();
            showShelf();
        });
        toolbar.addView(back);

        TextView title = text(nonEmpty(document.title, book.title), 17, palette.text, Typeface.BOLD);
        title.setSingleLine(true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        setMargins(title, dp(10), 0, dp(10), 0);

        TextView toc = iconButton("☰", "目录");
        toc.setOnClickListener(view -> showEpubTocDialog());
        toolbar.addView(toc);
        setMargins(toc, dp(6), 0, 0, 0);

        TextView bookmark = iconButton("☆", "书签列表");
        bookmark.setOnClickListener(view -> showBookmarksDialog());
        toolbar.addView(bookmark);
        setMargins(bookmark, dp(6), 0, 0, 0);

        TextView type = iconButton("Aa", "阅读设置");
        type.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        type.setOnClickListener(view -> showSettingsDialog());
        toolbar.addView(type);
        setMargins(type, dp(6), 0, 0, 0);

        FrameLayout readingFrame = new FrameLayout(this);
        shell.addView(readingFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        epubWebView = new WebView(this);
        epubWebView.setBackgroundColor(palette.readerBackground);
        epubWebView.setVerticalScrollBarEnabled(false);
        epubWebView.setHorizontalScrollBarEnabled(false);
        epubWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings webSettings = epubWebView.getSettings();
        webSettings.setJavaScriptEnabled(false);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(false);
        webSettings.setDefaultTextEncodingName("utf-8");
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);
        epubWebView.setWebViewClient(new WebViewClient());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            epubWebView.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                updateProgressLabel();
                handler.removeCallbacks(saveProgressRunnable);
                handler.postDelayed(saveProgressRunnable, 500);
            });
        }
        readingFrame.addView(epubWebView, matchParent());
        attachDocumentSurfaceGesture(epubWebView, this::epubPageTurn);
        addEpubTurnZones(readingFrame);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(expanded ? dp(28) : dp(18), dp(2), expanded ? dp(28) : dp(18), dp(10));
        footer.setBackgroundColor(palette.readerBackground);
        shell.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        readerFooter = footer;

        TextView previous = smallIconButton("‹", "上一章/上一屏");
        previous.setOnClickListener(view -> epubPageTurn(-1));
        footer.addView(previous);
        Space leftSpace = new Space(this);
        footer.addView(leftSpace, new LinearLayout.LayoutParams(0, 1, 1));
        progressView = text("", 12, palette.muted, Typeface.BOLD);
        footer.addView(progressView);
        Space rightSpace = new Space(this);
        footer.addView(rightSpace, new LinearLayout.LayoutParams(0, 1, 1));
        TextView next = smallIconButton("›", "下一屏/下一章");
        next.setOnClickListener(view -> epubPageTurn(1));
        footer.addView(next);

        loadEpubChapter(epubChapterIndex);
        restoreEpubWebProgress(restoreEpubScroll, 360);
    }

    private void addEpubTurnZones(FrameLayout readingFrame) {
        View previous = new View(this);
        previous.setBackgroundColor(Color.TRANSPARENT);
        previous.setClickable(true);
        attachDocumentEdgeGesture(previous, this::epubPageTurn, -1);
        readingFrame.addView(previous, new FrameLayout.LayoutParams(dp(isExpandedLayout() ? 220 : 120), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT));

        View next = new View(this);
        next.setBackgroundColor(Color.TRANSPARENT);
        next.setClickable(true);
        attachDocumentEdgeGesture(next, this::epubPageTurn, 1);
        readingFrame.addView(next, new FrameLayout.LayoutParams(dp(isExpandedLayout() ? 260 : 140), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT));
    }

    private void loadEpubChapter(int index) {
        if (currentEpubDocument == null || epubWebView == null || currentEpubDocument.chapters.isEmpty()) {
            return;
        }
        epubChapterIndex = Math.max(0, Math.min(currentEpubDocument.chapters.size() - 1, index));
        EpubChapterRef chapter = currentEpubDocument.chapters.get(epubChapterIndex);
        try {
            File chapterFile = new File(currentEpubDocument.rootDir, chapter.path);
            String html = DocumentParser.readPlainFile(chapterFile);
            String styled = injectEpubStyle(html);
            File base = chapterFile.getParentFile();
            String baseUrl = (base == null ? currentEpubDocument.rootDir : base).toURI().toString();
            epubWebView.loadDataWithBaseURL(baseUrl, styled, "text/html", "UTF-8", null);
            epubWebView.postDelayed(() -> {
                if (epubWebView != null) {
                    epubWebView.scrollTo(0, 0);
                    updateProgressLabel();
                    saveCurrentProgress();
                }
            }, 180);
        } catch (Exception exception) {
            toast(nonEmpty(exception.getMessage(), "章节打开失败"));
        }
    }

    private String injectEpubStyle(String html) {
        String css = "<style>"
                + "html,body{background:" + colorCss(palette.readerBackground) + ";color:" + colorCss(palette.text) + ";}"
                + "body{margin:0;padding:" + (isExpandedLayout() ? 34 : 22) + "px " + (isExpandedLayout() ? 76 : 26) + "px " + (isExpandedLayout() ? 34 : 24) + "px;font-family:sans-serif;font-size:" + settings.fontSp + "px;line-height:" + settings.lineMultiplier + ";word-break:break-word;}"
                + "p{margin:0 0 1.05em 0;text-align:justify;}h1,h2,h3{line-height:1.25;margin:1.2em 0 .7em 0;font-weight:700;}h1{font-size:1.55em;}h2{font-size:1.35em;}h3{font-size:1.18em;}"
                + "img,svg,video{max-width:100%;height:auto;display:block;margin:1.1em auto;}figure{margin:1.1em 0;text-align:center;}figcaption{font-size:.82em;color:" + colorCss(palette.muted) + ";}"
                + "blockquote{border-left:3px solid " + colorCss(palette.accent) + ";margin:1em 0;padding:.2em 0 .2em 1em;color:" + colorCss(palette.muted) + ";}"
                + "ul,ol{padding-left:1.4em;}li{margin:.35em 0;}table{max-width:100%;border-collapse:collapse;}td,th{border:1px solid " + colorCss(palette.hairline) + ";padding:.35em;}"
                + "a{color:" + colorCss(palette.accent) + ";text-decoration:none;}"
                + "</style>";
        String value = html == null ? "" : html;
        if (value.toLowerCase(Locale.US).contains("</head>")) {
            return value.replaceFirst("(?i)</head>", css + "</head>");
        }
        return "<html><head>" + css + "</head><body>" + value + "</body></html>";
    }

    private void epubPageTurn(int direction) {
        if (epubWebView == null || currentEpubDocument == null || currentEpubDocument.chapters.isEmpty()) {
            return;
        }
        int height = Math.max(1, epubWebView.getHeight());
        int step = Math.max(dp(160), height);
        int max = Math.max(0, Math.round(epubWebView.getContentHeight() * epubWebView.getScale()) - height);
        int y = epubWebView.getScrollY();
        if (direction > 0) {
            if (y + step < max) {
                epubWebView.scrollTo(0, Math.min(max, y + step));
                epubWebView.postDelayed(this::saveCurrentProgress, 260);
                return;
            }
            if (epubChapterIndex + 1 < currentEpubDocument.chapters.size()) {
                loadEpubChapter(epubChapterIndex + 1);
            }
            return;
        }
        if (y > dp(20)) {
            epubWebView.scrollTo(0, Math.max(0, y - step));
            epubWebView.postDelayed(this::saveCurrentProgress, 260);
            return;
        }
        if (epubChapterIndex > 0) {
            loadEpubChapter(epubChapterIndex - 1);
            epubWebView.postDelayed(() -> {
                if (epubWebView != null) {
                    epubWebView.scrollTo(0, Math.max(0, Math.round(epubWebView.getContentHeight() * epubWebView.getScale()) - epubWebView.getHeight()));
                }
            }, 280);
        }
    }

    private void showEpubTocDialog() {
        if (currentEpubDocument == null) {
            return;
        }
        Dialog dialog = baseDialog("目录");
        LinearLayout body = dialogBody(dialog);
        for (int i = 0; i < currentEpubDocument.chapters.size(); i++) {
            EpubChapterRef chapter = currentEpubDocument.chapters.get(i);
            int target = i;
            TextView row = dialogRow(nonEmpty(chapter.title, "章节 " + (i + 1)), chapter.path);
            row.setOnClickListener(view -> {
                dialog.dismiss();
                loadEpubChapter(target);
            });
            body.addView(row);
        }
        showDialog(dialog);
    }

    private File epubRenderDir(Book book) {
        return new File(new File(getFilesDir(), "epub-web"), book.id == null ? "current" : book.id);
    }

    private void closeEpubWebView() {
        if (epubWebView != null) {
            try {
                epubWebView.stopLoading();
                epubWebView.loadUrl("about:blank");
                epubWebView.destroy();
            } catch (Exception ignored) {
            }
            epubWebView = null;
        }
        currentEpubDocument = null;
        epubChapterIndex = 0;
    }

    private void showMarkdownReader(Book book, ReaderContent content) {
        closePdfRenderer();
        closeEpubWebView();
        closeMarkdownWebView();
        currentBook = book;
        currentContent = content;
        activeSearchQuery = "";
        activeSearchOffset = -1;
        readerScroll = null;
        readerText = null;
        pageStarts.clear();
        lazyPagination = false;
        book.lastOpenedAt = System.currentTimeMillis();
        store.saveBooks(books);
        refreshPalette();
        applySystemChrome(palette.readerBackground);
        root.setBackgroundColor(palette.readerBackground);
        root.removeAllViews();
        readerToolbar = null;
        readerFooter = null;
        readerChromeVisible = true;

        boolean expanded = isExpandedLayout();
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(safeInsetLeft, safeInsetTop, safeInsetRight, safeInsetBottom);
        shell.setBackgroundColor(palette.readerBackground);
        root.addView(shell, matchParent());

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(expanded ? dp(18) : dp(12), dp(8), expanded ? dp(18) : dp(12), dp(8));
        toolbar.setBackgroundColor(palette.readerBackground);
        shell.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        readerToolbar = toolbar;

        TextView back = iconButton("‹", "返回");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        back.setOnClickListener(view -> {
            saveCurrentProgress();
            showShelf();
        });
        toolbar.addView(back);

        TextView title = text(nonEmpty(content.title, book.title), 17, palette.text, Typeface.BOLD);
        title.setSingleLine(true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        setMargins(title, dp(10), 0, dp(10), 0);

        TextView toc = iconButton("☰", "目录");
        toc.setOnClickListener(view -> showTocDialog());
        toolbar.addView(toc);
        setMargins(toc, dp(6), 0, 0, 0);

        TextView search = iconButton("⌕", "搜索");
        search.setOnClickListener(view -> showSearchDialog());
        toolbar.addView(search);
        setMargins(search, dp(6), 0, 0, 0);

        TextView bookmark = iconButton("☆", "书签列表");
        bookmark.setOnClickListener(view -> showBookmarksDialog());
        toolbar.addView(bookmark);
        setMargins(bookmark, dp(6), 0, 0, 0);

        TextView type = iconButton("Aa", "阅读设置");
        type.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        type.setOnClickListener(view -> showSettingsDialog());
        toolbar.addView(type);
        setMargins(type, dp(6), 0, 0, 0);

        FrameLayout readingFrame = new FrameLayout(this);
        shell.addView(readingFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        markdownWebView = new WebView(this);
        markdownWebView.setBackgroundColor(palette.readerBackground);
        markdownWebView.setVerticalScrollBarEnabled(false);
        markdownWebView.setHorizontalScrollBarEnabled(false);
        markdownWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings webSettings = markdownWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(false);
        webSettings.setDefaultTextEncodingName("utf-8");
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);
        markdownWebView.setWebViewClient(new WebViewClient());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            markdownWebView.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                updateProgressLabel();
                handler.removeCallbacks(saveProgressRunnable);
                handler.postDelayed(saveProgressRunnable, 500);
            });
        }
        readingFrame.addView(markdownWebView, matchParent());
        attachDocumentSurfaceGesture(markdownWebView, this::markdownPageTurn);
        addMarkdownTurnZones(readingFrame);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(expanded ? dp(28) : dp(18), dp(2), expanded ? dp(28) : dp(18), dp(10));
        footer.setBackgroundColor(palette.readerBackground);
        shell.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        readerFooter = footer;

        TextView previous = smallIconButton("‹", "上一屏");
        previous.setOnClickListener(view -> markdownPageTurn(-1));
        footer.addView(previous);
        Space leftSpace = new Space(this);
        footer.addView(leftSpace, new LinearLayout.LayoutParams(0, 1, 1));
        progressView = text("", 12, palette.muted, Typeface.BOLD);
        footer.addView(progressView);
        Space rightSpace = new Space(this);
        footer.addView(rightSpace, new LinearLayout.LayoutParams(0, 1, 1));
        TextView next = smallIconButton("›", "下一屏");
        next.setOnClickListener(view -> markdownPageTurn(1));
        footer.addView(next);

        loadMarkdownDocument(book, content, book.progress);
    }

    private void loadMarkdownDocument(Book book, ReaderContent content, float restoreProgress) {
        String bookId = book.id;
        new Thread(() -> {
            try {
                File file = new File(book.localPath);
                String markdown = DocumentParser.readPlainFile(file);
                String body = DocumentParser.markdownToHtml(markdown);
                String html = markdownHtmlDocument(body);
                String baseUrl = file.getParentFile() == null ? file.toURI().toString() : file.getParentFile().toURI().toString();
                runOnUiThread(() -> {
                    if (currentBook == null || !nonEmpty(currentBook.id, "").equals(nonEmpty(bookId, "")) || markdownWebView == null) {
                        return;
                    }
                    markdownWebView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null);
                    restoreMarkdownWebProgress(restoreProgress, 360);
                    updateProgressLabel();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    if (currentBook != null && isMarkdownBook(currentBook)) {
                        toast(nonEmpty(exception.getMessage(), "Markdown 打开失败"));
                    }
                });
            }
        }).start();
    }

    private String markdownHtmlDocument(String body) {
        int horizontalPadding = isExpandedLayout() ? 74 : 26;
        int verticalPadding = isExpandedLayout() ? 34 : 24;
        String css = "<style>"
                + "html,body{background:" + colorCss(palette.readerBackground) + ";color:" + colorCss(palette.text) + ";}"
                + "body{margin:0;padding:" + verticalPadding + "px " + horizontalPadding + "px " + (verticalPadding + 8) + "px;font-family:sans-serif;font-size:" + settings.fontSp + "px;line-height:" + settings.lineMultiplier + ";word-break:break-word;}"
                + "main{max-width:880px;margin:0 auto;}"
                + "p{margin:0 0 1.05em 0;text-align:justify;}h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.25em 0 .65em;font-weight:700;}h1{font-size:1.7em;}h2{font-size:1.42em;}h3{font-size:1.22em;}h4,h5,h6{font-size:1.08em;}"
                + "img{max-width:100%;height:auto;display:block;margin:1.1em auto;}figure{margin:1.1em 0;text-align:center;}figcaption{font-size:.82em;color:" + colorCss(palette.muted) + ";margin-top:.45em;}"
                + "blockquote{border-left:3px solid " + colorCss(palette.accent) + ";margin:1em 0;padding:.15em 0 .15em 1em;color:" + colorCss(palette.muted) + ";}"
                + "pre{background:" + colorCss(markdownCodeBackground()) + ";border:1px solid " + colorCss(palette.hairline) + ";border-radius:6px;padding:1em;overflow:auto;line-height:1.45;}code{font-family:monospace;background:" + colorCss(markdownCodeBackground()) + ";border-radius:4px;padding:.08em .28em;}pre code{background:transparent;padding:0;}"
                + "ul,ol{padding-left:1.45em;margin:.4em 0 1em;}li{margin:.36em 0;}"
                + "table{width:100%;border-collapse:collapse;margin:1em 0;display:block;overflow-x:auto;}th,td{border:1px solid " + colorCss(palette.hairline) + ";padding:.48em .55em;text-align:left;}th{font-weight:700;background:" + colorCss(markdownCodeBackground()) + ";}"
                + "hr{border:0;border-top:1px solid " + colorCss(palette.hairline) + ";margin:1.35em 0;}a{color:" + colorCss(palette.accent) + ";text-decoration:none;}::selection{background:" + colorCss(Color.argb(96, 205, 47, 47)) + ";}"
                + "</style>";
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" + css + "</head><body><main>" + body + "</main></body></html>";
    }

    private int markdownCodeBackground() {
        if ("dark".equals(settings.theme)) {
            return Color.rgb(35, 35, 34);
        }
        if ("ink".equals(settings.theme)) {
            return Color.rgb(234, 234, 230);
        }
        return Color.rgb(244, 244, 239);
    }

    private void addMarkdownTurnZones(FrameLayout readingFrame) {
        View previous = new View(this);
        previous.setBackgroundColor(Color.TRANSPARENT);
        previous.setClickable(true);
        attachDocumentEdgeGesture(previous, this::markdownPageTurn, -1);
        readingFrame.addView(previous, new FrameLayout.LayoutParams(dp(isExpandedLayout() ? 220 : 120), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT));

        View next = new View(this);
        next.setBackgroundColor(Color.TRANSPARENT);
        next.setClickable(true);
        attachDocumentEdgeGesture(next, this::markdownPageTurn, 1);
        readingFrame.addView(next, new FrameLayout.LayoutParams(dp(isExpandedLayout() ? 260 : 140), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT));
    }

    private void attachDocumentEdgeGesture(View view, PageTurnHandler handler, int direction) {
        view.setOnTouchListener((target, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                touchDownX = event.getX();
                touchDownY = event.getY();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - touchDownX;
                float dy = event.getY() - touchDownY;
                if (Math.abs(dx) > dp(46) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    handler.turn(dx < 0 ? 1 : -1);
                    return true;
                }
                if (Math.abs(dx) < dp(18) && Math.abs(dy) < dp(18)) {
                    handler.turn(direction);
                    return true;
                }
            }
            return true;
        });
    }

    private void attachDocumentSurfaceGesture(View view, PageTurnHandler handler) {
        view.setOnTouchListener((target, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                touchDownX = event.getX();
                touchDownY = event.getY();
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - touchDownX;
                float dy = event.getY() - touchDownY;
                if (Math.abs(dx) > dp(46) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    handler.turn(dx < 0 ? 1 : -1);
                    return true;
                }
                if (Math.abs(dx) < dp(18) && Math.abs(dy) < dp(18)) {
                    turnOrToggleByHorizontalZone(target.getWidth(), event.getX(), handler);
                    return true;
                }
            }
            return false;
        });
    }

    private void turnOrToggleByHorizontalZone(int width, float x, PageTurnHandler handler) {
        int safeWidth = Math.max(1, width);
        if (x < safeWidth * 0.34f) {
            handler.turn(-1);
        } else if (x > safeWidth * 0.66f) {
            handler.turn(1);
        } else {
            toggleReaderChrome();
        }
    }

    private void markdownPageTurn(int direction) {
        if (markdownWebView == null) {
            return;
        }
        int height = Math.max(1, markdownWebView.getHeight());
        int step = Math.max(dp(160), height);
        int max = Math.max(0, Math.round(markdownWebView.getContentHeight() * markdownWebView.getScale()) - height);
        int y = markdownWebView.getScrollY();
        markdownWebView.scrollTo(0, Math.max(0, Math.min(max, y + direction * step)));
        markdownWebView.postDelayed(this::saveCurrentProgress, 260);
    }

    private void scrollMarkdownToAnchor(String anchor) {
        if (markdownWebView == null || anchor == null || anchor.trim().isEmpty()) {
            return;
        }
        String escaped = anchor.replace("\\", "\\\\").replace("'", "\\'");
        markdownWebView.evaluateJavascript("(function(){var el=document.getElementById('" + escaped + "'); if(el){el.scrollIntoView({block:'start'}); return true;} return false;})()", null);
        markdownWebView.postDelayed(this::saveCurrentProgress, 260);
    }

    private float markdownWebProgress() {
        if (markdownWebView == null) {
            return 0.0f;
        }
        int height = Math.max(1, markdownWebView.getHeight());
        int range = Math.max(1, Math.round(markdownWebView.getContentHeight() * markdownWebView.getScale()) - height);
        return clamp(markdownWebView.getScrollY() / (float) range);
    }

    private void restoreMarkdownWebProgress(float progress, int delayMillis) {
        if (markdownWebView == null) {
            return;
        }
        float safeProgress = clamp(progress);
        markdownWebView.postDelayed(() -> {
            if (markdownWebView == null) {
                return;
            }
            int height = Math.max(1, markdownWebView.getHeight());
            int range = Math.max(0, Math.round(markdownWebView.getContentHeight() * markdownWebView.getScale()) - height);
            markdownWebView.scrollTo(0, Math.round(safeProgress * range));
            updateProgressLabel();
            saveCurrentProgress();
        }, delayMillis);
    }

    private void closeMarkdownWebView() {
        if (markdownWebView != null) {
            try {
                markdownWebView.stopLoading();
                markdownWebView.loadUrl("about:blank");
                markdownWebView.destroy();
            } catch (Exception ignored) {
            }
            markdownWebView = null;
        }
    }

    private void showPdfReader(Book book) {
        closeEpubWebView();
        closeMarkdownWebView();
        currentBook = book;
        currentContent = null;
        readerScroll = null;
        readerText = null;
        pageStarts.clear();
        lazyPagination = false;
        refreshPalette();
        applySystemChrome(palette.readerBackground);
        root.setBackgroundColor(palette.readerBackground);
        root.removeAllViews();
        readerToolbar = null;
        readerFooter = null;
        readerChromeVisible = true;

        try {
            openPdfRenderer(book);
        } catch (Exception exception) {
            showShelf();
            toast(nonEmpty(exception.getMessage(), "无法打开 PDF"));
            return;
        }
        if (pdfPageCount <= 0) {
            showShelf();
            toast("PDF 没有可阅读页面");
            return;
        }

        pdfPageIndex = Math.max(0, Math.min(pdfPageCount - 1, Math.round(clamp(book.progress) * Math.max(0, pdfPageCount - 1))));
        book.lastOpenedAt = System.currentTimeMillis();
        store.saveBooks(books);

        boolean expanded = isExpandedLayout();
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(safeInsetLeft, safeInsetTop, safeInsetRight, safeInsetBottom);
        shell.setBackgroundColor(palette.readerBackground);
        root.addView(shell, matchParent());

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(expanded ? dp(18) : dp(12), dp(8), expanded ? dp(18) : dp(12), dp(8));
        toolbar.setBackgroundColor(palette.readerBackground);
        shell.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        readerToolbar = toolbar;

        TextView back = iconButton("‹", "返回");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        back.setOnClickListener(view -> {
            saveCurrentProgress();
            showShelf();
        });
        toolbar.addView(back);

        TextView title = text(nonEmpty(book.title, "PDF"), 17, palette.text, Typeface.BOLD);
        title.setSingleLine(true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        setMargins(title, dp(10), 0, dp(10), 0);

        TextView format = iconButton("PDF", "PDF");
        format.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        toolbar.addView(format);
        setMargins(format, dp(6), 0, 0, 0);

        TextView fit = iconButton(pdfFitHeight ? "高" : "宽", "PDF 适配宽度/高度");
        fit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        fit.setOnClickListener(view -> {
            pdfFitHeight = !pdfFitHeight;
            pdfZoom = 1.0f;
            fit.setText(pdfFitHeight ? "高" : "宽");
            renderPdfPage();
        });
        toolbar.addView(fit);
        setMargins(fit, dp(6), 0, 0, 0);

        TextView bookmark = iconButton("☆", "书签列表");
        bookmark.setOnClickListener(view -> showBookmarksDialog());
        toolbar.addView(bookmark);
        setMargins(bookmark, dp(6), 0, 0, 0);

        FrameLayout readingFrame = new FrameLayout(this);
        shell.addView(readingFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        pdfReadingFrame = readingFrame;
        pdfScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                pdfZoom = clampPdfZoom(pdfZoom * detector.getScaleFactor());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                renderPdfPage();
            }
        });

        pdfHorizontalScroll = new HorizontalScrollView(this);
        pdfHorizontalScroll.setFillViewport(true);
        pdfHorizontalScroll.setHorizontalScrollBarEnabled(false);
        pdfHorizontalScroll.setClipToPadding(false);
        pdfHorizontalScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        pdfHorizontalScroll.setBackgroundColor(palette.readerBackground);
        readingFrame.addView(pdfHorizontalScroll, matchParent());

        pdfScroll = new ScrollView(this);
        pdfScroll.setFillViewport(true);
        pdfScroll.setClipToPadding(false);
        pdfScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        pdfScroll.setBackgroundColor(palette.readerBackground);
        attachPdfGesture(pdfScroll);
        pdfHorizontalScroll.addView(pdfScroll, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pdfPageHolder = new FrameLayout(this);
        pdfPageHolder.setPadding(expanded ? dp(72) : dp(18), dp(18), expanded ? dp(72) : dp(18), dp(22));
        pdfScroll.addView(pdfPageHolder, new ScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pdfImage = new ImageView(this);
        pdfImage.setAdjustViewBounds(true);
        pdfImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pdfImage.setBackgroundColor(Color.WHITE);
        pdfPageHolder.addView(pdfImage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(expanded ? dp(28) : dp(18), dp(2), expanded ? dp(28) : dp(18), dp(10));
        footer.setBackgroundColor(palette.readerBackground);
        shell.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        readerFooter = footer;

        TextView previous = smallIconButton("‹", "上一页");
        previous.setOnClickListener(view -> pdfPageTurn(-1));
        footer.addView(previous);

        Space leftSpace = new Space(this);
        footer.addView(leftSpace, new LinearLayout.LayoutParams(0, 1, 1));
        progressView = text("", 12, palette.muted, Typeface.BOLD);
        footer.addView(progressView);
        Space rightSpace = new Space(this);
        footer.addView(rightSpace, new LinearLayout.LayoutParams(0, 1, 1));

        TextView next = smallIconButton("›", "下一页");
        next.setOnClickListener(view -> pdfPageTurn(1));
        footer.addView(next);

        pdfImage.post(this::renderPdfPage);
        updateProgressLabel();
    }

    private void openPdfRenderer(Book book) throws Exception {
        closePdfRenderer();
        File file = new File(book.localPath);
        pdfDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        pdfRenderer = new PdfRenderer(pdfDescriptor);
        pdfPageCount = pdfRenderer.getPageCount();
    }

    private void renderPdfPage() {
        if (pdfRenderer == null || pdfImage == null || pdfPageCount <= 0) {
            return;
        }
        pdfPageIndex = Math.max(0, Math.min(pdfPageIndex, pdfPageCount - 1));
        PdfRenderer.Page page = null;
        try {
            page = pdfRenderer.openPage(pdfPageIndex);
            int frameWidth = pdfReadingFrame == null ? 0 : pdfReadingFrame.getWidth();
            int frameHeight = pdfReadingFrame == null ? 0 : pdfReadingFrame.getHeight();
            if (frameWidth <= 0 && pdfScroll != null) {
                frameWidth = pdfScroll.getWidth();
            }
            if (frameHeight <= 0 && pdfScroll != null) {
                frameHeight = pdfScroll.getHeight();
            }
            int horizontalPadding = pdfPageHolder == null ? dp(isExpandedLayout() ? 144 : 36) : pdfPageHolder.getPaddingLeft() + pdfPageHolder.getPaddingRight();
            int verticalPadding = pdfPageHolder == null ? dp(40) : pdfPageHolder.getPaddingTop() + pdfPageHolder.getPaddingBottom();
            int availableWidth = Math.max(dp(260), frameWidth - horizontalPadding);
            int availableHeight = Math.max(dp(360), frameHeight - verticalPadding);
            float baseScale = pdfFitHeight
                    ? availableHeight / (float) Math.max(1, page.getHeight())
                    : availableWidth / (float) Math.max(1, page.getWidth());
            float scale = Math.max(0.12f, baseScale * pdfZoom);
            int targetWidth = Math.max(dp(220), Math.round(page.getWidth() * scale));
            int targetHeight = Math.max(1, Math.round(page.getHeight() * scale));
            long pixels = (long) targetWidth * (long) targetHeight;
            long maxPixels = 18L * 1024L * 1024L;
            if (pixels > maxPixels) {
                float shrink = (float) Math.sqrt(maxPixels / (double) pixels);
                targetWidth = Math.max(dp(220), Math.round(targetWidth * shrink));
                scale = targetWidth / (float) Math.max(1, page.getWidth());
                targetHeight = Math.max(1, Math.round(page.getHeight() * scale));
            }
            recyclePdfBitmap();
            Bitmap bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            Rect clip = new Rect(0, 0, targetWidth, targetHeight);
            page.render(bitmap, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            pdfBitmap = bitmap;
            pdfImage.setImageBitmap(bitmap);
            ViewGroup.LayoutParams imageParams = pdfImage.getLayoutParams();
            imageParams.width = targetWidth;
            imageParams.height = targetHeight;
            pdfImage.setLayoutParams(imageParams);
            if (pdfPageHolder != null) {
                ViewGroup.LayoutParams holderParams = pdfPageHolder.getLayoutParams();
                holderParams.width = targetWidth + pdfPageHolder.getPaddingLeft() + pdfPageHolder.getPaddingRight();
                holderParams.height = targetHeight + pdfPageHolder.getPaddingTop() + pdfPageHolder.getPaddingBottom();
                pdfPageHolder.setLayoutParams(holderParams);
            }
            if (pdfScroll != null) {
                pdfScroll.scrollTo(0, 0);
            }
            if (pdfHorizontalScroll != null) {
                pdfHorizontalScroll.scrollTo(0, 0);
            }
            updateProgressLabel();
        } catch (OutOfMemoryError error) {
            recyclePdfBitmap();
            System.gc();
            toast("PDF 页面过大，渲染失败");
        } catch (Exception exception) {
            toast(nonEmpty(exception.getMessage(), "PDF 页面渲染失败"));
        } finally {
            if (page != null) {
                page.close();
            }
        }
    }

    private void pdfPageTurn(int direction) {
        if (pdfRenderer == null || pdfPageCount <= 0) {
            return;
        }
        int target = Math.max(0, Math.min(pdfPageCount - 1, pdfPageIndex + direction));
        if (target == pdfPageIndex) {
            return;
        }
        pdfPageIndex = target;
        renderPdfPage();
        saveCurrentProgress();
    }

    private void attachPdfGesture(View view) {
        view.setOnTouchListener((target, event) -> {
            if (!isPdfBook(currentBook)) {
                return false;
            }
            if (pdfScaleDetector != null) {
                pdfScaleDetector.onTouchEvent(event);
                if (event.getPointerCount() > 1 || pdfScaleDetector.isInProgress()) {
                    return true;
                }
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                touchDownX = event.getX();
                touchDownY = event.getY();
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - touchDownX;
                float dy = event.getY() - touchDownY;
                if (Math.abs(dx) > dp(46) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    pdfPageTurn(dx < 0 ? 1 : -1);
                    return true;
                }
                if (Math.abs(dx) < dp(18) && Math.abs(dy) < dp(18)) {
                    turnOrToggleByHorizontalZone(target.getWidth(), event.getX(), this::pdfPageTurn);
                    return true;
                }
            }
            return false;
        });
    }

    private void closePdfRenderer() {
        recyclePdfBitmap();
        if (pdfRenderer != null) {
            try {
                pdfRenderer.close();
            } catch (Exception ignored) {
            }
            pdfRenderer = null;
        }
        if (pdfDescriptor != null) {
            try {
                pdfDescriptor.close();
            } catch (Exception ignored) {
            }
            pdfDescriptor = null;
        }
        pdfImage = null;
        pdfScroll = null;
        pdfHorizontalScroll = null;
        pdfReadingFrame = null;
        pdfPageHolder = null;
        pdfScaleDetector = null;
        pdfZoom = 1.0f;
        pdfFitHeight = false;
        pdfPageCount = 0;
        pdfPageIndex = 0;
    }

    private void recyclePdfBitmap() {
        if (pdfImage != null) {
            pdfImage.setImageDrawable(null);
        }
        if (pdfBitmap != null && !pdfBitmap.isRecycled()) {
            pdfBitmap.recycle();
        }
        pdfBitmap = null;
    }

    private void showReader(Book book, ReaderContent content) {
        closePdfRenderer();
        closeEpubWebView();
        closeMarkdownWebView();
        int restoreOffset = -1;
        if (settings.pageMode
                && currentBook != null
                && currentContent != null
                && book.id != null
                && book.id.equals(currentBook.id)
                && !pageStarts.isEmpty()) {
            restoreOffset = currentVisibleOffset();
        }
        currentBook = book;
        currentContent = content;
        activeSearchQuery = "";
        activeSearchOffset = -1;
        if (isLargeContent(content) && !settings.pageMode) {
            settings.pageMode = true;
            settings.save(this);
            toast("大文件已自动使用翻页模式");
        }
        book.lastOpenedAt = System.currentTimeMillis();
        store.saveBooks(books);
        refreshPalette();
        applySystemChrome(palette.readerBackground);
        root.setBackgroundColor(palette.readerBackground);
        root.removeAllViews();
        readerToolbar = null;
        readerFooter = null;
        readerChromeVisible = true;

        boolean expanded = isExpandedLayout();
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(safeInsetLeft, safeInsetTop, safeInsetRight, safeInsetBottom);
        shell.setBackgroundColor(palette.readerBackground);
        root.addView(shell, matchParent());

        LinearLayout page = shell;
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(palette.readerBackground);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(expanded ? dp(18) : dp(12), dp(8), expanded ? dp(18) : dp(12), dp(8));
        toolbar.setBackgroundColor(palette.readerBackground);
        page.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        readerToolbar = toolbar;

        TextView back = iconButton("‹", "返回");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        back.setOnClickListener(view -> {
            saveCurrentProgress();
            showShelf();
        });
        toolbar.addView(back);

        TextView title = text(nonEmpty(content.title, book.title), 17, palette.text, Typeface.BOLD);
        title.setSingleLine(true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        setMargins(title, dp(10), 0, dp(10), 0);

        TextView pageMode = iconButton(settings.pageMode ? "页" : "滚", "翻页模式");
        pageMode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        pageMode.setOnClickListener(view -> togglePageMode());
        toolbar.addView(pageMode);
        setMargins(pageMode, dp(6), 0, 0, 0);

        TextView toc = iconButton("☰", "目录");
        toc.setOnClickListener(view -> showTocDialog());
        toolbar.addView(toc);
        setMargins(toc, dp(6), 0, 0, 0);

        TextView search = iconButton("⌕", "搜索");
        search.setOnClickListener(view -> showSearchDialog());
        toolbar.addView(search);
        setMargins(search, dp(6), 0, 0, 0);

        TextView bookmark = iconButton("☆", "书签列表");
        bookmark.setOnClickListener(view -> showBookmarksDialog());
        toolbar.addView(bookmark);
        setMargins(bookmark, dp(6), 0, 0, 0);

        TextView type = iconButton("Aa", "阅读设置");
        type.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        type.setOnClickListener(view -> showSettingsDialog());
        toolbar.addView(type);
        setMargins(type, dp(6), 0, 0, 0);

        FrameLayout readingFrame = new FrameLayout(this);
        page.addView(readingFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        if (settings.pageMode) {
            readerScroll = null;
            readerText = text("", settings.fontSp, palette.text, Typeface.NORMAL);
            readerText.setPadding(expanded ? dp(76) : dp(26), expanded ? dp(32) : dp(20), expanded ? dp(76) : dp(26), expanded ? dp(28) : dp(22));
            readerText.setGravity(Gravity.TOP);
            readerText.setTextIsSelectable(false);
            applyReaderTextStyle();
            readingFrame.addView(readerText, matchParent());
            addPageTurnZones(readingFrame);
        } else {
            readerScroll = new ScrollView(this);
            readerScroll.setFillViewport(false);
            readerScroll.setClipToPadding(false);
            readerScroll.setPadding(0, 0, 0, dp(18));
            readerScroll.setVerticalScrollBarEnabled(true);
            readerScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            readingFrame.addView(readerScroll, matchParent());

            readerText = text(content.fullText, settings.fontSp, palette.text, Typeface.NORMAL);
            readerText.setPadding(expanded ? dp(30) : dp(24), expanded ? dp(26) : dp(20), expanded ? dp(30) : dp(24), expanded ? dp(56) : dp(44));
            readerText.setTextIsSelectable(true);
            applyReaderTextStyle();

            FrameLayout textFrame = new FrameLayout(this);
            textFrame.setPadding(expanded ? dp(22) : 0, 0, expanded ? dp(22) : 0, 0);
            readerScroll.addView(textFrame, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            textFrame.addView(readerText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        }

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(expanded ? dp(28) : dp(18), dp(2), expanded ? dp(28) : dp(18), dp(10));
        footer.setBackgroundColor(palette.readerBackground);
        page.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, settings.pageMode ? dp(52) : dp(36)));
        readerFooter = footer;

        progressView = text("0%", 12, palette.muted, Typeface.BOLD);
        if (settings.pageMode) {
            TextView previous = smallIconButton("‹", "上一页");
            previous.setOnClickListener(view -> pageTurn(-1));
            footer.addView(previous);
            Space leftSpace = new Space(this);
            footer.addView(leftSpace, new LinearLayout.LayoutParams(0, 1, 1));
            footer.addView(progressView);
            Space rightSpace = new Space(this);
            footer.addView(rightSpace, new LinearLayout.LayoutParams(0, 1, 1));
            TextView next = smallIconButton("›", "下一页");
            next.setOnClickListener(view -> pageTurn(1));
            footer.addView(next);
        } else {
            footer.addView(progressView);
            Space spacer = new Space(this);
            footer.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
            footer.addView(text(content.chapters.size() + " 章", 12, palette.muted, Typeface.NORMAL));
        }

        if (settings.pageMode) {
            int finalRestoreOffset = restoreOffset;
            readerText.post(() -> buildPagesForCurrentLayout(book.progress, finalRestoreOffset));
        } else {
            restoringScroll = true;
            readerText.post(() -> {
                scrollToProgress(book.progress, false);
                restoringScroll = false;
                updateProgressLabel();
            });
            readerScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
                if (!restoringScroll) {
                    updateProgressLabel();
                    handler.removeCallbacks(saveProgressRunnable);
                    handler.postDelayed(saveProgressRunnable, 500);
                }
            });
        }
    }

    private void togglePageMode() {
        if (settings.pageMode && isLargeContent(currentContent)) {
            toast("大文件暂不支持滚动全文模式");
            return;
        }
        settings.pageMode = !settings.pageMode;
        settings.save(this);
        if (currentBook != null && currentContent != null) {
            saveCurrentProgress();
            showReader(currentBook, currentContent);
        }
    }

    private void toggleReaderChrome() {
        if (readerToolbar == null || readerFooter == null) {
            return;
        }
        int restoreOffset = currentVisibleOffset();
        float epubProgress = isEpubBook(currentBook) && currentEpubDocument != null ? epubWebProgress() : -1.0f;
        float markdownProgress = isMarkdownBook(currentBook) ? markdownWebProgress() : -1.0f;
        readerChromeVisible = !readerChromeVisible;
        int visibility = readerChromeVisible ? View.VISIBLE : View.GONE;
        readerToolbar.setVisibility(visibility);
        readerFooter.setVisibility(visibility);
        if (settings.pageMode && currentBook != null && currentContent != null && readerText != null) {
            readerText.postDelayed(() -> buildPagesForCurrentLayout(currentBook.progress, restoreOffset), 80);
        } else if (epubProgress >= 0.0f) {
            restoreEpubWebProgress(epubProgress, 90);
        } else if (markdownProgress >= 0.0f) {
            restoreMarkdownWebProgress(markdownProgress, 90);
        } else if (isPdfBook(currentBook) && pdfImage != null) {
            pdfImage.postDelayed(this::renderPdfPage, 80);
        } else {
            updateProgressLabel();
        }
    }

    private void addPageTurnZones(FrameLayout readingFrame) {
        View previous = new View(this);
        previous.setBackgroundColor(Color.TRANSPARENT);
        previous.setClickable(true);
        attachPageGesture(previous, -1);
        FrameLayout.LayoutParams previousParams = new FrameLayout.LayoutParams(dp(isExpandedLayout() ? 220 : 148), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT);
        readingFrame.addView(previous, previousParams);

        View next = new View(this);
        next.setBackgroundColor(Color.TRANSPARENT);
        next.setClickable(true);
        attachPageGesture(next, 1);
        FrameLayout.LayoutParams nextParams = new FrameLayout.LayoutParams(dp(isExpandedLayout() ? 260 : 172), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT);
        readingFrame.addView(next, nextParams);
        attachPageGesture(readerText, 0);
    }

    private void pageTurn(int direction) {
        if (settings.pageMode) {
            if (pageStarts.isEmpty()) {
                return;
            }
            if (lazyPagination) {
                if (direction > 0) {
                    ensureNextLazyPage();
                } else if (direction < 0) {
                    ensurePreviousLazyPage();
                }
            }
            int target = Math.max(0, Math.min(pageStarts.size() - 1, pageIndex + direction));
            if (target != pageIndex) {
                pageIndex = target;
                renderCurrentPage();
                saveCurrentProgress();
            }
            return;
        }
        if (readerScroll == null || readerScroll.getChildCount() == 0) {
            return;
        }
        View child = readerScroll.getChildAt(0);
        int range = Math.max(0, child.getHeight() - readerScroll.getHeight());
        int step = Math.max(dp(160), Math.round(readerScroll.getHeight() * 0.88f));
        int target = Math.max(0, Math.min(range, readerScroll.getScrollY() + direction * step));
        readerScroll.smoothScrollTo(0, target);
        handler.postDelayed(() -> {
            updateProgressLabel();
            saveCurrentProgress();
        }, 260);
    }

    private void attachPageGesture(View view, int tapDirection) {
        view.setOnTouchListener((target, event) -> {
            if (!settings.pageMode) {
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                touchDownX = event.getX();
                touchDownY = event.getY();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - touchDownX;
                float dy = event.getY() - touchDownY;
                if (Math.abs(dx) > dp(46) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    pageTurn(dx < 0 ? 1 : -1);
                    return true;
                }
                if (Math.abs(dx) < dp(18) && Math.abs(dy) < dp(18)) {
                    if (tapDirection != 0) {
                        pageTurn(tapDirection);
                        return true;
                    }
                    int width = Math.max(1, target.getWidth());
                    float x = event.getX();
                    if (x < width * 0.34f) {
                        pageTurn(-1);
                    } else if (x > width * 0.66f) {
                        pageTurn(1);
                    } else {
                        toggleReaderChrome();
                    }
                    return true;
                }
            }
            return true;
        });
    }

    private void buildPagesForCurrentLayout(float progress, int restoreOffset) {
        pageStarts.clear();
        lazyPagination = false;
        if (currentContent == null || readerText == null) {
            return;
        }
        String text = currentContent.fullText == null ? "" : currentContent.fullText;
        pageStarts.add(0);
        if (text.isEmpty()) {
            pageIndex = 0;
            renderCurrentPage();
            return;
        }
        int contentWidth = Math.max(dp(240), readerText.getWidth() - readerText.getPaddingLeft() - readerText.getPaddingRight());
        int rawContentHeight = readerText.getHeight() - readerText.getPaddingTop() - readerText.getPaddingBottom();
        int contentHeight = Math.max(dp(260), rawContentHeight - pageBottomGuardPx());
        int estimatedChars = estimatedPageChars(contentWidth, contentHeight);
        if (shouldUseLazyPagination(text, estimatedChars)) {
            lazyPagination = true;
            lazyContentWidth = contentWidth;
            lazyContentHeight = contentHeight;
            lazyEstimatedChars = estimatedChars;
            lazyApproxPageCount = Math.max(1, (int) Math.ceil(text.length() / (double) Math.max(1, estimatedChars)));
            int start = restoreOffset >= 0
                    ? restoreOffset
                    : Math.round(clamp(progress) * Math.max(0, text.length() - 1));
            start = Math.max(0, Math.min(start, Math.max(0, text.length() - 1)));
            pageStarts.clear();
            pageStarts.add(skipPageBreakWhitespace(text, start));
            pageIndex = 0;
            renderCurrentPage();
            return;
        }

        int start = 0;
        while (start < text.length()) {
            int next = measuredPageBreak(text, start, contentWidth, contentHeight, estimatedChars);
            if (next <= start) {
                next = Math.min(text.length(), start + Math.max(80, estimatedChars));
            }
            start = skipPageBreakWhitespace(text, next);
            if (start < text.length()) {
                pageStarts.add(start);
            }
        }
        if (restoreOffset >= 0) {
            pageIndex = pageIndexForOffset(restoreOffset);
        } else {
            pageIndex = Math.max(0, Math.min(pageStarts.size() - 1, Math.round(clamp(progress) * Math.max(0, pageStarts.size() - 1))));
        }
        renderCurrentPage();
    }

    private boolean shouldUseLazyPagination(String text, int estimatedChars) {
        if (text == null) {
            return false;
        }
        if (text.length() > LAZY_PAGINATION_TEXT_CHARS) {
            return true;
        }
        int estimatedPages = (int) Math.ceil(text.length() / (double) Math.max(1, estimatedChars));
        return estimatedPages > 900;
    }

    private void ensureNextLazyPage() {
        if (!lazyPagination || currentContent == null || currentContent.fullText == null || pageStarts.isEmpty()) {
            return;
        }
        String text = currentContent.fullText;
        int start = pageStarts.get(Math.max(0, Math.min(pageIndex, pageStarts.size() - 1)));
        int next = measuredPageBreak(text, start, lazyContentWidth, lazyContentHeight, lazyEstimatedChars);
        next = skipPageBreakWhitespace(text, next);
        if (next > start && next < text.length()) {
            int insertAt = pageIndex + 1;
            if (insertAt >= pageStarts.size()) {
                pageStarts.add(next);
            } else if (!pageStarts.get(insertAt).equals(next)) {
                pageStarts.add(insertAt, next);
            }
        }
    }

    private void ensurePreviousLazyPage() {
        if (!lazyPagination || currentContent == null || currentContent.fullText == null || pageStarts.isEmpty()) {
            return;
        }
        if (pageIndex > 0) {
            return;
        }
        String text = currentContent.fullText;
        int currentStart = pageStarts.get(0);
        if (currentStart <= 0) {
            return;
        }
        int previous = previousLazyPageStart(text, currentStart);
        if (previous >= 0 && previous < currentStart) {
            pageStarts.add(0, previous);
            pageIndex = 1;
        }
    }

    private int previousLazyPageStart(String text, int currentStart) {
        int probe = Math.max(0, currentStart - Math.max(lazyEstimatedChars * 3, lazyEstimatedChars + 360));
        int lastStart = probe;
        int start = probe;
        int guard = 0;
        while (start < currentStart && guard < 24) {
            int next = measuredPageBreak(text, start, lazyContentWidth, lazyContentHeight, lazyEstimatedChars);
            next = skipPageBreakWhitespace(text, next);
            if (next <= start || next >= currentStart) {
                return lastStart;
            }
            lastStart = start;
            start = next;
            guard++;
        }
        return lastStart;
    }

    private int estimatedPageChars(int contentWidth, int contentHeight) {
        Paint.FontMetricsInt metrics = readerText.getPaint().getFontMetricsInt();
        float baseLineHeight = Math.max(1.0f, metrics.descent - metrics.ascent + metrics.leading);
        float lineHeight = Math.max(1.0f, baseLineHeight * settings.lineMultiplier);
        float averageCharWidth = Math.max(1.0f, Math.max(readerText.getPaint().measureText("汉"), readerText.getPaint().measureText("W") * 0.76f));
        int charsPerLine = Math.max(10, Math.round(contentWidth / averageCharWidth));
        int linesPerPage = Math.max(4, (int) Math.floor(contentHeight / lineHeight));
        return Math.max(180, Math.round(charsPerLine * linesPerPage * 1.2f));
    }

    private int pageBottomGuardPx() {
        if (readerText == null) {
            return dp(12);
        }
        Paint.FontMetricsInt metrics = readerText.getPaint().getFontMetricsInt();
        int baseLineHeight = Math.max(1, metrics.descent - metrics.ascent + metrics.leading);
        int lineHeight = Math.max(1, Math.round(baseLineHeight * settings.lineMultiplier));
        return Math.max(dp(12), Math.round(lineHeight * 0.42f));
    }

    private int measuredPageBreak(String text, int start, int contentWidth, int contentHeight, int estimatedChars) {
        if (start >= text.length()) {
            return text.length();
        }
        int windowEnd = Math.min(text.length(), start + Math.max(estimatedChars * 2, estimatedChars + 320));
        StaticLayout layout = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            CharSequence window = text.subSequence(start, windowEnd);
            layout = pageLayout(window, contentWidth);
            int lineCount = layout.getLineCount();
            if (lineCount == 0) {
                return Math.min(text.length(), start + Math.max(1, estimatedChars));
            }
            int fitLine = lastFittingLine(layout, contentHeight);
            boolean consumedWindow = fitLine >= lineCount - 1;
            if (!consumedWindow || windowEnd >= text.length()) {
                if (fitLine < 0) {
                    return start + Math.max(1, layout.getLineEnd(0));
                }
                int measuredEnd = start + layout.getLineEnd(fitLine);
                return normalizeMeasuredPageEnd(text, start, measuredEnd);
            }
            int nextEnd = Math.min(text.length(), start + Math.round((windowEnd - start) * 1.7f));
            if (nextEnd <= windowEnd) {
                break;
            }
            windowEnd = nextEnd;
        }
        if (layout != null) {
            int fitLine = lastFittingLine(layout, contentHeight);
            if (fitLine >= 0) {
                return normalizeMeasuredPageEnd(text, start, start + layout.getLineEnd(fitLine));
            }
        }
        return Math.min(text.length(), start + Math.max(1, estimatedChars));
    }

    private StaticLayout pageLayout(CharSequence text, int width) {
        TextPaint paint = new TextPaint(readerText.getPaint());
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, settings.lineMultiplier)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        return builder.build();
    }

    private int lastFittingLine(StaticLayout layout, int contentHeight) {
        int last = -1;
        for (int i = 0; i < layout.getLineCount(); i++) {
            if (layout.getLineBottom(i) <= contentHeight) {
                last = i;
            } else {
                break;
            }
        }
        return last;
    }

    private int normalizeMeasuredPageEnd(String text, int start, int measuredEnd) {
        int safeEnd = Math.max(start + 1, Math.min(measuredEnd, text.length()));
        if (safeEnd < text.length() && Character.isHighSurrogate(text.charAt(safeEnd - 1)) && Character.isLowSurrogate(text.charAt(safeEnd))) {
            safeEnd++;
        }
        return safeEnd;
    }

    private int chooseNaturalBreakNearEnd(String text, int start, int measuredEnd) {
        int safeEnd = Math.max(start + 1, Math.min(measuredEnd, text.length()));
        if (safeEnd >= text.length()) {
            return text.length();
        }
        int distance = safeEnd - start;
        int lower = Math.max(start + 1, safeEnd - Math.max(24, Math.round(distance * 0.08f)));
        for (int i = safeEnd; i > lower; i--) {
            char ch = text.charAt(i - 1);
            if (ch == '\n') {
                return i;
            }
        }
        for (int i = safeEnd; i > lower; i--) {
            char ch = text.charAt(i - 1);
            if ("。！？；.!?;：:，,".indexOf(ch) >= 0) {
                return i;
            }
        }
        for (int i = safeEnd; i > lower; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }
        return safeEnd;
    }

    private int skipPageBreakWhitespace(String text, int index) {
        int value = Math.max(0, Math.min(index, text.length()));
        while (value < text.length()) {
            char ch = text.charAt(value);
            if (!Character.isWhitespace(ch)) {
                break;
            }
            value++;
        }
        return value;
    }

    private void renderCurrentPage() {
        if (readerText == null || currentContent == null) {
            return;
        }
        String text = currentContent.fullText == null ? "" : currentContent.fullText;
        if (pageStarts.isEmpty()) {
            pageStarts.add(0);
        }
        pageIndex = Math.max(0, Math.min(pageIndex, pageStarts.size() - 1));
        int start = pageStarts.get(pageIndex);
        int end;
        if (lazyPagination) {
            end = lazyPageEnd(text, start);
        } else {
            end = pageIndex + 1 < pageStarts.size() ? pageStarts.get(pageIndex + 1) : text.length();
        }
        setReaderTextWithHighlight(text.substring(Math.max(0, start), Math.max(start, end)), start);
        updateProgressLabel();
    }

    private void setReaderTextWithHighlight(String value, int globalStart) {
        String display = trimPageDisplayText(value);
        if (readerText == null) {
            return;
        }
        if (activeSearchQuery == null || activeSearchQuery.trim().isEmpty()) {
            readerText.setText(display);
            return;
        }
        String query = activeSearchQuery.trim();
        String lowerDisplay = display.toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        SpannableString span = new SpannableString(display);
        int index = lowerDisplay.indexOf(lowerQuery);
        int highlightColor = Color.argb("dark".equals(settings.theme) ? 120 : 92, 205, 47, 47);
        int activeColor = Color.argb("dark".equals(settings.theme) ? 190 : 145, 205, 47, 47);
        while (index >= 0) {
            int end = Math.min(display.length(), index + query.length());
            boolean active = activeSearchOffset >= 0 && activeSearchOffset >= globalStart + index && activeSearchOffset < globalStart + end;
            span.setSpan(new BackgroundColorSpan(active ? activeColor : highlightColor), index, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (active) {
                span.setSpan(new ForegroundColorSpan(palette.text), index, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            index = lowerDisplay.indexOf(lowerQuery, Math.max(end, index + 1));
        }
        readerText.setText(span);
    }

    private String trimPageDisplayText(String value) {
        if (value == null) {
            return "";
        }
        String text = value;
        while (text.startsWith("\n") || text.startsWith("\r")) {
            text = text.substring(1);
        }
        while (text.endsWith("\n") || text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private void refreshReaderSearchHighlight() {
        if (currentContent == null || readerText == null || activeSearchQuery == null || activeSearchQuery.trim().isEmpty()) {
            return;
        }
        if (settings.pageMode) {
            renderCurrentPage();
        } else {
            setReaderTextWithHighlight(currentContent.fullText == null ? "" : currentContent.fullText, 0);
        }
    }

    private int lazyPageEnd(String text, int start) {
        if (pageIndex + 1 < pageStarts.size()) {
            return pageStarts.get(pageIndex + 1);
        }
        int next = measuredPageBreak(text, start, lazyContentWidth, lazyContentHeight, lazyEstimatedChars);
        next = skipPageBreakWhitespace(text, next);
        if (next > start && next < text.length()) {
            pageStarts.add(pageIndex + 1, next);
            return next;
        }
        return text.length();
    }

    private View readerRail(Book activeBook) {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(16), dp(18), dp(16), dp(16));
        rail.setBackgroundColor(palette.background);

        TextView brand = text("read only", 24, palette.text, Typeface.BOLD);
        brand.setIncludeFontPadding(false);
        rail.addView(brand);

        TextView slogan = monoText("nothing but reading", 11, palette.accent);
        rail.addView(slogan);
        setMargins(slogan, 0, dp(8), 0, dp(14));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        rail.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView shelf = pill("书架", false);
        shelf.setOnClickListener(view -> {
            saveCurrentProgress();
            showShelf();
        });
        actions.addView(shelf);

        TextView importButton = pill("+ 导入", false);
        importButton.setOnClickListener(view -> launchImport());
        actions.addView(importButton);
        setMargins(importButton, dp(8), 0, 0, 0);

        TextView dots = monoText("............", 15, palette.accent);
        rail.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
        setMargins(dots, 0, dp(14), 0, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        rail.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ArrayList<Book> sorted = new ArrayList<>(books);
        Collections.sort(sorted, (left, right) -> Long.compare(right.lastOpenedAt, left.lastOpenedAt));
        for (Book item : sorted) {
            rows.addView(compactBookRow(item, activeBook));
        }
        return rail;
    }

    private View compactBookRow(Book book, Book activeBook) {
        boolean active = activeBook != null && book.id != null && book.id.equals(activeBook.id);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackground(border(active ? palette.text : palette.surface, active ? palette.text : palette.hairline, 5, 1));
        row.setClickable(true);
        row.setOnClickListener(view -> {
            if (active) {
                return;
            }
            saveCurrentProgress();
            openBook(book);
        });

        TextView title = text(nonEmpty(book.title, "未命名"), 15, active ? palette.readerBackground : palette.text, Typeface.BOLD);
        title.setMaxLines(2);
        row.addView(title);

        TextView meta = text(book.format + " / " + Math.round(clamp(book.progress) * 100) + "%", 12, active ? palette.readerBackground : palette.muted, Typeface.NORMAL);
        row.addView(meta);
        setMargins(meta, 0, dp(6), 0, 0);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(params);
        return row;
    }

    private void applyReaderTextStyle() {
        if (readerText == null) {
            return;
        }
        readerText.setTextColor(palette.text);
        readerText.setBackgroundColor(palette.readerBackground);
        readerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSp);
        readerText.setLineSpacing(0, settings.lineMultiplier);
        readerText.setIncludeFontPadding(!settings.pageMode);
        readerText.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
        readerText.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            readerText.setElegantTextHeight(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            readerText.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
        }
    }

    private void showTocDialog() {
        if (currentContent == null) {
            return;
        }
        Dialog dialog = baseDialog("目录");
        LinearLayout body = dialogBody(dialog);
        for (Chapter chapter : currentContent.chapters) {
            int totalLength = currentContent.fullText == null ? 1 : Math.max(1, currentContent.fullText.length());
            String progress = Math.round(clamp(chapter.startOffset / (float) totalLength) * 100) + "%";
            String subtitle = chapter.source == null || chapter.source.trim().isEmpty() || chapter.source.startsWith("md-heading")
                    ? progress
                    : chapter.source;
            TextView row = dialogRow(chapter.title, subtitle);
            int indent = dp(Math.max(0, Math.min(5, chapter.level - 1)) * 16);
            row.setPadding(dp(12) + indent, dp(8), dp(12), dp(8));
            row.setOnClickListener(view -> {
                dialog.dismiss();
                if (isMarkdownBook(currentBook) && chapter.source != null && chapter.source.startsWith("md-heading")) {
                    scrollMarkdownToAnchor(chapter.source);
                } else {
                    scrollToOffset(chapter.startOffset, true);
                }
            });
            body.addView(row);
        }
        showDialog(dialog);
    }

    private void showSearchDialog() {
        if (currentContent == null) {
            return;
        }
        Dialog dialog = baseDialog("搜索");
        LinearLayout body = dialogBody(dialog);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("关键词");
        input.setTextColor(palette.text);
        input.setHintTextColor(palette.muted);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(border(palette.surface, palette.hairline, 4, 1));
        body.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        body.addView(results);
        setMargins(results, 0, dp(12), 0, 0);

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderSearchResults(s.toString(), results, dialog);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        showDialog(dialog);
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 200);
    }

    private void renderSearchResults(String query, LinearLayout results, Dialog dialog) {
        results.removeAllViews();
        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            if (markdownWebView != null) {
                markdownWebView.clearMatches();
            }
            return;
        }
        if (isMarkdownBook(currentBook) && markdownWebView != null) {
            markdownWebView.findAllAsync(query.trim());
        }
        String sourceText = currentContent.fullText == null ? "" : currentContent.fullText;
        if (sourceText.length() > MAX_SCROLL_MODE_TEXT_CHARS && normalizedQuery.length() < 2) {
            results.addView(dialogRow("请输入至少 2 个字", "大文件搜索需要更具体的关键词"));
            return;
        }
        int index = indexOfIgnoreCase(sourceText, normalizedQuery, 0);
        int count = 0;
        while (index >= 0 && count < 50) {
            int start = Math.max(0, index - 46);
            int end = Math.min(sourceText.length(), index + normalizedQuery.length() + 72);
            String snippet = sourceText.substring(start, end).replaceAll("\\s+", " ").trim();
            int target = index;
            TextView row = dialogRow(snippet, chapterForOffset(index));
            row.setOnClickListener(view -> {
                activeSearchQuery = query.trim();
                activeSearchOffset = target;
                dialog.dismiss();
                scrollToOffset(target, true);
                if (isMarkdownBook(currentBook) && markdownWebView != null) {
                    markdownWebView.findAllAsync(activeSearchQuery);
                } else {
                    handler.postDelayed(this::refreshReaderSearchHighlight, 180);
                }
            });
            results.addView(row);
            index = indexOfIgnoreCase(sourceText, normalizedQuery, index + Math.max(1, normalizedQuery.length()));
            count++;
        }
        if (count == 0) {
            results.addView(dialogRow("无结果", ""));
        }
    }

    private int indexOfIgnoreCase(String text, String query, int fromIndex) {
        if (text == null || query == null || query.isEmpty()) {
            return -1;
        }
        int max = text.length() - query.length();
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            if (text.regionMatches(true, i, query, 0, query.length())) {
                return i;
            }
        }
        return -1;
    }

    private void showBookmarksDialog() {
        if (currentBook == null || (!isPdfBook(currentBook) && !isEpubBook(currentBook) && currentContent == null)) {
            return;
        }
        Dialog dialog = baseDialog("书签列表");
        LinearLayout body = dialogBody(dialog);

        TextView add = dialogRow("+ 添加当前页书签", currentProgressText());
        add.setOnClickListener(view -> {
            addCurrentBookmark();
            dialog.dismiss();
            showBookmarksDialog();
        });
        body.addView(add);

        TextView savedTitle = text("已保存书签", 14, palette.muted, Typeface.BOLD);
        savedTitle.setIncludeFontPadding(false);
        body.addView(savedTitle);
        setMargins(savedTitle, 0, dp(16), 0, 0);

        ArrayList<Bookmark> sorted = new ArrayList<>(currentBook.bookmarks);
        Collections.sort(sorted, (left, right) -> Long.compare(right.createdAt, left.createdAt));
        if (sorted.isEmpty()) {
            TextView empty = dialogRow("暂无已保存书签", "添加当前页后会显示在这里");
            empty.setClickable(false);
            body.addView(empty);
        }
        for (Bookmark bookmark : sorted) {
            TextView row = dialogRow("打开 · " + nonEmpty(bookmark.label, Math.round(bookmark.progress * 100) + "%"), bookmarkSubtitle(bookmark));
            row.setOnClickListener(view -> {
                dialog.dismiss();
                scrollToProgress(bookmark.progress, true);
            });
            row.setOnLongClickListener(view -> {
                currentBook.bookmarks.remove(bookmark);
                store.saveBooks(books);
                dialog.dismiss();
                showBookmarksDialog();
                return true;
            });
            body.addView(row);
        }
        showDialog(dialog);
    }

    private String bookmarkSubtitle(Bookmark bookmark) {
        StringBuilder builder = new StringBuilder();
        if (bookmark.chapterTitle != null && !bookmark.chapterTitle.trim().isEmpty()) {
            builder.append(bookmark.chapterTitle.trim()).append("\n");
        }
        if (bookmark.snippet != null && !bookmark.snippet.trim().isEmpty()) {
            builder.append(bookmark.snippet.trim());
        } else {
            builder.append(formatTime(bookmark.createdAt));
        }
        builder.append("\n点按打开，长按删除");
        return builder.toString();
    }

    private void addCurrentBookmark() {
        float progress = currentScrollProgress();
        int offset = currentVisibleOffset();
        String chapter = chapterForOffset(offset);
        String snippet = snippetAround(offset);
        String label;
        if (isPdfBook(currentBook)) {
            label = "第 " + (pdfPageIndex + 1) + " 页";
        } else if (isEpubBook(currentBook)) {
            label = "第 " + (epubChapterIndex + 1) + " 章";
        } else {
            label = Math.round(progress * 100) + "% / " + nonEmpty(chapter, "正文");
        }
        Bookmark bookmark = new Bookmark(UUID.randomUUID().toString(), label, snippet, chapter, progress, System.currentTimeMillis());
        currentBook.bookmarks.add(bookmark);
        currentBook.progress = progress;
        store.saveBooks(books);
        toast("已添加到书签列表");
    }

    private void showSettingsDialog() {
        Dialog dialog = baseDialog("Aa");
        LinearLayout body = dialogBody(dialog);

        TextView fontValue = dialogRow("字号 " + Math.round(settings.fontSp), "");
        body.addView(fontValue);

        LinearLayout fontRow = controlRow();
        TextView smaller = iconButton("-", "减小字号");
        TextView bigger = iconButton("+", "增大字号");
        fontRow.addView(smaller);
        fontRow.addView(bigger);
        setMargins(bigger, dp(10), 0, 0, 0);
        body.addView(fontRow);
        smaller.setOnClickListener(view -> {
            int offset = currentVisibleOffset();
            settings.fontSp = Math.max(14.0f, settings.fontSp - 1.0f);
            settings.save(this);
            fontValue.setText("字号 " + Math.round(settings.fontSp));
            applyReaderAppearanceChange(offset);
        });
        bigger.setOnClickListener(view -> {
            int offset = currentVisibleOffset();
            settings.fontSp = Math.min(30.0f, settings.fontSp + 1.0f);
            settings.save(this);
            fontValue.setText("字号 " + Math.round(settings.fontSp));
            applyReaderAppearanceChange(offset);
        });

        TextView lineValue = dialogRow("行距 " + String.format(Locale.CHINA, "%.2f", settings.lineMultiplier), "");
        body.addView(lineValue);
        LinearLayout lineRow = controlRow();
        TextView tighter = iconButton("-", "减小行距");
        TextView looser = iconButton("+", "增大行距");
        lineRow.addView(tighter);
        lineRow.addView(looser);
        setMargins(looser, dp(10), 0, 0, 0);
        body.addView(lineRow);
        tighter.setOnClickListener(view -> {
            int offset = currentVisibleOffset();
            settings.lineMultiplier = Math.max(1.1f, settings.lineMultiplier - 0.05f);
            settings.save(this);
            lineValue.setText("行距 " + String.format(Locale.CHINA, "%.2f", settings.lineMultiplier));
            applyReaderAppearanceChange(offset);
        });
        looser.setOnClickListener(view -> {
            int offset = currentVisibleOffset();
            settings.lineMultiplier = Math.min(1.9f, settings.lineMultiplier + 0.05f);
            settings.save(this);
            lineValue.setText("行距 " + String.format(Locale.CHINA, "%.2f", settings.lineMultiplier));
            applyReaderAppearanceChange(offset);
        });

        LinearLayout themeRow = controlRow();
        TextView paper = pill("浅", "paper".equals(settings.theme));
        TextView dark = pill("深", "dark".equals(settings.theme));
        TextView ink = pill("墨", "ink".equals(settings.theme));
        themeRow.addView(paper);
        themeRow.addView(dark);
        themeRow.addView(ink);
        setMargins(dark, dp(10), 0, 0, 0);
        setMargins(ink, dp(10), 0, 0, 0);
        body.addView(themeRow);
        paper.setOnClickListener(view -> changeThemeFromDialog("paper", dialog));
        dark.setOnClickListener(view -> changeThemeFromDialog("dark", dialog));
        ink.setOnClickListener(view -> changeThemeFromDialog("ink", dialog));

        if (!isEpubBook(currentBook) && !isPdfBook(currentBook) && !isMarkdownBook(currentBook)) {
            LinearLayout modeRow = controlRow();
            TextView scroll = pill("滚动", !settings.pageMode);
            TextView page = pill("翻页", settings.pageMode);
            modeRow.addView(scroll);
            modeRow.addView(page);
            setMargins(page, dp(10), 0, 0, 0);
            body.addView(modeRow);
            scroll.setOnClickListener(view -> changePageModeFromDialog(false, dialog));
            page.setOnClickListener(view -> changePageModeFromDialog(true, dialog));
        }

        showDialog(dialog);
    }

    private void applyReaderTextChange(int restoreOffset) {
        applyReaderTextStyle();
        if (settings.pageMode && currentBook != null && currentContent != null && readerText != null) {
            readerText.post(() -> {
                buildPagesForCurrentLayout(currentBook.progress, restoreOffset);
                saveCurrentProgress();
            });
        }
    }

    private void applyReaderAppearanceChange(int restoreOffset) {
        if (isEpubBook(currentBook) && currentEpubDocument != null) {
            float progress = epubWebProgress();
            loadEpubChapter(epubChapterIndex);
            restoreEpubWebProgress(progress, 300);
            return;
        }
        if (isMarkdownBook(currentBook) && currentContent != null) {
            float progress = markdownWebProgress();
            currentBook.progress = progress;
            showMarkdownReader(currentBook, currentContent);
            restoreMarkdownWebProgress(progress, 420);
            return;
        }
        applyReaderTextChange(restoreOffset);
    }

    private void changePageModeFromDialog(boolean pageMode, Dialog dialog) {
        if (!pageMode && isLargeContent(currentContent)) {
            toast("大文件暂不支持滚动全文模式");
            return;
        }
        settings.pageMode = pageMode;
        settings.save(this);
        dialog.dismiss();
        if (currentBook != null && currentContent != null) {
            saveCurrentProgress();
            showReader(currentBook, currentContent);
        }
    }

    private void changeThemeFromDialog(String theme, Dialog dialog) {
        settings.theme = theme;
        settings.save(this);
        dialog.dismiss();
        if (isEpubBook(currentBook) && currentEpubDocument != null) {
            float progress = epubWebProgress();
            showEpubReader(currentBook, currentEpubDocument);
            restoreEpubWebProgress(progress, 420);
        } else if (isMarkdownBook(currentBook) && currentContent != null) {
            float progress = markdownWebProgress();
            currentBook.progress = progress;
            showMarkdownReader(currentBook, currentContent);
            restoreMarkdownWebProgress(progress, 420);
        } else if (currentBook != null && currentContent != null) {
            float progress = currentScrollProgress();
            currentBook.progress = progress;
            showReader(currentBook, currentContent);
        }
    }

    private void showBookActions(Book book) {
        Dialog dialog = baseDialog(book.title);
        LinearLayout body = dialogBody(dialog);

        TextView open = dialogRow("打开", Math.round(book.progress * 100) + "%");
        open.setOnClickListener(view -> {
            dialog.dismiss();
            openBook(book);
        });
        body.addView(open);

        TextView delete = dialogRow("移出书架", book.fileName);
        delete.setTextColor(palette.accent);
        delete.setOnClickListener(view -> {
            store.deleteBook(book);
            contentCache.remove(book.id);
            books.remove(book);
            store.saveBooks(books);
            dialog.dismiss();
            showShelf();
        });
        body.addView(delete);

        TextView reset = dialogRow("重置进度", "保留书签和文件");
        reset.setOnClickListener(view -> {
            book.progress = 0.0f;
            book.lastOpenedAt = System.currentTimeMillis();
            store.saveBooks(books);
            dialog.dismiss();
            showShelf();
        });
        body.addView(reset);

        showDialog(dialog);
    }

    private Dialog baseDialog(String title) {
        Dialog dialog = new Dialog(this);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(18), dp(18), dp(18), dp(18));
        shell.setBackground(border(palette.surface, palette.hairline, 8, 1));

        TextView label = text(title, 22, palette.text, Typeface.BOLD);
        label.setMaxLines(2);
        shell.addView(label);
        shell.setId(android.R.id.custom);
        TextView dots = monoText("........", 16, palette.accent);
        shell.addView(dots);
        dialog.setContentView(shell);
        return dialog;
    }

    private LinearLayout dialogBody(Dialog dialog) {
        LinearLayout shell = dialog.findViewById(android.R.id.custom);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setMargins(scroll, 0, dp(6), 0, 0);
        return body;
    }

    private void showDialog(Dialog dialog) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(isExpandedLayout() ? dp(560) : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private TextView dialogRow(String title, String subtitle) {
        TextView row = text(title + (subtitle == null || subtitle.trim().isEmpty() ? "" : "\n" + subtitle.trim()), 16, palette.text, Typeface.NORMAL);
        row.setMinHeight(dp(54));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackground(border(palette.readerBackground, palette.hairline, 4, 1));
        row.setClickable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, 0);
        row.setLayoutParams(params);
        return row;
    }

    private LinearLayout controlRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, dp(10));
        row.setLayoutParams(params);
        return row;
    }

    private void showBusy(String label) {
        refreshPalette();
        applySystemChrome(palette.background);
        root.setBackgroundColor(palette.background);
        root.removeAllViews();
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(palette.background);
        root.addView(page, matchParent());

        ImageView background = new ImageView(this);
        background.setImageResource(R.drawable.loading_background);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.setAlpha("dark".equals(settings.theme) ? 0.42f : 1.0f);
        page.addView(background, matchParent());

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.LEFT);
        copy.setPadding(
                safeInsetLeft + dp(isExpandedLayout() ? 52 : 28),
                safeInsetTop + dp(isExpandedLayout() ? 72 : 54),
                safeInsetRight + dp(24),
                safeInsetBottom + dp(isExpandedLayout() ? 52 : 42)
        );
        FrameLayout.LayoutParams copyParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        page.addView(copy, copyParams);

        Space topSpace = new Space(this);
        copy.addView(topSpace, new LinearLayout.LayoutParams(1, 0, 1));

        TextView mark = text("read only", isExpandedLayout() ? 52 : 44, palette.text, Typeface.BOLD);
        mark.setIncludeFontPadding(false);
        mark.setSingleLine(true);
        copy.addView(mark);

        TextView slogan = monoText("Nothing but Reading", isExpandedLayout() ? 15 : 13, palette.accent);
        slogan.setIncludeFontPadding(false);
        slogan.setLetterSpacing(0.04f);
        copy.addView(slogan);
        setMargins(slogan, dp(2), dp(12), 0, 0);

        TextView dots = monoText("........", 18, palette.accent);
        dots.setIncludeFontPadding(false);
        copy.addView(dots);
        setMargins(dots, dp(2), dp(18), 0, 0);

        TextView status = text(label, 16, palette.muted, Typeface.BOLD);
        status.setIncludeFontPadding(false);
        copy.addView(status);
        setMargins(status, dp(2), dp(8), 0, 0);
    }

    private void saveCurrentProgress() {
        if (currentBook == null) {
            return;
        }
        currentBook.progress = currentScrollProgress();
        if (isEpubBook(currentBook)) {
            currentBook.locator = "epub:" + epubChapterIndex + ":" + epubWebProgress();
        }
        currentBook.lastOpenedAt = System.currentTimeMillis();
        store.saveBooks(books);
    }

    private void updateProgressLabel() {
        if (progressView != null) {
            progressView.setText(currentProgressText());
        }
    }

    private String currentProgressText() {
        if (isPdfBook(currentBook)) {
            int count = Math.max(1, pdfPageCount);
            return (pdfPageIndex + 1) + " / " + count + " · " + Math.round(currentScrollProgress() * 100) + "%";
        }
        if (isMarkdownBook(currentBook)) {
            return Math.round(currentScrollProgress() * 100) + "% · Markdown";
        }
        if (isEpubBook(currentBook) && currentEpubDocument != null) {
            return (epubChapterIndex + 1) + " / " + Math.max(1, currentEpubDocument.chapters.size()) + " · " + Math.round(currentScrollProgress() * 100) + "%";
        }
        if (lazyPagination && settings.pageMode && !pageStarts.isEmpty()) {
            int pageNumber = lazyDisplayPageNumber();
            int pageCount = Math.max(pageNumber, lazyApproxPageCount);
            return pageNumber + " / ≈" + pageCount + " · " + Math.round(currentScrollProgress() * 100) + "%";
        }
        if (settings.pageMode && !pageStarts.isEmpty()) {
            return (pageIndex + 1) + " / " + pageStarts.size() + " · " + Math.round(currentScrollProgress() * 100) + "%";
        }
        return Math.round(currentScrollProgress() * 100) + "%";
    }

    private float currentScrollProgress() {
        if (isPdfBook(currentBook)) {
            if (pdfPageCount <= 1) {
                return 0.0f;
            }
            return clamp(pdfPageIndex / (float) Math.max(1, pdfPageCount - 1));
        }
        if (isMarkdownBook(currentBook)) {
            return markdownWebProgress();
        }
        if (isEpubBook(currentBook) && currentEpubDocument != null) {
            int count = Math.max(1, currentEpubDocument.chapters.size());
            if (count <= 1) {
                return epubWebProgress();
            }
            return clamp((epubChapterIndex + epubWebProgress()) / (float) count);
        }
        if (settings.pageMode) {
            if (pageStarts.isEmpty()) {
                return currentBook == null ? 0.0f : clamp(currentBook.progress);
            }
            if (lazyPagination && currentContent != null && currentContent.fullText != null && currentContent.fullText.length() > 1) {
                int offset = pageStarts.get(Math.max(0, Math.min(pageIndex, pageStarts.size() - 1)));
                return clamp(offset / (float) Math.max(1, currentContent.fullText.length() - 1));
            }
            return clamp(pageIndex / (float) Math.max(1, pageStarts.size() - 1));
        }
        if (readerScroll == null || readerScroll.getChildCount() == 0) {
            return currentBook == null ? 0.0f : clamp(currentBook.progress);
        }
        View child = readerScroll.getChildAt(0);
        int range = Math.max(1, child.getHeight() - readerScroll.getHeight());
        return clamp(readerScroll.getScrollY() / (float) range);
    }

    private int lazyDisplayPageNumber() {
        if (currentContent == null || currentContent.fullText == null || currentContent.fullText.length() <= 1 || pageStarts.isEmpty()) {
            return 1;
        }
        int offset = pageStarts.get(Math.max(0, Math.min(pageIndex, pageStarts.size() - 1)));
        float progress = clamp(offset / (float) Math.max(1, currentContent.fullText.length() - 1));
        return Math.max(1, 1 + Math.round(progress * Math.max(0, lazyApproxPageCount - 1)));
    }

    private float epubWebProgress() {
        if (epubWebView == null) {
            return 0.0f;
        }
        int height = Math.max(1, epubWebView.getHeight());
        int range = Math.max(1, Math.round(epubWebView.getContentHeight() * epubWebView.getScale()) - height);
        return clamp(epubWebView.getScrollY() / (float) range);
    }

    private void restoreEpubWebProgress(float progress, int delayMillis) {
        if (epubWebView == null) {
            return;
        }
        float safeProgress = clamp(progress);
        epubWebView.postDelayed(() -> {
            if (epubWebView == null) {
                return;
            }
            int height = Math.max(1, epubWebView.getHeight());
            int range = Math.max(0, Math.round(epubWebView.getContentHeight() * epubWebView.getScale()) - height);
            epubWebView.scrollTo(0, Math.round(safeProgress * range));
            updateProgressLabel();
            saveCurrentProgress();
        }, delayMillis);
    }

    private void scrollToProgress(float progress, boolean smooth) {
        if (isPdfBook(currentBook)) {
            if (pdfPageCount <= 0) {
                return;
            }
            pdfPageIndex = Math.max(0, Math.min(pdfPageCount - 1, Math.round(clamp(progress) * Math.max(0, pdfPageCount - 1))));
            renderPdfPage();
            saveCurrentProgress();
            return;
        }
        if (isMarkdownBook(currentBook)) {
            restoreMarkdownWebProgress(progress, smooth ? 120 : 0);
            saveCurrentProgress();
            return;
        }
        if (isEpubBook(currentBook) && currentEpubDocument != null) {
            int count = Math.max(1, currentEpubDocument.chapters.size());
            float scaled = clamp(progress) * count;
            int target = Math.max(0, Math.min(count - 1, (int) Math.floor(scaled)));
            float chapterProgress = clamp(scaled - target);
            loadEpubChapter(target);
            restoreEpubWebProgress(chapterProgress, 360);
            saveCurrentProgress();
            return;
        }
        if (settings.pageMode) {
            if (pageStarts.isEmpty()) {
                return;
            }
            if (lazyPagination && currentContent != null && currentContent.fullText != null) {
                int targetOffset = Math.round(clamp(progress) * Math.max(0, currentContent.fullText.length() - 1));
                pageStarts.clear();
                pageStarts.add(skipPageBreakWhitespace(currentContent.fullText, targetOffset));
                pageIndex = 0;
                renderCurrentPage();
                saveCurrentProgress();
                return;
            }
            pageIndex = Math.max(0, Math.min(pageStarts.size() - 1, Math.round(clamp(progress) * Math.max(0, pageStarts.size() - 1))));
            renderCurrentPage();
            return;
        }
        if (readerScroll == null || readerScroll.getChildCount() == 0) {
            return;
        }
        View child = readerScroll.getChildAt(0);
        int range = Math.max(1, child.getHeight() - readerScroll.getHeight());
        int y = Math.round(clamp(progress) * range);
        if (smooth) {
            readerScroll.smoothScrollTo(0, y);
        } else {
            readerScroll.scrollTo(0, y);
        }
    }

    private void scrollToOffset(int offset, boolean smooth) {
        if (isMarkdownBook(currentBook)) {
            if (currentContent != null && currentContent.fullText != null && currentContent.fullText.length() > 1) {
                restoreMarkdownWebProgress(offset / (float) Math.max(1, currentContent.fullText.length() - 1), smooth ? 120 : 0);
                saveCurrentProgress();
            }
            return;
        }
        if (settings.pageMode) {
            if (lazyPagination && currentContent != null && currentContent.fullText != null) {
                int safeOffset = Math.max(0, Math.min(offset, Math.max(0, currentContent.fullText.length() - 1)));
                pageStarts.clear();
                pageStarts.add(skipPageBreakWhitespace(currentContent.fullText, safeOffset));
                pageIndex = 0;
                renderCurrentPage();
                saveCurrentProgress();
                return;
            }
            pageIndex = pageIndexForOffset(offset);
            renderCurrentPage();
            saveCurrentProgress();
            return;
        }
        if (readerText == null || readerScroll == null) {
            return;
        }
        readerText.post(() -> {
            Layout layout = readerText.getLayout();
            if (layout == null) {
                return;
            }
            int safeOffset = Math.max(0, Math.min(offset, readerText.getText().length()));
            int line = layout.getLineForOffset(safeOffset);
            int y = Math.max(0, layout.getLineTop(line) - dp(18));
            if (smooth) {
                readerScroll.smoothScrollTo(0, y);
            } else {
                readerScroll.scrollTo(0, y);
            }
        });
    }

    private int currentVisibleOffset() {
        if (isMarkdownBook(currentBook)) {
            int length = currentContent == null || currentContent.fullText == null ? 1 : currentContent.fullText.length();
            return Math.max(0, Math.min(length - 1, Math.round(markdownWebProgress() * Math.max(0, length - 1))));
        }
        if (settings.pageMode) {
            if (pageStarts.isEmpty()) {
                return 0;
            }
            return pageStarts.get(Math.max(0, Math.min(pageIndex, pageStarts.size() - 1)));
        }
        if (readerText == null || readerScroll == null || readerText.getLayout() == null) {
            return 0;
        }
        Layout layout = readerText.getLayout();
        int line = layout.getLineForVertical(readerScroll.getScrollY() + dp(12));
        return Math.max(0, Math.min(layout.getLineStart(line), readerText.getText().length()));
    }

    private int pageIndexForOffset(int offset) {
        if (pageStarts.isEmpty()) {
            return 0;
        }
        int safeOffset = Math.max(0, offset);
        int low = 0;
        int high = pageStarts.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int start = pageStarts.get(middle);
            int next = middle + 1 < pageStarts.size() ? pageStarts.get(middle + 1) : Integer.MAX_VALUE;
            if (safeOffset < start) {
                high = middle - 1;
            } else if (safeOffset >= next) {
                low = middle + 1;
            } else {
                return middle;
            }
        }
        return Math.max(0, Math.min(pageStarts.size() - 1, low));
    }

    private String snippetAround(int offset) {
        if (isPdfBook(currentBook)) {
            return "PDF 第 " + (pdfPageIndex + 1) + " 页";
        }
        if (isEpubBook(currentBook)) {
            return "章节内 " + Math.round(epubWebProgress() * 100) + "%";
        }
        if (currentContent == null || currentContent.fullText == null) {
            return "";
        }
        int start = Math.max(0, offset - 24);
        int end = Math.min(currentContent.fullText.length(), offset + 80);
        return currentContent.fullText.substring(start, end).replaceAll("\\s+", " ").trim();
    }

    private String chapterForOffset(int offset) {
        if (isPdfBook(currentBook)) {
            return "第 " + (pdfPageIndex + 1) + " 页";
        }
        if (isEpubBook(currentBook)) {
            if (currentEpubDocument == null || currentEpubDocument.chapters.isEmpty()) {
                return "";
            }
            EpubChapterRef chapter = currentEpubDocument.chapters.get(Math.max(0, Math.min(epubChapterIndex, currentEpubDocument.chapters.size() - 1)));
            return chapter.title;
        }
        if (currentContent == null || currentContent.chapters.isEmpty()) {
            return "";
        }
        Chapter best = currentContent.chapters.get(0);
        for (Chapter chapter : currentContent.chapters) {
            if (chapter.startOffset <= offset) {
                best = chapter;
            } else {
                break;
            }
        }
        return best.title;
    }

    private void cycleTheme() {
        if ("paper".equals(settings.theme)) {
            settings.theme = "dark";
        } else if ("dark".equals(settings.theme)) {
            settings.theme = "ink";
        } else {
            settings.theme = "paper";
        }
        settings.save(this);
    }

    private String themeGlyph() {
        if ("dark".equals(settings.theme)) {
            return "●";
        }
        if ("ink".equals(settings.theme)) {
            return "◐";
        }
        return "○";
    }

    private String recentSummary() {
        if (books.isEmpty()) {
            return "无最近";
        }
        Book latest = Collections.max(books, Comparator.comparingLong(book -> book.lastOpenedAt));
        return "最近 " + formatTime(latest.lastOpenedAt);
    }

    private boolean isExpandedLayout() {
        Configuration configuration = getResources().getConfiguration();
        return configuration.screenWidthDp >= 700
                || (configuration.screenWidthDp >= 600 && configuration.smallestScreenWidthDp >= 600);
    }

    private int shelfRailWidthPx() {
        return railWidthPx(0.34f, 292, 360);
    }

    private int readerRailWidthPx() {
        return railWidthPx(0.32f, 252, 336);
    }

    private int railWidthPx(float ratio, int minDp, int maxDp) {
        int widthDp = Math.max(1, getResources().getConfiguration().screenWidthDp);
        int railDp = Math.round(widthDp * ratio);
        railDp = Math.max(minDp, Math.min(maxDp, railDp));
        return dp(railDp);
    }

    private void refreshPalette() {
        palette = Palette.from(settings.theme);
        applySystemChrome(palette.background);
    }

    private void applySystemChrome(int color) {
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().setNavigationBarDividerColor(color);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = edgeToEdgeFlags();
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !"dark".equals(settings.theme)) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(
                        appearance,
                        appearance
                );
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(
                        0,
                        appearance
                );
            }
        }
    }

    private int edgeToEdgeFlags() {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!"dark".equals(settings.theme) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (!"dark".equals(settings.theme) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        return flags;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(border(palette.surface, palette.hairline, 6, 1));
        return card;
    }

    private TextView text(String value, float sp, int color, int style) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        text.setTypeface(Typeface.DEFAULT, style);
        text.setLetterSpacing(0);
        return text;
    }

    private TextView monoText(String value, float sp, int color) {
        TextView text = text(value, sp, color, Typeface.NORMAL);
        text.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        return text;
    }

    private TextView iconButton(String label, String tooltip) {
        TextView button = text(label, 18, palette.text, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(dp(44));
        button.setMinHeight(dp(44));
        button.setBackground(border(Color.TRANSPARENT, palette.hairline, 4, 1));
        button.setClickable(true);
        button.setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button.setTooltipText(tooltip);
        }
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return button;
    }

    private TextView smallIconButton(String label, String tooltip) {
        TextView button = text(label, 20, palette.text, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(dp(36));
        button.setMinHeight(dp(36));
        button.setBackground(border(Color.TRANSPARENT, palette.hairline, 4, 1));
        button.setClickable(true);
        button.setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button.setTooltipText(tooltip);
        }
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        return button;
    }

    private TextView pill(String label, boolean active) {
        TextView pill = text(label, 12, active ? palette.background : palette.text, Typeface.BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setIncludeFontPadding(false);
        pill.setPadding(dp(10), dp(6), dp(10), dp(6));
        pill.setMinHeight(dp(30));
        pill.setBackground(border(active ? palette.text : Color.TRANSPARENT, active ? palette.text : palette.hairline, 4, 1));
        return pill;
    }

    private GradientDrawable border(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(strokeDp), stroke);
        return drawable;
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float clampPdfZoom(float value) {
        return Math.max(0.75f, Math.min(3.0f, value));
    }

    private void setMargins(View view, int left, int top, int right, int bottom) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) params).setMargins(left, top, right, bottom);
            view.setLayoutParams(params);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String formatTime(long time) {
        if (time <= 0) {
            return "-";
        }
        return new SimpleDateFormat("MM.dd HH:mm", Locale.CHINA).format(time);
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String colorCss(int color) {
        return String.format(Locale.US, "#%02x%02x%02x", Color.red(color), Color.green(color), Color.blue(color));
    }

    private static boolean isLargeContent(ReaderContent content) {
        return content != null
                && content.fullText != null
                && content.fullText.length() > MAX_SCROLL_MODE_TEXT_CHARS;
    }

    private static boolean isPdfBook(Book book) {
        return book != null && "PDF".equals(book.format);
    }

    private static boolean isEpubBook(Book book) {
        return book != null && "EPUB".equals(book.format);
    }

    private static boolean isMarkdownBook(Book book) {
        return book != null && "MD".equals(book.format);
    }

    private final class OpeningCoverView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path fold = new Path();

        OpeningCoverView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }

            boolean expanded = isExpandedLayout();
            paint.setShader(new LinearGradient(
                    0,
                    0,
                    width,
                    height,
                    Color.rgb(255, 255, 252),
                    Color.rgb(238, 238, 229),
                    Shader.TileMode.CLAMP
            ));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            drawCoverDots(canvas, width, height);
            drawCoverAccent(canvas, width, height, expanded);

            if (expanded || width > height) {
                drawCoverArt(canvas, width * 0.53f, height * 0.19f, width * 0.35f, height * 0.56f);
                drawCoverText(canvas, dp(54), height * 0.43f, true);
            } else {
                drawCoverArt(canvas, width * 0.18f, height * 0.16f, width * 0.64f, height * 0.38f);
                drawCoverText(canvas, dp(32), height * 0.68f, false);
            }
        }

        private void drawCoverDots(Canvas canvas, int width, int height) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(34, 17, 17, 17));
            float step = dp(14);
            float radius = Math.max(1.0f, dp(1));
            for (float y = dp(18); y < height; y += step) {
                for (float x = dp(18); x < width; x += step) {
                    canvas.drawCircle(x, y, radius, paint);
                }
            }
        }

        private void drawCoverAccent(Canvas canvas, int width, int height, boolean expanded) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(215, 25, 32));
            float left = expanded ? dp(36) : dp(22);
            rect.set(left, dp(26), left + dp(5), height - dp(26));
            canvas.drawRoundRect(rect, dp(3), dp(3), paint);

            paint.setColor(Color.argb(28, 215, 25, 32));
            rect.set(width - dp(92), dp(54), width - dp(32), height - dp(58));
            canvas.drawRoundRect(rect, dp(28), dp(28), paint);
        }

        private void drawCoverArt(Canvas canvas, float left, float top, float artWidth, float artHeight) {
            float pageRadius = dp(9);
            drawPage(canvas, left + artWidth * 0.08f, top + artHeight * 0.06f, artWidth * 0.58f, artHeight * 0.88f, -4.0f, Color.rgb(17, 17, 17), Color.rgb(17, 17, 17), pageRadius);
            drawPage(canvas, left + artWidth * 0.32f, top + artHeight * 0.01f, artWidth * 0.58f, artHeight * 0.90f, 5.0f, Color.rgb(255, 255, 252), Color.rgb(17, 17, 17), pageRadius);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb(110, 17, 17, 17));
            float lineLeft = left + artWidth * 0.43f;
            float lineRight = left + artWidth * 0.76f;
            for (int i = 0; i < 5; i++) {
                float y = top + artHeight * (0.22f + i * 0.105f);
                canvas.drawLine(lineLeft, y, lineRight - (i % 2 == 0 ? 0 : artWidth * 0.08f), y, paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(215, 25, 32));
            rect.set(left + artWidth * 0.58f, top + artHeight * 0.70f, left + artWidth * 0.66f, top + artHeight * 0.90f);
            canvas.drawRoundRect(rect, dp(2), dp(2), paint);
        }

        private void drawPage(Canvas canvas, float left, float top, float width, float height, float rotation, int fill, int stroke, float radius) {
            canvas.save();
            canvas.rotate(rotation, left + width * 0.5f, top + height * 0.5f);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            rect.set(left, top, left + width, top + height);
            canvas.drawRoundRect(rect, radius, radius, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(stroke);
            canvas.drawRoundRect(rect, radius, radius, paint);

            fold.reset();
            fold.moveTo(left + width * 0.72f, top);
            fold.lineTo(left + width, top);
            fold.lineTo(left + width, top + height * 0.27f);
            fold.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill == Color.rgb(17, 17, 17) ? Color.rgb(44, 44, 42) : Color.rgb(238, 238, 229));
            canvas.drawPath(fold, paint);

            canvas.restore();
        }

        private void drawCoverText(Canvas canvas, float left, float baseline, boolean expanded) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            paint.setColor(Color.rgb(17, 17, 17));
            paint.setTextSize(sp(expanded ? 56 : 42));
            canvas.drawText("read only", left, baseline, paint);

            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            paint.setColor(Color.rgb(215, 25, 32));
            paint.setTextSize(sp(expanded ? 18 : 15));
            canvas.drawText("nothing but reading", left + dp(2), baseline + dp(expanded ? 40 : 34), paint);

            paint.setColor(Color.argb(190, 17, 17, 17));
            paint.setTextSize(sp(expanded ? 13 : 12));
            canvas.drawText("EPUB  TXT  MD", left + dp(2), baseline + dp(expanded ? 70 : 60), paint);
        }
    }

    private static final class Palette {
        final int background;
        final int readerBackground;
        final int surface;
        final int text;
        final int muted;
        final int hairline;
        final int accent;

        Palette(int background, int readerBackground, int surface, int text, int muted, int hairline, int accent) {
            this.background = background;
            this.readerBackground = readerBackground;
            this.surface = surface;
            this.text = text;
            this.muted = muted;
            this.hairline = hairline;
            this.accent = accent;
        }

        static Palette from(String theme) {
            if ("dark".equals(theme)) {
                return new Palette(
                        Color.rgb(15, 15, 15),
                        Color.rgb(12, 12, 12),
                        Color.rgb(24, 24, 24),
                        Color.rgb(240, 240, 236),
                        Color.rgb(150, 150, 146),
                        Color.rgb(62, 62, 60),
                        Color.rgb(215, 25, 32)
                );
            }
            if ("ink".equals(theme)) {
                return new Palette(
                        Color.rgb(250, 250, 248),
                        Color.rgb(255, 255, 252),
                        Color.rgb(255, 255, 255),
                        Color.rgb(20, 20, 18),
                        Color.rgb(92, 92, 86),
                        Color.rgb(218, 218, 210),
                        Color.rgb(215, 25, 32)
                );
            }
            return new Palette(
                    Color.rgb(247, 247, 242),
                    Color.rgb(247, 247, 242),
                    Color.rgb(255, 255, 252),
                    Color.rgb(17, 17, 17),
                    Color.rgb(96, 96, 90),
                    Color.rgb(214, 214, 204),
                    Color.rgb(215, 25, 32)
            );
        }
    }
}
