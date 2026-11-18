package com.opensplit.videosplitter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process queue + persistence. The foreground service drains it. */
public final class QueueManager {

    public interface Listener {
        void onQueueChanged();
    }

    private static final String PREFS = "queue";
    private static final String KEY = "jobs";
    private static final int MAX_JOBS = 40;

    private static QueueManager instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Job> jobs = new ArrayList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> cancelIds = new HashSet<>();

    private QueueManager(Context c) {
        app = c.getApplicationContext();
        load();
    }

    public static synchronized QueueManager get(Context c) {
        if (instance == null) {
            instance = new QueueManager(c);
        }
        return instance;
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        main.post(() -> {
            for (Listener l : listeners) {
                try {
                    l.onQueueChanged();
                } catch (Exception ignored) {
                }
            }
        });
    }

    public synchronized List<Job> jobs() {
        return new ArrayList<>(jobs);
    }

    public synchronized Job find(String id) {
        for (Job j : jobs) {
            if (j.id.equals(id)) {
                return j;
            }
        }
        return null;
    }

    public synchronized Job running() {
        for (Job j : jobs) {
            if (Job.RUNNING.equals(j.status)) {
                return j;
            }
        }
        return null;
    }

    public synchronized boolean hasQueued() {
        for (Job j : jobs) {
            if (Job.QUEUED.equals(j.status) && !cancelIds.contains(j.id)) {
                return true;
            }
        }
        return false;
    }

    public synchronized Job nextQueued() {
        // jobs are newest-first; process oldest-first for FIFO
        for (int i = jobs.size() - 1; i >= 0; i--) {
            Job j = jobs.get(i);
            if (Job.QUEUED.equals(j.status) && !cancelIds.contains(j.id)) {
                return j;
            }
        }
        return null;
    }

    public void enqueue(Job job) {
        synchronized (this) {
            jobs.add(0, job);
            trim();
            saveLocked();
        }
        notifyChanged();
        SplitService.start(app);
    }

    public synchronized void markRunning(String id) {
        Job j = find(id);
        if (j == null) {
            return;
        }
        j.status = Job.RUNNING;
        j.progress = 0;
        j.error = null;
        saveLocked();
        notifyChanged();
    }

    public void progress(String id, int percent) {
        boolean changed = false;
        synchronized (this) {
            Job j = find(id);
            if (j != null && Job.RUNNING.equals(j.status)
                    && (percent >= j.progress + 5 || percent >= 100)) {
                j.progress = Math.min(100, percent);
                changed = true;
            }
        }
        if (changed) {
            notifyChanged();
            SplitService.updateNotification(app);
        }
    }

    public void done(String id, List<Splitter.Out> outs) {
        synchronized (this) {
            Job j = find(id);
            if (j == null) {
                return;
            }
            for (Splitter.Out o : outs) {
                j.outs.add(new Job.Out(o.name, o.uri.toString(), o.durationMs, o.sizeBytes));
            }
            j.progress = 100;
            j.status = Job.DONE;
            saveLocked();
        }
        notifyChanged();
    }

    public void failed(String id, String message) {
        synchronized (this) {
            Job j = find(id);
            if (j == null) {
                return;
            }
            j.status = Job.FAILED;
            j.error = message == null ? "Unknown error" : message;
            saveLocked();
        }
        notifyChanged();
    }

    public void cancelled(String id) {
        synchronized (this) {
            Job j = find(id);
            if (j == null) {
                return;
            }
            j.status = Job.CANCELLED;
            j.progress = 0;
            saveLocked();
        }
        notifyChanged();
    }

    public void cancel(String id) {
        synchronized (this) {
            Job j = find(id);
            if (j == null) {
                return;
            }
            if (Job.QUEUED.equals(j.status)) {
                j.status = Job.CANCELLED;
                saveLocked();
            } else if (Job.RUNNING.equals(j.status)) {
                cancelIds.add(id);
                j.cancelRequested = true;
            }
        }
        notifyChanged();
    }

    public boolean isCancelled(String id) {
        synchronized (this) {
            return cancelIds.contains(id);
        }
    }

    public void clearFinished() {
        synchronized (this) {
            for (int i = jobs.size() - 1; i >= 0; i--) {
                Job j = jobs.get(i);
                if (!j.isActive()) {
                    jobs.remove(i);
                }
            }
            saveLocked();
        }
        notifyChanged();
    }

    public void remove(String id) {
        synchronized (this) {
            for (int i = jobs.size() - 1; i >= 0; i--) {
                if (jobs.get(i).id.equals(id)) {
                    jobs.remove(i);
                }
            }
            cancelIds.remove(id);
            saveLocked();
        }
        notifyChanged();
    }

    private void trim() {
        while (jobs.size() > MAX_JOBS) {
            for (int i = jobs.size() - 1; i >= 0; i--) {
                if (!jobs.get(i).isActive()) {
                    jobs.remove(i);
                    break;
                }
            }
            if (jobs.size() > MAX_JOBS) {
                jobs.remove(jobs.size() - 1);
            }
        }
    }

    private void saveLocked() {
        JSONArray arr = new JSONArray();
        for (Job j : jobs) {
            arr.put(j.toJson());
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }

    private void load() {
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, null);
        if (raw == null) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Job j = Job.fromJson(o);
                if (Job.RUNNING.equals(j.status) || Job.QUEUED.equals(j.status)) {
                    j.status = Job.FAILED;
                    j.error = "Interrupted when the app was closed";
                }
                jobs.add(j);
            }
        } catch (Exception ignored) {
        }
    }
}