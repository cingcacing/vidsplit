package com.opensplit.videosplitter;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One split job, persisted as JSON so the queue survives process death. */
public final class Job {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    public static final class Out {
        public String name;
        public String uri;
        public long durationMs;
        public long sizeBytes;

        Out() {
        }

        public Out(String name, String uri, long durationMs, long sizeBytes) {
            this.name = name;
            this.uri = uri;
            this.durationMs = durationMs;
            this.sizeBytes = sizeBytes;
        }
    }

    public String id = UUID.randomUUID().toString();
    public String sourceUri = "";
    public String displayName = "";
    public long durationMs;
    public int rotation;
    public int mode;              // Splitter.MODE_PARTS / MODE_EVERY / MODE_TRIM
    public long a;                // parts count, or seconds for EVERY
    public long b;                // trim end ms
    public String folder = "";
    public int maxDim;          // 0 = keep original size
    public boolean precise;     // re-encode for frame-accurate cuts
    public String status = QUEUED;
    public int progress;
    public String error;
    public long createdAt = System.currentTimeMillis();
    public final ArrayList<Out> outs = new ArrayList<>();

    public transient boolean cancelRequested;

    public boolean isActive() {
        return RUNNING.equals(status) || QUEUED.equals(status);
    }

    public List<Splitter.Seg> segments() {
        if (durationMs <= 0) {
            return new ArrayList<>();
        }
        if (mode == Splitter.MODE_PARTS) {
            return Splitter.plan(Splitter.MODE_PARTS, durationMs, a, 0);
        }
        if (mode == Splitter.MODE_EVERY) {
            return Splitter.plan(Splitter.MODE_EVERY, durationMs, a * 1000L, 0);
        }
        return Splitter.plan(Splitter.MODE_TRIM, durationMs, a, b);
    }

    public String modeLabel() {
        if (mode == Splitter.MODE_PARTS) {
            return a + " parts";
        }
        if (mode == Splitter.MODE_EVERY) {
            return a + "-sec split";
        }
        return "Trim";
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("sourceUri", sourceUri);
            o.put("displayName", displayName);
            o.put("durationMs", durationMs);
            o.put("rotation", rotation);
            o.put("mode", mode);
            o.put("a", a);
            o.put("b", b);
            o.put("folder", folder);
            o.put("maxDim", maxDim);
            o.put("precise", precise);
            o.put("status", status);
            o.put("progress", progress);
            o.put("error", error == null ? JSONObject.NULL : error);
            o.put("createdAt", createdAt);
            JSONArray arr = new JSONArray();
            for (Out out : outs) {
                JSONObject x = new JSONObject();
                x.put("name", out.name);
                x.put("uri", out.uri);
                x.put("durationMs", out.durationMs);
                x.put("sizeBytes", out.sizeBytes);
                arr.put(x);
            }
            o.put("outs", arr);
        } catch (Exception ignored) {
        }
        return o;
    }

    static Job fromJson(JSONObject o) {
        Job j = new Job();
        j.id = o.optString("id", j.id);
        j.sourceUri = o.optString("sourceUri", "");
        j.displayName = o.optString("displayName", "");
        j.durationMs = o.optLong("durationMs", 0);
        j.rotation = o.optInt("rotation", 0);
        j.mode = o.optInt("mode", Splitter.MODE_PARTS);
        j.a = o.optLong("a", 6);
        j.b = o.optLong("b", 0);
        j.folder = o.optString("folder", "");
        j.maxDim = o.optInt("maxDim", 0);
        j.precise = o.optBoolean("precise", false);
        j.status = o.optString("status", QUEUED);
        j.progress = o.optInt("progress", 0);
        j.error = o.isNull("error") ? null : o.optString("error", null);
        j.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        JSONArray arr = o.optJSONArray("outs");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x == null) {
                    continue;
                }
                Out out = new Out();
                out.name = x.optString("name", "");
                out.uri = x.optString("uri", "");
                out.durationMs = x.optLong("durationMs", 0);
                out.sizeBytes = x.optLong("sizeBytes", 0);
                j.outs.add(out);
            }
        }
        return j;
    }

    public Uri uri() {
        return Uri.parse(sourceUri);
    }
}