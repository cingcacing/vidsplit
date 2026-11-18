# Third-party binaries

## FFmpegKit

- Artifact: `com.arthenica:ffmpeg-kit-video:6.0-2`
- Obtained from: Maven Central (mirror `maven.aliyun.com`), AAR `ffmpeg-kit-video-6.0-2.aar`
- License: **GNU Lesser General Public License v3.0** (see `app/src/main/assets/licenses/`)
- Source: https://github.com/arthenica/ffmpeg-kit (archived upstream)
- Bundled ABIs: `arm64-v8a`, `x86_64`
- Purpose: split/trim/re-encode engine (`FfmpegEngine`)

The AAR is redistributed unmodified inside this APK. Its license files ship in
`assets/licenses/`. FFmpeg and the optional libraries it contains keep their own
licenses (also listed there). No GPL-only encoders (x264/x265) are included in the
`video` package; re-encode uses MediaCodec or MPEG-4.

## smart-exception-java / smart-exception-common

- `com.arthenica:smart-exception-java:0.2.1`
- `com.arthenica:smart-exception-common:0.2.1`
- Apache-2.0 (ARTHENICA), runtime dependency of FFmpegKit.