package com.opensplit.videosplitter;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lossless, keyframe-aligned video splitter built only on framework APIs
 * (MediaExtractor + MediaMuxer). No ffmpeg, no native libraries, no ads.
 *
 * Each segment is re-muxed, not re-encoded, so the operation is fast and the
 * quality is untouched. Segment start times snap back to the previous keyframe.
 */
public final class Splitter {

    public static final int MODE_PARTS = 0;
    public static final int MODE_EVERY = 1;
    public static final int MODE_TRIM = 2;

    private static final String TAG = "Splitter";

    public interface Progress {
        void onProgress(int percent, String label);
    }

    public interface Cancel {
        boolean isCancelled();
    }

    public static final class Seg {
        public final long startMs;
        public final long endMs;

        public Seg(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    public static final class Out {
        public final Uri uri;
        public final String name;
        public final long durationMs;
        public final long sizeBytes;

        Out(Uri uri, String name, long durationMs, long sizeBytes) {
            this.uri = uri;
            this.name = name;
            this.durationMs = durationMs;
            this.sizeBytes = sizeBytes;
        }
    }

    public static final class CancelledException extends Exception {
        public CancelledException() {
            super("Cancelled");
        }
    }

    private Splitter() {
    }

    public static List<Seg> plan(int mode, long durMs, long a, long b) {
        ArrayList<Seg> out = new ArrayList<>();
        if (durMs <= 300) {
            return out;
        }
        if (mode == MODE_PARTS) {
            int n = (int) Math.max(2, Math.min(60, a));
            for (int i = 0; i < n; i++) {
                long s = durMs * i / n;
                long e = (i == n - 1) ? durMs : durMs * (i + 1) / n;
                if (e - s >= 200) {
                    out.add(new Seg(s, e));
                }
            }
        } else if (mode == MODE_EVERY) {
            long step = Math.max(1000, a);
            long s = 0;
            while (s < durMs) {
                long e = Math.min(durMs, s + step);
                if (durMs - e < 900) {
                    e = durMs;
                }
                out.add(new Seg(s, e));
                s = e;
            }
        } else {
            long s = Math.max(0, a);
            long e = Math.min(durMs, b);
            if (e - s >= 200) {
                out.add(new Seg(s, e));
            }
        }
        return out;
    }

    public static List<Out> split(Context ctx, Uri uri, List<Seg> segs, String baseName,
                                  String folder, int rotationDegrees, Progress cb, Cancel cancel)
            throws Exception {
        if (segs.isEmpty()) {
            throw new IOException("No segments to process");
        }
        ContentResolver resolver = ctx.getContentResolver();
        ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r");
        if (pfd == null) {
            throw new IOException("Cannot open the selected video");
        }
        AssetFileDescriptor afd = null;
        File tmpDir = new File(ctx.getCacheDir(), "split_work");
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            pfd.close();
            throw new IOException("Cannot create work directory");
        }
        // Clean leftovers from previous runs.
        File[] old = tmpDir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        ArrayList<Out> results = new ArrayList<>();
        try {
            afd = resolver.openAssetFileDescriptor(uri, "r");
            long fdOffset = 0;
            long fdLength = -1;
            if (afd != null) {
                fdOffset = afd.getStartOffset();
                fdLength = afd.getDeclaredLength();
                if (fdLength == AssetFileDescriptor.UNKNOWN_LENGTH) {
                    long stat = pfd.getStatSize();
                    fdLength = stat > 0 ? stat - fdOffset : -1;
                }
            }

            int total = segs.size();
            String ext = probeExtension(resolver, pfd.getFileDescriptor(), fdOffset, fdLength);
            ArrayList<File> temps = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            ArrayList<Long> durs = new ArrayList<>();
            try {
                for (int i = 0; i < total; i++) {
                    if (cancel != null && cancel.isCancelled()) {
                        throw new CancelledException();
                    }
                    Seg seg = segs.get(i);
                    final int index = i;
                    String fileName = outputName(baseName, segs.size(), ext, i);
                    File outFile = new File(tmpDir, fileName);
                    int segStartPct = (int) (i * 90L / total);
                    int segEndPct = (int) ((i + 1) * 90L / total);
                    cb.onProgress(segStartPct, "Clip " + (i + 1) + " of " + total);

                    remux(pfd.getFileDescriptor(), fdOffset, fdLength, seg, outFile,
                            rotationDegrees, cancel,
                            new Progress() {
                                @Override
                                public void onProgress(int p, String label) {
                                    int mapped = segStartPct + (segEndPct - segStartPct) * p / 100;
                                    cb.onProgress(Math.min(89, mapped),
                                            "Clip " + (index + 1) + " of " + total);
                                }
                            });
                    if (cancel != null && cancel.isCancelled()) {
                        throw new CancelledException();
                    }
                    temps.add(outFile);
                    names.add(fileName);
                    durs.add(seg.endMs - seg.startMs);
                }

                // Only publish after every clip was produced, so a cancel or a
                // failure never leaves half a folder in the gallery.
                for (int i = 0; i < temps.size(); i++) {
                    if (cancel != null && cancel.isCancelled()) {
                        throw new CancelledException();
                    }
                    cb.onProgress(90 + (10 * (i + 1) / Math.max(1, temps.size())),
                            "Saving clip " + (i + 1) + " of " + temps.size());
                    File f = temps.get(i);
                    Uri published = publish(ctx, f, names.get(i), ext, folder);
                    results.add(new Out(published, names.get(i), durs.get(i), f.length()));
                }
                cb.onProgress(100, "Done");
                return results;
            } finally {
                for (File f : temps) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (IOException ignored) {
                }
            }
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String outputName(String base, int count, String ext, int index) {
        if (count == 1) {
            return base + "_trim." + ext;
        }
        return String.format(Locale.US, "%s_part%02d.%s", base, index + 1, ext);
    }

    private static String probeExtension(ContentResolver resolver, java.io.FileDescriptor fd,
                                         long offset, long length) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(fd, offset, length);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) {
                    continue;
                }
                if (mime.startsWith("video/")) {
                    return isWebmCodec(mime) ? "webm" : "mp4";
                }
            }
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    return isWebmCodec(mime) ? "webm" : "mp4";
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "probeExtension failed", e);
        } finally {
            ex.release();
        }
        return "mp4";
    }

    private static boolean isWebmCodec(String mime) {
        return mime.equals("video/x-vnd.on2.vp8")
                || mime.equals("video/x-vnd.on2.vp9")
                || mime.equals("video/av01")
                || mime.equals("audio/opus")
                || mime.equals("audio/vorbis");
    }

    private static void remux(java.io.FileDescriptor fd, long offset, long length,
                              Seg seg, File outFile, int rotationDegrees,
                              Cancel cancel, Progress cb) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        int outFormat = outFile.getName().endsWith(".webm")
                ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
        try {
            extractor.setDataSource(fd, offset, length);
            int trackCount = extractor.getTrackCount();
            int[] outTrack = new int[trackCount];
            ArrayList<Integer> selected = new ArrayList<>();
            for (int i = 0; i < trackCount; i++) {
                MediaFormat fmt = extractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime == null || (!mime.startsWith("video/") && !mime.startsWith("audio/"))) {
                    outTrack[i] = -1;
                    continue;
                }
                extractor.selectTrack(i);
                outTrack[i] = -2; // pending: added to muxer later
                selected.add(i);
            }
            if (selected.isEmpty()) {
                throw new IOException("Unsupported video: no audio/video track");
            }
            muxer = new MediaMuxer(outFile.getAbsolutePath(), outFormat);
            if (rotationDegrees != 0) {
                try {
                    muxer.setOrientationHint(rotationDegrees);
                } catch (Exception ignored) {
                }
            }
            for (int i : selected) {
                MediaFormat fmt = extractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") && rotationDegrees != 0) {
                    try {
                        fmt.setInteger(MediaFormat.KEY_ROTATION, rotationDegrees);
                    } catch (Exception ignored) {
                    }
                }
                try {
                    outTrack[i] = muxer.addTrack(fmt);
                } catch (Exception e) {
                    Log.w(TAG, "addTrack failed for " + fmt, e);
                    outTrack[i] = -1;
                    extractor.unselectTrack(i);
                }
            }
            muxer.start();

            long startUs = seg.startMs * 1000L;
            long endUs = seg.endMs * 1000L;
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(1 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long baseUs = -1;
            int lastPct = -1;
            while (!(cancel != null && cancel.isCancelled())) {
                int inTrack = extractor.getSampleTrackIndex();
                if (inTrack < 0) {
                    break;
                }
                long t = extractor.getSampleTime();
                if (t < 0) {
                    break;
                }
                if (t >= endUs) {
                    break;
                }
                int dst = outTrack[inTrack];
                if (dst < 0) {
                    extractor.advance();
                    continue;
                }
                if (baseUs < 0) {
                    baseUs = t;
                }
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    break;
                }
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = Math.max(0, t - baseUs);
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(dst, buffer, info);
                extractor.advance();

                if (endUs > startUs) {
                    int p = (int) (Math.max(0, t - startUs) * 100 / (endUs - startUs));
                    if (p != lastPct && p % 2 == 0) {
                        lastPct = p;
                        cb.onProgress(Math.min(99, p), null);
                    }
                }
            }
            if (cancel != null && cancel.isCancelled()) {
                throw new CancelledException();
            }
        } finally {
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
            extractor.release();
        }
        if (outFile.length() < 1024) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
            throw new IOException("Empty output produced");
        }
    }

    private static Uri publish(Context ctx, File file, String name, String ext, String folder)
            throws IOException {
        ContentResolver resolver = ctx.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, ext.equals("webm") ? "video/webm" : "video/mp4");
        String dir = Environment.DIRECTORY_MOVIES + "/VideoSplitter";
        if (folder != null && !folder.trim().isEmpty()) {
            dir = dir + "/" + folder.trim();
        }
        values.put(MediaStore.Video.Media.RELATIVE_PATH, dir);
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri item = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (item == null) {
            throw new IOException("Could not create a gallery entry");
        }
        try {
            InputStream in = new FileInputStream(file);
            OutputStream out = resolver.openOutputStream(item);
            if (out == null) {
                throw new IOException("Could not open output stream");
            }
            byte[] buf = new byte[1 << 16];
            int r;
            try {
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
            } finally {
                in.close();
                out.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(item, done, null, null);
            return item;
        } catch (IOException e) {
            resolver.delete(item, null, null);
            throw e;
        }
    }


    /** Output extension for the selected input, based on its first audio/video mime. */
    public static String extensionFor(Context ctx, Uri uri) {
        ContentResolver r = ctx.getContentResolver();
        ParcelFileDescriptor pfd = null;
        AssetFileDescriptor afd = null;
        try {
            pfd = r.openFileDescriptor(uri, "r");
            afd = r.openAssetFileDescriptor(uri, "r");
            long off = 0;
            long len = -1;
            if (afd != null) {
                off = afd.getStartOffset();
                len = afd.getDeclaredLength();
                if (len == AssetFileDescriptor.UNKNOWN_LENGTH && pfd != null) {
                    long stat = pfd.getStatSize();
                    len = stat > 0 ? stat - off : -1;
                }
            }
            if (pfd != null) {
                return probeExtension(r, pfd.getFileDescriptor(), off, len);
            }
        } catch (Exception ignored) {
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (IOException ignored) {
                }
            }
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
            }
        }
        return "mp4";
    }

    public static String outputNameFor(String base, int count, String ext, int index) {
        return outputName(base, count, ext, index);
    }

    public static Uri publishFile(Context ctx, File file, String name, String ext, String folder)
            throws IOException {
        return publish(ctx, file, name, ext, folder);
    }

    /** Sanitizes a user supplied name or folder for MediaStore. */
    public static String safeName(String name) {
        if (name == null) {
            return "video";
        }
        String base = name.trim();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replaceAll("[^A-Za-z0-9._ -]+", "_").trim();
        if (base.isEmpty()) {
            base = "video";
        }
        if (base.length() > 60) {
            base = base.substring(0, 60).trim();
        }
        return base;
    }
}
