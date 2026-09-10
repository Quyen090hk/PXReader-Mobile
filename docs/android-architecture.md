# Android architecture

```text
Compose screens ──> ViewModels ──> ReaderRepository / ImportRepository
                                            │
                      Room <── document metadata, location, annotation, search units
                                            │
                  filesDir/documents <── imported raw TXT / EPUB files
                                            │
              TXT engine / EPUB package + local-only WebView renderer
```

The app process never reads a persisted content URI after import. The importer takes a temporary URI grant, streams it to a private temporary file while hashing, validates/inspects it, then atomically promotes it. A unique SHA-256 document ID prevents copies of the same content from creating another library entry.

All parse, hash, search, and export work runs on `Dispatchers.IO` or `Dispatchers.Default`. ViewModels retain only screen state, and every operation is cancellable with coroutine cancellation. Reading positions are written on location changes and lifecycle `onStop`; importing and opening do not depend on an activity remaining alive.

EPUB uses a WebView only for XHTML/CSS layout. It loads a sanitized spine document on an `https://appassets.androidplatform.net` origin supplied by an `WebViewAssetLoader` path handler. JavaScript, DOM storage, file/content access, mixed content, and network loads are disabled. The only bridge reports a user selection and scroll state from app-injected code; book-supplied script and handlers are removed before render.

