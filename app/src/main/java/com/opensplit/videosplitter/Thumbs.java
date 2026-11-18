package com.opensplit.videosplitter;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Size;

import java.util.ArrayList;
import java.util.List;

/** Thumbnail loader that also works for SAF document URIs (loadThumbnail often fails there). */
final class Thumbs {

    private Thumbs() {
    }

    static Bitmap load(Context c, Uri uri, int w, int h) {
        try {
            return c.getContentResolver().loadThumbnail(uri, new Size(w, h), null);
        } catch (Exception ignored) {
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, uri);
            return r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h);
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** First frame scaled to fit inside w x h without stretching. */
    static Bitmap frameFit(Context c, Uri uri, int w, int h) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        Bitmap raw = null;
        try {
            r.setDataSource(c, uri);
            raw = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (raw == null || raw.getWidth() <= 0 || raw.getHeight() <= 0) {
                return null;
            }
            float scale = Math.min((float) w / raw.getWidth(), (float) h / raw.getHeight());
            int nw = Math.max(1, Math.round(raw.getWidth() * scale));
            int nh = Math.max(1, Math.round(raw.getHeight() * scale));
            Bitmap out = Bitmap.createScaledBitmap(raw, nw, nh, true);
            if (out != raw) {
                raw.recycle();
            }
            return out;
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** Evenly spaced frames for the trim strip. */
    static List<Bitmap> strip(Context c, Uri uri, long durationMs, int count, int w, int h) {
        ArrayList<Bitmap> out = new ArrayList<>();
        if (durationMs <= 0 || count <= 0) {
            return out;
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, uri);
            for (int i = 0; i < count; i++) {
                long tUs = durationMs * 1000L * i / Math.max(1, count - 1);
                Bitmap b = r.getScaledFrameAtTime(tUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h);
                out.add(b);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}