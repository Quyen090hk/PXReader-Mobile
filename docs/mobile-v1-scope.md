# PXReader Mobile v1 scope

## Screens and flow

```text
Document library
  ├─ system picker / system Share → validate → private copy → metadata → library
  ├─ recent and tag filters
  └─ open document → reader

Reader
  ├─ contents → chapter jump
  ├─ text selection → highlight or note
  ├─ search → result → exact locator
  ├─ annotations → jump / delete
  └─ settings → font, line height, light/dark/system theme

Library tools
  ├─ annotation export (Markdown)
  └─ portable backup export (protocol/v1 JSON)
```

## In scope

- TXT and EPUB only; local import, reading, progress recovery, table of contents, and native settings.
- TXT native rendering; EPUB controlled WebView after sanitization, without network or book-supplied JavaScript.
- Background indexing/search, annotation CRUD, tags, recent reading, incoming Android Shares, annotation export, and metadata backup export.
- Rotation, backgrounding, activity recreation, cancellation, duplicate detection, corrupt/unsupported-file errors, and large-file work off the main thread.

## Explicitly out of scope for v1

- PDF renderer, DRM/protected EPUB, cloud sync, login/accounts, collaboration, web links, and embedded scripts/media execution.
- Cross-device import of raw document bytes. Portable metadata is supported once the same content has been imported locally.
- Pixel-identical desktop pagination. Position fidelity is semantic text location, not CSS page geometry.

## Acceptance gates before HarmonyOS starts

1. A real Android device imports normal, duplicate, unsupported, and corrupt TXT/EPUB files with clear outcomes.
2. TXT and EPUB complete import → read → leave/background/kill → restore at a stable semantic location.
3. Search results and annotation jumps reach the stored chapter/offset.
4. Share intake, annotation export, and `protocol/v1` backup are verified from Android’s system UI.
5. Rotation, process recreation, a long TXT, a large EPUB, and task cancellation have no data loss or main-thread stalls.

