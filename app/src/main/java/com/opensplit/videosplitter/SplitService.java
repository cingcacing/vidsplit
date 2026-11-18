package com.opensplit.videosplitter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.List;

/** Foreground service that drains the split queue one job at a time. */
public class SplitService extends Service {

    private static final String CHANNEL_RUN = "split_progress";
    private static final String CHANNEL_DONE = "split_done";
    private static final int NOTIF_RUN = 41;
    private static final int NOTIF_DONE = 42;

    private static SplitService instance;
    private static boolean draining;
    private static int lastNotified = -1;

    public static void start(Context c) {
        Intent i = new Intent(c, SplitService.class);
        c.startForegroundService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_RUN, buildRunningNotification("Preparing…", 0));
        synchronized (SplitService.class) {
            if (!draining) {
                draining = true;
                new Thread(this::drain, "split-service").start();
            }
        }
        return START_NOT_STICKY;
    }

    private void drain() {
        QueueManager qm = QueueManager.get(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        while (true) {
            Job job = qm.nextQueued();
            if (job == null) {
                break;
            }
            qm.markRunning(job.id);
            lastNotified = -1;
            PowerManager.WakeLock lock = null;
            if (pm != null) {
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoSplitter:job");
                lock.setReferenceCounted(false);
                lock.acquire(60 * 60 * 1000L);
            }
            try {
                List<Splitter.Seg> segs = job.segments();
                if (segs.isEmpty()) {
                    throw new IllegalArgumentException("This range is too short");
                }
                String base = Splitter.safeName(job.displayName);
                String folder = job.folder == null || job.folder.trim().isEmpty()
                        ? base : Splitter.safeName(job.folder);
                Splitter.Progress progress = (percent, label) -> {
                    qm.progress(job.id, percent);
                    if (percent - lastNotified >= 5 || percent >= 100) {
                        lastNotified = percent;
                        updateNotification(SplitService.this);
                    }
                };
                Splitter.Cancel cancel = () -> qm.isCancelled(job.id);
                List<Splitter.Out> outs;
                try {
                    outs = FfmpegEngine.split(this, job.uri(), segs, base, folder,
                            job.rotation, job.maxDim, job.precise, progress, cancel);
                } catch (Splitter.CancelledException ce) {
                    throw ce;
                } catch (Throwable primary) {
                    android.util.Log.w("SplitService",
                            "ffmpeg engine failed, trying framework muxer", primary);
                    outs = Splitter.split(this, job.uri(), segs, base, folder,
                            job.rotation, progress, cancel);
                }
                qm.done(job.id, outs);
                notifyDone(job, outs.size());
            } catch (Splitter.CancelledException e) {
                qm.cancelled(job.id);
            } catch (Throwable t) {
                String msg = t.getMessage();
                if (msg == null || msg.trim().isEmpty()) {
                    msg = t.getClass().getSimpleName();
                }
                qm.failed(job.id, msg);
                notifyFailed(job, msg);
            } finally {
                if (lock != null && lock.isHeld()) {
                    lock.release();
                }
            }
        }
        synchronized (SplitService.class) {
            draining = false;
        }
        if (qm.hasQueued()) {
            start(this);
            return;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- notifications ----------

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel run = new NotificationChannel(
                CHANNEL_RUN, "Splitting videos", NotificationManager.IMPORTANCE_LOW);
        run.setShowBadge(false);
        NotificationChannel done = new NotificationChannel(
                CHANNEL_DONE, "Split results", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(run);
        nm.createNotificationChannel(done);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildRunningNotification(String label, int percent) {
        return new Notification.Builder(this, CHANNEL_RUN)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Splitting video")
                .setContentText(label)
                .setContentIntent(openApp())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, percent <= 0)
                .build();
    }

    static void updateNotification(Context c) {
        SplitService s = instance;
        if (s == null) {
            return;
        }
        QueueManager qm = QueueManager.get(c);
        Job job = qm.running();
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || job == null) {
            return;
        }
        String label = job.displayName + "  ·  " + job.progress + "%";
        nm.notify(NOTIF_RUN, s.buildRunningNotification(label, job.progress));
    }

    private void notifyDone(Job job, int count) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Notification n = new Notification.Builder(this, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Split complete")
                .setContentText(count + (count == 1 ? " clip" : " clips")
                        + " saved to Movies/VideoSplitter")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIF_DONE, n);
    }

    private void notifyFailed(Job job, String message) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Notification n = new Notification.Builder(this, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Split failed")
                .setContentText(message)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIF_DONE, n);
    }
}