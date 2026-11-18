# Security audit — VideoSplitterClean.apk 3.4.0

Artifact: `VideoSplitterClean.apk`
SHA-256: `f0eb7d8cb83ce1b9476b2729bbcf19bdbbf04734dc4575012b4cfb994c15abd9`
Package: `com.opensplit.videosplitter` 3.4.0 (code 6)

Re-audited 2026-11-18 after the in-app player changes.
Permission, component, DEX and native inventories are unchanged from the 3.2.0
audit; the scan below is on the 3.4.0 artifact.

Scope: static inspection of the shipped APK, its DEX, manifest, native libraries,
and the bundled FFmpegKit AAR. No runtime sandbox escape test was performed.
This is evidence of what the artifact contains, not a mathematical guarantee.

## 1. Permissions (complete list)

```
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_DATA_SYNC
android.permission.POST_NOTIFICATIONS
android.permission.WAKE_LOCK
```

Not requested: `INTERNET`, `ACCESS_NETWORK_STATE`, storage permissions
(`READ/WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_*`, `MANAGE_EXTERNAL_STORAGE`),
contacts, SMS, call log, location, camera, microphone, `QUERY_ALL_PACKAGES`,
`SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES`, accessibility, device admin,
notification listener, VPN, `PACKAGE_USAGE_STATS`, AD_ID.

Consequence: the app cannot open sockets, cannot read other apps' data, cannot
record audio/video, cannot read messages/contacts/location, cannot install apps.

## 2. Components

| Kind | Name | Exported |
|---|---|---|
| activity | `.MainActivity` | **true** (launcher + `SEND video/*`) |
| activity | `.LibraryActivity` | false |
| activity | `.OnboardingActivity` | false |
| service | `.SplitService` (`dataSync`, foreground) | false |

No receivers, no providers, no accessibility/notification-listener services,
no boot receivers, no `ContentProvider`.

`application`: `allowBackup=false`, `usesCleartextTraffic=false`,
`debuggable` absent (false), `extractNativeLibs=true`.

## 3. Code inventory

One DEX. Packages present: `android.*`, `java.*`, `dalvik.*`,
`com.arthenica.*` (FFmpegKit), `com.opensplit.videosplitter` (11 classes:
MainActivity, LibraryActivity, OnboardingActivity, SplitService, QueueManager,
Splitter, FfmpegEngine, ShareSheet, Job, Thumbs, Ui).

No ad/analytics/billing SDKs. String scan of all DEX for
`okhttp|firebase|crashlytics|admob|googleads|billingclient` → **0 hits**.
No URLs in DEX or app source.

Dangerous-API scan (source + DEX):

| Check | Result |
|---|---|
| `Runtime.exec` / `ProcessBuilder` / shell execution | none |
| `DexClassLoader` / `PathClassLoader` / dynamic code load | none |
| Reflection (`Class.forName`, `getDeclaredMethod`, `invoke`) | none |
| `WebView` / `addJavascriptInterface` | none |
| `AccessibilityService` / key event capture | none |
| `MediaProjection` / screen capture | none |
| Clipboard read (`getPrimaryClip`) | none |
| SMS / contacts / IMEI / device ID APIs | none |
| `AudioRecord` / camera APIs | none |
| Logging of user content | only ffmpeg error text and stack traces |

## 4. Native libraries

20 `.so` files = 10 per ABI (`arm64-v8a`, `x86_64`), all from FFmpegKit.

`libavformat.so` imports `socket`, `connect`, `getaddrinfo` (FFmpeg network
protocols). They are unusable: **no `INTERNET` permission**, and the app only
feeds local staged files to ffmpeg. No user-supplied URL ever reaches ffmpeg.

No imports of `system`, `popen`, `execve`, `fork`, `ptrace` in the audited
libraries. `libavcodec.so` imports `dlopen`/`dlsym` (normal FFmpeg optional-lib
lookup) and `open`/`openat`.

FFmpeg version string: `n6.0` (`Lavc60.3.100`), FFmpegKit `6.0-2`.

## 5. Third-party provenance

`ffmpeg-kit-video-6.0-2.aar`:
`a280ef2f2e3725cf0749708abf16aedd1caf271f12cc12f4824d5d779ef7dc95`

The same SHA-256 was obtained independently from two mirrors
(`maven.aliyun.com` and `repo.huaweicloud.com`). Individual contents also match:

- `classes.jar` `f1657313ccff4de8e6acbdd276a06754e8038dae0c4aebb06265dde7c31c9a3e`
- `arm64-v8a/libavcodec.so` `89bc6d2a9a22ee205e73fbba6989529e96198ab80ebc276204f945b3c5c14bb8`
- `arm64-v8a/libffmpegkit.so` `cbbf142ccd0ff8b1d5cd1875f1f2a53100ddd2fae03d7970a3387390407fef2d`

The `libffmpegkit.so` inside the APK is byte-identical to the AAR.
Dependencies `smart-exception-java` / `smart-exception-common` 0.2.1 are
ARTHENICA Apache-2.0 runtime jars; scan finds no process/network APIs.

## 6. Malware scan

ClamAV (current signatures) on the APK including decompressed native libraries:

```
Infected files: 0
Data scanned: 99.98 MiB
```

Caveat: signature scanners detect known families; they do not prove absence of a
targeted backdoor. The structural checks above are the stronger signal here.

## 7. Data flow

1. User picks a video (SAF) or shares one in (`SEND`).
2. Input is copied to the app cache for ffmpeg.
3. Outputs are written to app cache, then published to
   `Movies/VideoSplitter/<folder>/` through MediaStore.
4. Temporary files are deleted; cancel leaves no published partials.
5. No upload, no telemetry, no network code.

## 8. Residual risks (honest)

1. **FFmpeg 6.0 decoder CVEs.** FFmpeg 6.0 (2023) has known memory-safety issues
   in some demuxers/decoders; ffmpeg-kit was archived in 2025. A malicious video
   could potentially crash or exploit the app. The blast radius is limited by the
   app sandbox and the absence of network/contacts/location/mic permissions.
2. **Debug signing key.** The APK is signed with a locally generated debug key
   (`CN=Video Splitter Debug`, SHA-256 `57a55fcd...e8481c`). The keystore and
   password live in the project. Anyone with that key could sign a same-package
   update that Android would accept. Not malware, but an update-integrity risk.
3. **Exported SEND surface.** Any app can send a `video/*` URI to `MainActivity`.
   It will read metadata/thumbnail. This is a potential DoS with a hostile URI,
   not an exfiltration path.
4. Static analysis only: no runtime instrumentation, no fuzzing of the FFmpeg
   parsers, no sandbox-escape test.

## 9. Recommendations

- Build a **release-signed** APK with a private keystore kept outside the project;
  uninstall the debug build once before switching signatures.
- Keep `ABIS=arm64-v8a ./build.sh` and install only from your own signed artifact.
- If the threat model includes hostile media files, rebuild the engine from a
  maintained FFmpeg-kit fork with a newer FFmpeg (7.x) instead of 6.0-2.
- Re-run this audit after every dependency or FFmpeg change.

## 10. Reproduce

```bash
BT=$HOME/android-sdk/build-tools/34.0.0
$BT/aapt2 dump permissions VideoSplitterClean.apk
$BT/aapt2 dump xmltree VideoSplitterClean.apk --file AndroidManifest.xml
$BT/apksigner verify --verbose --print-certs VideoSplitterClean.apk
unzip -Z1 VideoSplitterClean.apk
clamscan --max-scansize=300M --max-filesize=60M VideoSplitterClean.apk
```