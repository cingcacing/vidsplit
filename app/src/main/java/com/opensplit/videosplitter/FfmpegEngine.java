package com.opensplit.videosplitter;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Split engine backed by ffmpeg-kit (the same family the original app uses).
 *
 * Copy mode is tried first: for union of the original's copy / copy-max
 * branches. If the container/codec combination cannot be muxed, it falls back
 * to a MediaCodec H.264 encode and finally to MPEG-4 so a job never ends with
 * an empty file.
 */
public final class FfmpegEngine {

    private static final String TAG = "FfmpegEngine";

    private FfmpegEngine() {
    }

    public static List<Splitter.Out> split(Context ctx, Uri uri, List<Splitter.Seg> segs,
                                           String baseName, String folder, int rotation,
                                           int maxDim, boolean precise,
                                           Splitter.Progress cb, Splitter.Cancel cancel)
            throws Exception {
        if (segs.isEmpty()) {
            throw new IOException("No segments to process");
        }
        File tmpDir = new File(ctx.getCacheDir(), "ffmpeg_work");
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            throw new IOException("Cannot create work directory");
        }
        File[] old = tmpDir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }

        String ext = "mp4";
        // ffmpeg cannot consistently reopen SAF descriptors (ids may be cleared
        // between sessions), so stage the input as a real file first.
        File inputFile = new File(tmpDir, "input.bin");
        cb.onProgress(0, "Preparing video…");
        copyInput(ctx, uri, inputFile, cancel);
        String input = inputFile.getAbsolutePath();
        ArrayList<File> temps = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        ArrayList<Long> durs = new ArrayList<>();
        ArrayList<Splitter.Out> results = new ArrayList<>();

        try {
            int total = segs.size();
            for (int i = 0; i < total; i++) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new Splitter.CancelledException();
                }
                Splitter.Seg seg = segs.get(i);
                final int index = i;
                final int basePct = i * 90 / total;
                final int spanPct = Math.max(1, 90 / total);
                String name = Splitter.outputNameFor(baseName, total, ext, i);
                File out = new File(tmpDir, name);

                cb.onProgress(basePct, "Clip " + (i + 1) + " of " + total);
                boolean needsEncode = precise || maxDim > 0;
                boolean ok;
                if (!needsEncode) {
                    ok = run(ctx, copyArgs(input, out, seg, rotation), seg,
                            (p, label) -> cb.onProgress(Math.min(89, basePct + spanPct * p / 100),
                                    "Clip " + (index + 1) + " of " + total), cancel);
                } else {
                    ok = false;
                }
                if (!ok) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    ok = run(ctx, encodeArgs(input, out, seg, "h264_mediacodec", "6M", maxDim),
                            seg, (p, label) -> cb.onProgress(
                                    Math.min(89, basePct + spanPct * p / 100),
                                    "Re-encoding " + (index + 1) + " of " + total), cancel);
                }
                if (!ok) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    ok = run(ctx, encodeArgs(input, out, seg, "mpeg4", null, maxDim),
                            seg, (p, label) -> cb.onProgress(
                                    Math.min(89, basePct + spanPct * p / 100),
                                    "Re-encoding " + (index + 1) + " of " + total), cancel);
                }
                if (!ok) {
                    throw new IOException("ffmpeg could not produce a clip (codec/container unsupported)");
                }
                if (out.length() < 1024) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    throw new IOException("Empty output produced by ffmpeg");
                }
                temps.add(out);
                names.add(name);
                durs.add(seg.endMs - seg.startMs);
            }

            for (int i = 0; i < temps.size(); i++) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new Splitter.CancelledException();
                }
                cb.onProgress(90 + 10 * (i + 1) / Math.max(1, temps.size()),
                        "Saving clip " + (i + 1) + " of " + temps.size());
                File f = temps.get(i);
                Uri published = Splitter.publishFile(ctx, f, names.get(i), ext, folder);
                results.add(new Splitter.Out(published, names.get(i), durs.get(i), f.length()));
            }
            cb.onProgress(100, "Done");
            return results;
        } finally {
            for (File f : temps) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            inputFile.delete();
        }
    }

    private static void copyInput(Context ctx, Uri uri, File dest, Splitter.Cancel cancel)
            throws IOException, Splitter.CancelledException {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("Cannot open the selected video");
        }
        FileOutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[1 << 20];
        try {
            int r;
            long copied = 0;
            while ((r = in.read(buf)) > 0) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new Splitter.CancelledException();
                }
                out.write(buf, 0, r);
                copied += r;
                if (copied % (64L << 20) < r) {
                    out.flush();
                }
            }
            out.flush();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
        if (dest.length() < 1024) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            throw new IOException("Cannot read the selected video");
        }
    }

    private static String[] copyArgs(String input, File out, Splitter.Seg seg, int rotation) {
        ArrayList<String> a = new ArrayList<>();
        a.add("-y");
        a.add("-hide_banner");
        a.add("-loglevel");
        a.add("error");
        a.add("-ss");
        a.add(sec(seg.startMs));
        a.add("-t");
        a.add(sec(seg.endMs - seg.startMs));
        a.add("-i");
        a.add(input);
        a.add("-map");
        a.add("0:v:0?");
        a.add("-map");
        a.add("0:a:0?");
        a.add("-map_metadata");
        a.add("0");
        a.add("-c");
        a.add("copy");
        a.add("-avoid_negative_ts");
        a.add("make_zero");
        a.add("-movflags");
        a.add("+faststart");
        if (rotation == 90 || rotation == 180 || rotation == 270) {
            a.add("-metadata:s:v:0");
            a.add("rotate=" + rotation);
        }
        a.add(out.getAbsolutePath());
        return a.toArray(new String[0]);
    }

    private static String[] encodeArgs(String input, File out, Splitter.Seg seg,
                                       String videoCodec, String bitrate, int maxDim) {
        ArrayList<String> a = new ArrayList<>();
        a.add("-y");
        a.add("-hide_banner");
        a.add("-loglevel");
        a.add("error");
        a.add("-ss");
        a.add(sec(seg.startMs));
        a.add("-t");
        a.add(sec(seg.endMs - seg.startMs));
        a.add("-i");
        a.add(input);
        a.add("-map");
        a.add("0:v:0?");
        a.add("-map");
        a.add("0:a:0?");
        a.add("-c:v");
        a.add(videoCodec);
        if (bitrate != null) {
            a.add("-b:v");
            a.add(bitrate);
        } else {
            a.add("-q:v");
            a.add("4");
            a.add("-pix_fmt");
            a.add("yuv420p");
        }
        if (maxDim > 0) {
            a.add("-vf");
            a.add("scale=w='min(iw," + maxDim + ")':h='min(ih," + maxDim
                    + ")':force_original_aspect_ratio=decrease");
            a.add("-pix_fmt");
            a.add("yuv420p");
        }
        a.add("-c:a");
        a.add("aac");
        a.add("-b:a");
        a.add("128k");
        a.add("-movflags");
        a.add("+faststart");
        a.add(out.getAbsolutePath());
        return a.toArray(new String[0]);
    }

    private interface InnerProgress {
        void on(int percent, String label);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static boolean run(Context ctx, String[] args, Splitter.Seg seg,
                               InnerProgress progress, Splitter.Cancel cancel) {
        final long expected = Math.max(1, seg.endMs - seg.startMs);
        final CountDownLatch done = new CountDownLatch(1);
        final FFmpegSession[] holder = new FFmpegSession[1];
        try {
            holder[0] = FFmpegKit.executeWithArgumentsAsync(
                    args,
                    session -> done.countDown(),
                    log -> {
                    },
                    stats -> {
                        if (progress != null && stats != null) {
                            int p = (int) Math.min(99, Math.max(0, stats.getTime() * 100 / expected));
                            progress.on(p, null);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "ffmpeg start failed", t);
            return false;
        }
        try {
            while (!done.await(250, TimeUnit.MILLISECONDS)) {
                if (cancel != null && cancel.isCancelled()) {
                    try {
                        FFmpegKit.cancel(holder[0].getSessionId());
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            FFmpegSession s = holder[0];
            ReturnCode rc = s.getReturnCode();
            if (ReturnCode.isSuccess(rc)) {
                return true;
            }
            if (ReturnCode.isCancel(rc)) {
                return false;
            }
            String out = s.getOutput();
            if (out != null && !out.isEmpty()) {
                int from = Math.max(0, out.length() - 400);
                Log.w(TAG, "ffmpeg failed: " + out.substring(from));
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "ffmpeg result failed", t);
            return false;
        }
    }

    private static String sec(long ms) {
        return String.format(Locale.US, "%.3f", ms / 1000.0);
    }
}