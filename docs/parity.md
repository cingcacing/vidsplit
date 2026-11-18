# Feature parity — original vs clean clone v2

Original artifact: `com.psma.videosplitter` 5.3.1 (48), SHA-256
`aae02954e2acc8baf761e1fa5f772d03ff90715b6a526b3062c8f29983bf6742`,
analysed with REA/jadx. Evidence IDs refer to the exported bundle
`docs/rea-evidence-videosplitter.json`.

## Kept (working in the clone)

| Feature | Original evidence | Clone implementation |
|---|---|---|
| Pick any video (SAF) | `ComposeMainActivity` + media picker | `ACTION_OPEN_DOCUMENT` + persisted URI grant |
| Split into N equal parts | `u0.k.b` loops branches; strings “Custom Split” (`ev_95a1c0fb…d91888`) | `Splitter.plan(MODE_PARTS)` + slider 2–60 |
| Split every 30/60/90 s or custom | strings `onboarding_split_duration_desc`, WhatsApp presets (`ev_95a1c0fb…d91888`) | `MODE_EVERY` + presets + slider 5–300 s |
| Trim one start–end range | strings `instruction_single` (`ev_95a1c0fb…d91888`) | `MODE_TRIM` + two seekbars + frame strip |
| Frame strip preview | `single_split_frame_strip` string in original | 7 thumbnails via `MediaMetadataRetriever` |
| Per-job output folder | `output_folders` / `RELATIVE_PATH` in original Room DB | `Movies/VideoSplitter/<folder>/` via MediaStore |
| Queue + background processing | `KotlinVideoProcessingService` foreground service | `SplitService` (foreground, dataSync) + `QueueManager` |
| Progress + notification + cancel | `n0.l.a` callbacks; `u0.q.c` percent (`ev_6f11ec6f…08b27f7`, `ev_08c3c95b…eb97fb`) | statistics-equivalent progress per clip, notification, cancel |
| Job persistence across restart | `processing_jobs` table | JSON in SharedPreferences, interrupted jobs marked failed |
| Library / “My Stuff” | `saved_folders*` strings, `output_folders` table | `LibraryActivity`: group by folder, search, multi-select |
| Share clips | share sheet to WhatsApp/Instagram/Facebook | direct targets (WhatsApp/Status/Business, Instagram, Facebook, Telegram) + system chooser, single & multiple |
| WhatsApp Status | original shares to WhatsApp | dedicated Status entry with status intent + WhatsApp fallback |
| Multi-select share | original selection flow | library multi-select + per-row and queue-job share |
| Delete clips | `confirmation_delete_folder` flow | MediaStore delete with confirmation |
| Onboarding | 5 slides (`slide_1…slide_5`) | 4 pages, shown once |
| Video metadata (duration, resolution, size) | original editor header | `MediaMetadataRetriever` + `OpenableColumns` |
| Playback | in-app player in the original | in-app `VideoView` preview + `PlayerActivity` for the library |

## Removed on purpose

| Removed | Original evidence |
|---|---|
| AdMob (5 ad units, app-open/interstitial/native/rewarded) | `ca-app-pub-2822219579722585/*`, `AdActivity`, `AdService` |
| Watermark renderer | request flag + `watermarkPath` in `u0.E`; `no_watermark` premium string |
| Premium / billing / weekly-yearly-lifetime | `ProxyBillingActivity`, `premium_*` strings |
| Firebase Analytics / Crashlytics / Data Transport / install referrer | `FirebaseInitProvider`, `app-measurement.com` |
| Feedback API | `store_feedback.php` on `prod-api.backendcore.com` |
| `INTERNET` / `ACCESS_NETWORK_STATE` | original manifest |
| Ad ID / AD_SERVICES permissions | original manifest |

The clone requests **no** internet permission. The only runtime permissions are
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS`.
Storage goes through SAF + MediaStore, so no `READ/WRITE_EXTERNAL_STORAGE`,
no `READ_MEDIA_VIDEO`, no all-files access.

## Approximated / not implemented

| Original capability | Why it differs |
|---|---|
| HDR transfer, subtitle policy | Streams are copied as-is; subtitle tracks are dropped (no `mov_text` conversion). The original exposes SDR/HLG/PQ and `mov_text`/`copy`/`drop` parameters. |
| ffmpeg command shape | Original passes 19 parameters into a **native** `nativeBuildCommand`; that `.so` (`libnative-lib`) is not in the base APK (JNI inventory: 33 declarations, 0 native libraries, `ev_cb5e1143…3ba9b09a`). The clone now embeds **ffmpeg-kit-video 6.0-2** and implements the same `copy`-first / re-encode strategy itself. Exact internal argv of the original remains unobserved. |
| Estimated time remaining text | original exposes an ETA string in `u0.r`; clone shows percent only. |
| “Notify me when completed” choice | clone always posts a completion notification. |
| Queue reordering / multi-queue UI | clone processes FIFO, newest-first history. |

## Security posture

- `aapt2 dump permissions` → only foreground-service + notification permissions.
- Only `MainActivity` is exported (launcher). Service and library/onboarding
  activities are `exported="false"`.
- No WebView, no JS bridge, no dynamic code loading, no ad/analytics SDK.
- No file paths from external intents; media access is per-URI via SAF.
## Verified in v2 (Android 14 / API 34 emulator)

| Check | Result |
|---|---|
| Onboarding shown once, then main screen | pass |
| 25 s H.264/AAC → 6 parts | 6 files, 4.2–5.0 s each |
| 7 s interval → 4 clips | 4 files (7/7/7/4 s) |
| Trim full range → 1 clip | 1 file `*_trim.mp4` |
| Per-job folder | `Movies/VideoSplitter/sample/`, `.../long/` |
| Foreground service + notification | "Splitting video · long.mp4 · 82%", "Split complete" |
| Queue cancel (queued) | status `Cancelled`, no processing |
| Queue cancel (running, 59-clip job) | status `Cancelled`, folder file count unchanged (no partials) |
| Library groups/search/select-all | pass |
| Portrait rotation preserved | output side-data `rotation=90` |
| Crash / ANR | none |
| `INTERNET` permission | absent |


## v3 engine notes (ffmpeg-kit)

- Input is staged from the SAF URI to a cache file; ffmpeg then reads a normal path.
- Per segment: `-ss <start> -t <dur> -i input -map 0:v:0? -map 0:a:0? -c copy
  -avoid_negative_ts make_zero -movflags +faststart`.
- If copy fails or “Precise cuts”/resolution cap is selected:
  `-c:v h264_mediacodec -b:v 6M` (MediaCodec), then `-c:v mpeg4 -q:v 4` as last resort.
- Resolution cap: `scale=w='min(iw,N)':h='min(ih,N)':force_original_aspect_ratio=decrease`.
- Outputs are staged and only published to MediaStore after all segments succeed.
- Verified: H.264/AAC MP4, HEVC MP4, AV1 MP4, VP9+Opus WebM, H.264 MKV,
  480p re-encode (480×270 output), 6-clip queue, cancel mid-run.


## v3.1 share notes

- `ShareSheet` shows only installed targets (manifest `<queries>` for each package).
- Every share builds `ClipData` and adds `FLAG_GRANT_READ_URI_PERMISSION`, so
  receivers can read the MediaStore URI.
- Status shares **every selected clip** via `ACTION_SEND_MULTIPLE`; WhatsApp's
  status composer decides how many it accepts in one sequence.
- “More apps” uses `Intent.createChooser` — the standard Android share mechanism.
- Library thumbnails now load on a 2-thread pool; this fixed an ANR with 160+ clips.


## v3.4 playback notes

- All playback is in-app: inline preview + `PlayerActivity`.
- No `ACTION_VIEW` playback intents, no external player dependency.
- Both surfaces size the video from the source aspect ratio (rotation applied)
  and use play/pause + seek + time.
- Verified: preview plays inline (`00:04 / 00:25`); library Play opens
  `PlayerActivity` and plays the clip; no external activity is started.


## v3.3 notes

- Preview frame is sized from the video's own aspect ratio (rotation applied):
  landscape fills the card, portrait is letterboxed at up to 430 dp — no crop.
- WhatsApp Status now receives all selected clips at once; there is still no
  public API to force a status post, so WhatsApp's own screen is the final step.
- Verified: “Sharing 2 files” in the Android chooser for a 2-clip selection.
