# PXReader portable data protocol v1

This folder is the contract shared by the Android and HarmonyOS native applications. JSON uses UTF-8, RFC 3339 UTC timestamps, and schema version `1.0.0`.

## Invariants

- IDs are never UI-generated transient IDs. `documentId` is `sha256:` plus 64 lowercase hex characters. Bookmark and annotation IDs are UUIDv4 strings.
- `createdAt` is immutable. Each mutation updates `updatedAt`; exported snapshots also carry `exportedAt`.
- Formats use lowercase `txt` or `epub`. Unknown fields must be ignored so a minor protocol revision is forward-compatible.
- A locator always has a `chapterIndex`, `charStart`, `charEnd`, progress fraction, quote, and surrounding context. EPUB also includes its normalized spine href; TXT uses `chapterHref: null`.
- Backups are metadata-only. The raw book file stays local and is matched by `documentId`.

Schemas:

- `document.schema.json`
- `position.schema.json`
- `bookmark.schema.json`
- `annotation.schema.json`
- `backup.schema.json`

`fixtures/backup-v1.json` is the canonical interoperability example. Both platforms should validate exports against `backup.schema.json` and use it in contract tests.

