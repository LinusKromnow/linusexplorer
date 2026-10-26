# LinusExplorer

A free, open-source Android file manager in the spirit of Amaze and Solid Explorer, with no ads, tracking or paid tiers.
It covers local storage (list and grid views, recursive search, bookmarks and recent folders, tabs and dual-pane, a recycle bin, optional root access, file properties with checksums, archives, file encryption, an encrypted vault and a built-in text editor) and network storage over SFTP, FTP/FTPS, SMB, WebDAV and S3, including uploading files edited in other apps. Cloud drives (Google Drive, Dropbox, OneDrive...) are next. See [ROADMAP.md](ROADMAP.md).

## Building

Requirements: JDK 17 and the Android SDK (Android Studio installs both).

```sh
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests
./gradlew installDebug         # install on a connected device/emulator
```

Or open the folder in Android Studio and press Run.

## Project layout

```
app/src/main/kotlin/io/github/linuskromnow/linusexplorer/
├── fs/            Storage-agnostic core: VirtualFileSystem, paths, sorting, copy/move engine
│   ├── local/     Device storage backend
│   ├── root/      Superuser backend (libsu root service), optional
│   ├── network/   SFTP, FTP/FTPS, SMB, WebDAV and S3 backends, saved connections
│   ├── archive/   Browsing and creating ZIP, 7z and tar archives
│   ├── vault/     The encrypted private vault
│   ├── trash/     Per-volume recycle bin
│   ├── search/    Tree walker and recursive name search, working on any backend
│   └── properties/ Folder size aggregation and streaming checksums
├── crypto/        age file encryption and scrypt
├── security/      App lock, Keystore and fingerprint helpers
├── editor/        Text editor core (encodings, undo, search)
├── tasks/         Background transfers and uploads of files edited in other apps
├── storage/       Android storage volumes and the "All files access" permission
├── data/          User preferences (DataStore), bookmarks and recent folders
├── thumbnails/    Image, video and APK thumbnails for the file list (Coil, no network module)
├── ui/            Jetpack Compose screens
└── util/          Opening and sharing files with other apps
```

Every storage backend implements `fs/VirtualFileSystem`. The UI and the copy/move engine talk only to that
interface, so a new protocol is one new class and a registration in `AppContainer`.

## Principles

- **No proprietary dependencies.** No Google Play Services, Firebase or closed-source SDKs, so the app
  qualifies for F-Droid. Cloud providers are reached through their public REST APIs.
- **No network access unless you add a remote storage yourself.**

## License

[GPL-3.0-or-later](LICENSE)
