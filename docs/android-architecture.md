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

`DocumentScanner` has two discovery modes. A selected-directory scan recurses through a persisted Storage Access Framework tree URI. A full-device scan requires the user to grant Android's All files access special permission; it traverses shared storage while deliberately skipping `Android/`. Each scan receives one `ScanOptions` value (allowed formats, minimum byte size, and initial tags), filters files before opening them, and applies tags only to newly imported documents. Both modes feed candidates into `DocumentImporter`, so manual import, share import, and scanning always follow the same validation, metadata extraction, private-copy, deduplication, indexing, and Room persistence rules.

Room schema v2 adds nullable `sourceUri`, `sourcePath`, and `coverFileName` to `documents`. Schema v3 adds `contentLength` to `search_units` so newly indexed EPUB chapters use the same UTF-16 text-node offset unit as the WebView. The v2→v3 migration retains older search rows; their length falls back to the previous SQLite calculation until re-indexed, while search jumps also match the quoted text in the current DOM.

Parse, hash, search, and export work runs off the main thread. Reading-position writes are ordered in a repository-owned scope: ordinary location changes are debounced, while leaving or pausing the reader queues the final position independently of the screen ViewModel. The reading viewport remains fixed when the temporary controls appear, avoiding an unnecessary re-pagination and location change.

EPUB uses a WebView for XHTML/CSS layout. It loads a sanitized spine document on an `https://appassets.androidplatform.net` origin supplied by a `WebViewAssetLoader` path handler. JavaScript is enabled for the app's paging/selection bridge, but book-supplied scripts and event handlers are removed before render; DOM storage, file/content access, mixed content, and network loads are disabled. The WebView is released when its chapter host leaves composition.
