# read only

read only is a lightweight local Android reader for EPUB, TXT, Markdown, and PDF files.

## Scope

- Local bookshelf with file import, drag-and-drop import, and open-with support from file managers / share sheet (v0.3 / v0.2)
- Bookshelf sorting by recent activity, title, or progress
- Bookshelf list/grid display modes
- EPUB, TXT, Markdown, and PDF reading
- EPUB HTML/CSS/image rendering with extracted bookshelf covers
- Markdown rich rendering for headings, images, links, quotes, lists, code blocks, tables, and local relative images
- TXT encoding detection for UTF-8, UTF-16, GB18030, Big5, and common fallback text
- Fast import metadata extraction and parsed-content cache
- Reading progress
- Scroll and page-turn reading modes for all text formats; EPUB/Markdown page mode uses true CSS multi-column pagination (whole lines only, page-accurate progress) (v0.2)
- Page-turn animation with finger-following drag across TXT / EPUB / Markdown (v0.2)
- Keep screen on while reading; volume-key page turning with settings toggle (v0.2)
- Chapter/global progress slider with a "return to previous position" chip (v0.2)
- Element-anchored reading position: font, theme, chrome, and fold/unfold changes restore the exact line, not a percentage (v0.2 / v0.3)
- Table of contents
- Search
- Bookmarks
- PDF width/height fit modes and pinch zoom
- Font size, line height, and light/dark/e-ink themes
- Minimal monochrome UI with a small red accent inspired by Nothing Phone interfaces
- Imagegen-based launcher icon and opening cover with the slogan "nothing but reading"
- Foldable support oriented at the vivo X Fold 5 (v0.3):
  - window width classes (compact / medium / expanded) instead of a single boolean
  - two-page book spread on the expanded inner display for TXT, EPUB, Markdown, and PDF, with the gutter aligned to the centered crease (auto / single / double setting)
  - reading position continuity across fold/unfold via element anchors, with debounced surface rebuilds during hinge movement
  - immersive compact layout on the 21:9 cover display (chrome hidden by default, tap center to reveal)

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
