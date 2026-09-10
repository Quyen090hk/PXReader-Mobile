# PXReader-Mobile

PXReader-Mobile is the native mobile companion to [PXReader](https://github.com/Quyen090hk/PXReader). It is a separate repository on purpose: Android and HarmonyOS keep native UI, storage, lifecycle, sharing, and rendering integrations, while agreeing on the same product rules and portable data protocol.

## Current delivery: Android first

The `android/` project is a Kotlin + Jetpack Compose application that supports the first mobile reading loop:

- import TXT and EPUB through Android's system picker or a Share action;
- recursively scan a user-selected folder, or scan shared device storage after the user grants Android's all-files access;
- copy accepted files into app-private storage and de-duplicate them by SHA-256;
- persist title, author, source URI/path, EPUB cover cache path, locations, annotations, tags, and search units with Room;
- render TXT natively and EPUB in a sandboxed, local-only WebView;
- recover the exact saved chapter and text offset after process recreation;
- search in background, navigate to a result, create/remove annotations, and export annotations or a versioned backup.

PDF is deliberately out of the initial mobile scope. It remains available in the desktop product and is a later, separate renderer decision.

## Layout

```text
android/       Android Studio / Gradle project
docs/          desktop audit, mobile scope, and Android architecture
protocol/v1/   platform-neutral JSON Schemas and a canonical fixture
```

## Open the Android app

Install Android Studio with JDK 17 and Android SDK Platform 37, then open `android/` and let Gradle sync. Run the `app` configuration on an Android 8.0+ device or emulator.

The project includes a Gradle wrapper pinned to Gradle 9.5.0 and uses Android Gradle Plugin 9.3.0. The initial target is Android API 36; `minSdk` is 26.

## Local library discovery

Use **扫描书籍** in the library to choose either a folder or the whole shared device storage. Folder scans use Android's Storage Access Framework and retain only the selected tree's read grant. Full-device scans intentionally exclude `Android/` and require the user to enable **All files access** in the system settings; this is necessary on modern Android versions to inspect arbitrary EPUB and TXT paths.

Every discovered file goes through the same importer as a manual import: PXReader hashes it, checks the supported format, extracts title/author/chapter metadata, extracts an EPUB cover when present, copies the source to private storage, then writes the metadata to Room. The original source URI/path is retained for library provenance, while reading continues to work from the app-private copy.

## Product and protocol contract

- [Desktop audit and migration](docs/desktop-audit-and-migration.md)
- [Mobile v1 scope](docs/mobile-v1-scope.md)
- [Android architecture](docs/android-architecture.md)
- [Shared data protocol v1](protocol/v1/README.md)

HarmonyOS work begins only after Android's import → read → leave → restore loop has passed on real devices. The protocol schemas and fixture are already the contract HarmonyOS must consume and produce.
