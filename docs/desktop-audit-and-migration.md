# PXReader desktop audit and mobile migration plan

Audit source: `PXReader` desktop repository, `src/app.js`, `src/search-worker.js`, and `src-tauri/src/lib.rs`, reviewed on 2026-09-10.

## Existing modules

| Desktop concern | Current implementation | Android disposition |
| --- | --- | --- |
| Library files | `BookStore` stores a browser `Blob` record in IndexedDB; Tauri reads selected paths into `File` objects. | Rewrite for Android `ContentResolver`, copy into `filesDir/documents`, and Room metadata. |
| Document identity | `name::size::lastModified`. | Replace with content SHA-256 (`sha256:<lowercase hex>`), stable across imports and backup. |
| TXT | UTF-8 → GB18030 → Big5 decoding, heading split, chapter/ratio location. | Reuse rules in Kotlin; store deterministic chapter number plus character offset instead of a scroll ratio alone. |
| EPUB | JSZip reads `container.xml`, OPF, spine, HTML nav or NCX, then sanitizes/re-writes resources. | Reimplement package and spine extraction with `ZipFile` + XML pull parser. Render sanitized spine XHTML in a controlled local WebView. |
| Search | Background Web Worker builds an inverted index; direct scan is a fallback. | Rewrite as coroutine work. Persist searchable chapter units in Room, using FTS when applicable and Unicode-safe `LIKE` fallback for exact Chinese matching. |
| Reading position | `localStorage`, chapter or page plus ratio/anchor. | Room `ReadingPositionEntity`, debounced, with a portable `TextLocator`. |
| Annotations | `localStorage` array holding quote, note, color, location, timestamps. Rendering re-finds quote in DOM. | Room annotation table. Capture offsets plus quote/context so jumps and recovery are deterministic. |
| Settings | `localStorage` keys for theme, zoom, layout, rail state. | DataStore preferences. Theme, font size, and line height are product settings; desktop rail state is not mobile data. |
| Desktop integration | Tauri file dialog and Documents/PXReader/Books directory. | Android document picker, `ACTION_SEND`, app-private storage, share-safe FileProvider exports. |

## Reuse versus rewrite

Reusable without a platform port:

- TXT chapter heading rule and 9,000-character fallback segmentation.
- EPUB package semantics: `META-INF/container.xml` → OPF manifest/spine → EPUB3 nav, then NCX fallback.
- Search result contract: title, snippet, locator; cap initial result display at 200.
- Reading workflow and product rules: local-first data, progress per document, selection-based highlights, return to recorded location.
- EPUB safety policy: remove scripts, embedded frames/objects, event attributes, and external navigation.

Must be rewritten:

- Browser DOM, IndexedDB, `localStorage`, web worker, JSZip, and Tauri APIs.
- UI layout and gestures; Compose is native mobile UI, not a responsive port of the desktop rails.
- Scroll-ratio-only annotation recovery. Mobile uses text offsets, quote, prefix, and suffix.
- Identity and export data. Desktop’s name/size/mtime key is intentionally not portable.

## Migration boundary

The desktop app does not yet write the mobile protocol. Therefore no claim is made that its private IndexedDB/localStorage database can be imported automatically. The forward migration path is:

1. Mobile emits `protocol/v1` backups now.
2. Desktop adds a small export/import adapter that maps its book records, progress, and annotations to those schemas.
3. HarmonyOS implements the same schemas, fixtures, ID rules, and timestamp format.

Document contents are not embedded in a backup. A backup references the source checksum and original filename. Each device imports the matching local document, then applies positions/annotations when its `documentId` matches.

