# Android architecture

```text
Compose screens ──> ViewModels ──> ReaderRepository / DocumentImporter / DocumentScanner
                                            │
                      Room <── document metadata, source URI/path, cover cache path, location, annotation, search units
                                            │
          filesDir/documents + filesDir/covers <── imported raw TXT / EPUB files and extracted EPUB covers
                                            │
              TXT engine / EPUB package + local-only WebView renderer
```

The app process never reads a persisted content URI after import. The importer takes a temporary URI grant, streams it to a private temporary file while hashing, validates/inspects it, then atomically promotes it. A unique SHA-256 document ID prevents copies of the same content from creating another library entry.

`DocumentScanner` has two discovery modes. A selected-directory scan recurses through a persisted Storage Access Framework tree URI. A full-device scan requires the user to grant Android's All files access special permission; it traverses shared storage while deliberately skipping `Android/`. Both modes feed candidates into `DocumentImporter`, so manual import, share import, and scanning always follow the same validation, metadata extraction, private-copy, deduplication, indexing, and Room persistence rules.

Room schema v2 adds nullable `sourceUri`, `sourcePath`, and `coverFileName` to the existing `documents` table. The v1→v2 migration only adds those columns, preserving existing books, reading positions, annotations, bookmarks, and search units.

All parse, hash, search, and export work runs on `Dispatchers.IO` or `Dispatchers.Default`. ViewModels retain only screen state, and every operation is cancellable with coroutine cancellation. Reading positions are written on location changes and lifecycle `onStop`; importing and opening do not depend on an activity remaining alive.

EPUB uses a WebView only for XHTML/CSS layout. It loads a sanitized spine document on an `https://appassets.androidplatform.net` origin supplied by an `WebViewAssetLoader` path handler. JavaScript, DOM storage, file/content access, mixed content, and network loads are disabled. The only bridge reports a user selection and scroll state from app-injected code; book-supplied script and handlers are removed before render.
