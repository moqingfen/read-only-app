# read only

read only is a lightweight local Android reader for EPUB, TXT, Markdown, and PDF files.

## Scope

- Local bookshelf with file import
- Bookshelf sorting by recent activity, title, or progress
- Bookshelf list/grid display modes
- EPUB, TXT, Markdown, and PDF reading
- EPUB HTML/CSS/image rendering with extracted bookshelf covers
- Markdown rich rendering for headings, images, links, quotes, lists, code blocks, tables, and local relative images
- TXT encoding detection for UTF-8, UTF-16, GB18030, Big5, and common fallback text
- Fast import metadata extraction and parsed-content cache
- Reading progress
- Scroll and page-turn reading modes
- Table of contents
- Search
- Bookmarks
- PDF width/height fit modes and pinch zoom
- Font size, line height, and light/dark/e-ink themes
- Minimal monochrome UI with a small red accent inspired by Nothing Phone interfaces
- Imagegen-based launcher icon and opening cover with the slogan "nothing but reading"
- Foldable-aware layout for vivo X Fold 5 style inner displays

HTML is intentionally not exposed as an import format. EPUB internals may still contain XHTML, which is parsed as part of EPUB support.

## Build

Open this folder in Android Studio, or run Gradle if it is installed:

```sh
gradle :app:assembleDebug
```

In this workspace, a local toolchain based debug build can also be produced without Gradle:

```sh
scripts/build_debug_apk.sh
```

This repository does not vendor Gradle or Android SDK binaries.
