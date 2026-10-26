# Roadmap

## v0.1: local file manager (done in the initial scaffold)
- [x] Browse internal storage, SD cards and `/`
- [x] All files access (Android 11+) / legacy storage permissions
- [x] Copy, cut, paste, rename, delete, new folder/file, share, open with
- [x] Multi-select, natural sort (name/size/date/type), folders first, hidden files
- [x] Breadcrumbs, back history, per-folder scroll restore, pull to refresh
- [x] Storage-agnostic `VirtualFileSystem` + cross-filesystem copy/move engine
- [x] CI on GitHub Actions; fastlane metadata for F-Droid / Play

## v0.2: core file-manager polish
- [x] Run transfers in a foreground service so they survive leaving the app; notification with progress
- [x] Conflict dialog (replace / skip / keep both / merge folders, "apply to all")
- [x] Image/video thumbnails (Coil) and APK icons
- [x] Grid view, search (recursive, by name), properties dialog (size of folder, checksums)
- [x] Bookmarks and recent folders (local and network, never vault or archive paths)
- [ ] Tabs or dual-pane on tablets
- [x] Settings screen (theme, dynamic colors, view, files, app lock, about)
- [x] Archives: browse and extract zip, 7z, tar(.gz/.bz2/.xz), gz/bz2/xz; create zip/7z/tar*; AES-256 zip passwords
- [ ] RAR: only possible via the non-free UnRAR license (junrar); needs a licensing decision
- [x] File encryption (.age, interoperable with age/rage), encrypted private vault, app lock (PIN + fingerprint)
- [x] Unlock the vault with fingerprint (Keystore-wrapped master key); vault backup & restore (.lxvault)
- [x] Built-in text editor (line numbers, find/replace, undo/redo, encoding and line-ending aware, works in the vault)
- [x] Recycle bin: per-volume hidden bin (instant moves), restore, delete forever, auto-purge after 30 days
- [ ] Root access (optional, via libsu)

## v0.3: network protocols
Each is a `VirtualFileSystem` plus a saved-connection entry (credentials in Android Keystore-encrypted storage).

| Protocol        | Library                        | License    |
|-----------------|--------------------------------|------------|
| ✅ SFTP / SSH   | sshj                           | Apache-2.0 |
| ✅ FTP / FTPS   | Apache Commons Net             | Apache-2.0 |
| ✅ SMB 2/3       | smbj                           | Apache-2.0 |
| ✅ WebDAV         | OkHttp (hand-written client)   | Apache-2.0 |

Also: open remote files by streaming them to cache, and an "edit and upload back" flow.

## v0.4+: cloud storage
All through public REST APIs with OkHttp + AppAuth (browser-based OAuth). No vendor SDKs, so F-Droid accepts it.

- WebDAV-based: **Nextcloud, ownCloud, Koofr, Box (WebDAV), Seafile (WebDAV)**: almost free once WebDAV exists
- **S3-compatible**: AWS S3, Backblaze B2, Cloudflare R2, Wasabi, MinIO (SigV4 signing)
- **Google Drive**, **Dropbox**, **OneDrive / SharePoint** (Microsoft Graph), **pCloud**, **Yandex Disk**, **Box**
- Not feasible: iCloud Drive and Proton Drive (no public API); MEGA needs its native C++ SDK (investigate later)

Alternative worth evaluating: embedding **rclone** (MIT, 70+ providers) as a cloud engine, as Round Sync does.

### Known hurdles
- **Google Drive's full `drive` scope is "restricted".** Public apps using it must pass Google's OAuth verification
  and a yearly third-party security assessment. Options: let users supply their own client ID, use rclone, or budget for it.
- Each cloud provider needs a registered OAuth app/client ID. Client IDs for installed apps are not secret,
  but keep them in one config file.

## Publishing checklist
- [ ] Release signing config (keystore kept out of git; secrets via `keystore.properties` / CI secrets)
- [ ] Real launcher icon and screenshots in `fastlane/metadata/android/en-US/images/`
- [ ] **F-Droid:** tag releases (`v0.1.0`), bump `versionCode` each release, open a merge request to
      [fdroiddata](https://gitlab.com/fdroid/fdroiddata). F-Droid builds from source and signs with its own key
      unless the build is reproducible.
- [ ] **Google Play:** developer account; new personal accounts must run a closed test (12+ testers for 14 days)
      before production; fill in the *All files access* permission declaration (file managers are an allowed use)
      and the Data safety form (no data collected).
- [ ] Translations via Weblate (Hosted Weblate is free for libre projects)
