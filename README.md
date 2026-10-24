# LinusExplorer

A free, open-source Android file manager in the spirit of Amaze and Solid Explorer, with no ads, tracking or paid tiers.
It covers local storage today, with archives, file encryption, an encrypted vault and a built-in text editor, with network (SFTP, FTP, SMB, WebDAV) and cloud storage planned. See [ROADMAP.md](ROADMAP.md).

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
│   └── local/     Device storage backend
├── storage/       Android storage volumes and the "All files access" permission
├── data/          User preferences (DataStore)
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
