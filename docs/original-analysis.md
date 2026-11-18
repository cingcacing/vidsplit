# Original app analysis — `com.psma.videosplitter` (Video Splitter 5.3.1)

Static analysis of the APK shipped on Google Play. The artifact used here was
`_dl/apk/orig/videosplitter.apk` (17.4 MB, package `com.psma.videosplitter`,
versionCode 48, versionName 5.3.1, minSdk 24, targetSdk 36, compileSdk 36,
signed distribution build).

Tooling: `aapt2`, `apktool 2.9.3`, `jadx`, and REA analysis notes. The Android
provider requires a caller-supplied `jadx-headless-mcp` JAR (`REA_JADX_MCP_JAR`);
the analysis used the same underlying tools directly. This is a static analysis;
the app was not executed.

## Identity

| Field | Value |
|---|---|
| Package | `com.psma.videosplitter` |
| Label | Video Splitter |
| Version | 5.3.1 (48) |
| Launch activity | `com.psma.videosplitter.revamp.ComposeMainActivity` |
| Application class | `com.psma.videosplitter.revamp.RevampApplication` |
| UI toolkit | Jetpack Compose + Material 3 |
| Min / target SDK | 24 / 36 |
| Split APK stamp | `base__abi,base__density` etc. — native libs ship as ABI splits |
| DEX | 4 files, ~32 MB uncompressed |

## Permissions

```
INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK
FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC
POST_NOTIFICATIONS, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED
READ_EXTERNAL_STORAGE (maxSdk 32), WRITE_EXTERNAL_STORAGE (maxSdk 29)
com.android.vending.BILLING
com.google.android.gms.permission.AD_ID
ACCESS_ADSERVICES_AD_ID / ATTRIBUTION / TOPICS
com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE
```

## Components

- 1 app activity (`ComposeMainActivity`), 1 app service
  (`KotlinVideoProcessingService`, foreground, data-sync type).
- AdMob: `AdActivity`, `AdService`, `NotificationHandlerActivity`,
  `OutOfContextTestingActivity`, `MobileAdsInitProvider`.
- Billing: `ProxyBillingActivity`, `ProxyBillingActivityV2`.
- Firebase: `FirebaseInitProvider`, `ComponentDiscoveryService`,
  Analytics/Measurement service + receiver, Crashlytics, Data Transport.
- WorkManager, Room, ProfileInstaller, AndroidX Startup, `FileProvider`.
- No exported deep links except the normal launcher intent.

## Processing engine

- `com.arthenica.ffmpegkit` is bundled (FFmpegKit wrappers). The version string
  in resources is `shipped_ffmpeg_version = v4.4-dev-416`.
- A custom JNI bridge builds the argv:
  `com.psma.videosplitter.revamp.data.processing.JniNativeFfmpegCommandBridge`
  calls `System.loadLibrary("native-lib")` and `nativeBuildCommand(...)`.
- The request model `u0.C2873E` carries: branch, input path, output path,
  start/end/duration, segment count, segment time, output dimension cap, HDR
  transfer, watermark path, watermark flag, subtitle handling, and misc flags.
- Branch values (`u0.AbstractC2887j`):
  `copy`, `copy-max`, `custom`, `custom-copy`, `custom-max`,
  `single`, `single-copy`, `single-max`.
- HDR transfer enum (`u0.c0`): `sdr`, `hlg`, `smpte2084`, `unknown`.
- Subtitle enum (`u0.EnumC2895s`): `mov_text`, `copy`, `drop`.
- The base APK from the mirror has no `lib/` directory (ABI splits), so the exact
  ffmpeg argv inside `libnative-lib.so` could not be read. The parameters above,
  plus ffmpeg's standard `-ss/-to/-t/-c copy/-map`, fully describe the command
  shape.

## Split modes (from UI resources)

- **WhatsApp split**: 30 / 60 / 90 second presets ("Split video according to
  your choice").
- **Custom split**: choose the number of clips; per-clip duration shown.
- **Single split / trim**: start and end time, frame strip preview, trim handles.
- Output folder per job (`output_folders.folderName`), saved-video library
  ("My Stuff"), download/share to WhatsApp, Instagram, Facebook, other.
- Processing queue, per-segment progress, estimated time, notifications,
  wake lock, cancel confirmation.
- Storage: Room DB `video_splitter.db` with tables
  `source_videos`, `splits`, `processing_jobs`, `processing_segments`,
  `output_folders`, `output_videos`. Output publishing through MediaStore.
- Source video access uses persisted URIs + MediaStore IDs.

## Monetisation

- AdMob app id `ca-app-pub-2822219579722585~9001968842`.
- Ad units:
  - `ca-app-pub-2822219579722585/3184918537`
  - `ca-app-pub-2822219579722585/3861322805`
  - `ca-app-pub-2822219579722585/5936821802`
  - `ca-app-pub-2822219579722585/7923980456`
  - `ca-app-pub-2822219579722585/9106786138`
- Ad formats referenced in resources: app-open, interstitial, native, rewarded
  ("Watch Ad"), banner upsell.
- Billing: weekly and yearly subscriptions plus legacy monthly/yearly and a
  lifetime "No Ads" product. Premium removes ads and watermark, and unlocks
  premium content. Offer/countdown UI (50% off, "2 days left").
- Strings confirm the gating: `no_ads`, `no_watermark`, `remove_watermark_go_premium`,
  `premium_weekly_plan`, `premium_yearly_plan`, `premium_lifetime_no_ads`.

## Telemetry / network

- Firebase Analytics (`app-measurement.com`), Crashlytics
  (`firebase-settings.crashlytics.com`), Google Data Transport, install referrer.
- Feedback: Retrofit `POST store_feedback.php` against
  `https://prod-api.backendcore.com`; support email `psma.satish@gmail.com`.
- Developer identity in resources: `photoshop+mobile+apps`;
  privacy/site link `https://photoshopmobileapps.wordpress.com`.
- Google Sign-In client id is present but no login screen is part of the core
  flow.

## Core UX flow (from strings)

1. Onboarding (5 slides: easy split, WhatsApp status, custom split, single
   split/trim, sharing).
2. Home: choose video + mode, recent/old videos, premium banner.
3. Editor: video preview, mode tabs (WhatsApp / Custom / Single), duration
   inputs, clip count, estimated time, start processing.
4. Processing: foreground service, progress %, time remaining, cancel.
5. Result: folder with clips, preview, multi-select, save to gallery, share,
   download all.
6. My Stuff: folders, search, delete, open.
7. Premium screen: weekly/yearly plans, offers, restore purchases.

## What the clean clone keeps vs drops

| Capability | Original | Clone |
|---|---|---|
| Split into N parts | yes | yes |
| Split every 30/60/90/custom seconds | yes | yes |
| Trim one start–end range | yes | yes |
| Lossless copy split | yes (ffmpeg `copy`) | yes (`MediaExtractor`/`MediaMuxer`) |
| HDR / subtitle policy controls | yes | no (streams are copied as-is) |
| Output resolution cap | yes | no |
| Foreground service + notification | yes | in-activity worker + progress sheet |
| Saved-video library / folders | yes (Room) | gallery folder `Movies/VideoSplitter` |
| Ads, Premium, Billing | yes | removed |
| Watermark | yes on free tier | removed |
| Analytics / Crashlytics / feedback API | yes | removed |
| Internet permission | yes | **none** |