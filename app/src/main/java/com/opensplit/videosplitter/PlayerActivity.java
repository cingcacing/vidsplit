package com.opensplit.videosplitter;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

/** In-app player used by every playback entry point. */
public class PlayerActivity extends Activity {

    private VideoView videoView;
    private TextView playPause;
    private TextView time;
    private SeekBar seek;
    private FrameLayout stage;
    private FrameLayout videoArea;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable tick;
    private Uri uri;
    private int videoW;
    private int videoH;
    private int durationMs;
    private boolean prepared;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String s = getIntent() == null ? null : getIntent().getStringExtra("uri");
        if (s == null) {
            finish();
            return;
        }
        uri = Uri.parse(s);
        String titleText = getIntent().getStringExtra("title");
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        probe();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 12));

        LinearLayout bar = Ui.row(this);
        TextView back = Ui.text(this, "‹", 30, Ui.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 14), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = Ui.text(this, titleText == null ? "Play" : titleText,
                15, Ui.TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        videoArea = new FrameLayout(this);
        videoArea.setBackgroundColor(Color.BLACK);
        videoView = new VideoView(this);
        videoArea.addView(videoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams stageLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 200), Ui.dp(this, 120), Gravity.CENTER);
        stage.addView(videoArea, stageLp);

        LinearLayout controls = Ui.row(this);
        int cp = Ui.dp(this, 10);
        controls.setPadding(cp, cp, cp, cp);
        controls.setBackground(Ui.roundStroke(Ui.SURFACE, Ui.STROKE, 14, this));
        playPause = Ui.text(this, "▶", 18, Color.WHITE, true);
        playPause.setGravity(Gravity.CENTER);
        playPause.setBackground(Ui.round(Ui.PRIMARY, 22, this));
        playPause.setOnClickListener(v -> toggle());
        controls.addView(playPause, new LinearLayout.LayoutParams(
                Ui.dp(this, 44), Ui.dp(this, 44)));

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        seek.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Ui.SURFACE_2));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }

            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && prepared) {
                    videoView.seekTo(p);
                    if (time != null) {
                        time.setText(Ui.time(p) + " / " + Ui.time(durationMs));
                    }
                }
            }
        });
        controls.addView(seek, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Ui.margin(seek, 10, 0, 8, 0, this);

        time = Ui.text(this, "00:00 / 00:00", 10, Ui.MUTED, false);
        controls.addView(time);
        root.addView(controls, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10, this));

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        videoView.setOnPreparedListener(mp -> {
            prepared = true;
            durationMs = Math.max(0, videoView.getDuration());
            seek.setMax(Math.max(1, durationMs));
            videoView.seekTo(1);
            layoutVideo();
            time.setText("00:00 / " + Ui.time(durationMs));
            start();
        });
        videoView.setOnCompletionListener(mp -> {
            ui.removeCallbacks(tick);
            playPause.setText("▶");
            seek.setProgress(0);
            time.setText("00:00 / " + Ui.time(durationMs));
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            toast("This video cannot be played in-app.");
            return true;
        });
        try {
            videoView.setVideoURI(uri);
        } catch (Exception e) {
            toast("This video cannot be played in-app.");
        }
    }

    private void probe() {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            durationMs = d == null ? 0 : Integer.parseInt(d);
            videoW = w == null ? 0 : Integer.parseInt(w);
            videoH = h == null ? 0 : Integer.parseInt(h);
            int rotation = rot == null ? 0 : Integer.parseInt(rot);
            if (rotation == 90 || rotation == 270) {
                int t = videoW;
                videoW = videoH;
                videoH = t;
            }
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void layoutVideo() {
        int availW = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32);
        int availH = getResources().getDisplayMetrics().heightPixels
                - Ui.dp(this, 190);
        if (availW <= 0 || availH <= 0) {
            return;
        }
        int w = videoW > 0 ? videoW : 16;
        int h = videoH > 0 ? videoH : 9;
        float scale = Math.min((float) availW / w, (float) availH / h);
        int dw = Math.max(1, Math.round(w * scale));
        int dh = Math.max(1, Math.round(h * scale));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) videoArea.getLayoutParams();
        lp.width = dw;
        lp.height = dh;
        lp.gravity = Gravity.CENTER;
        videoArea.setLayoutParams(lp);
    }

    private void start() {
        try {
            videoView.start();
            playPause.setText("❚❚");
        } catch (Exception ignored) {
        }
        if (tick == null) {
            tick = new Runnable() {
                @Override
                public void run() {
                    try {
                        if (videoView.isPlaying()) {
                            int p = videoView.getCurrentPosition();
                            seek.setProgress(p);
                            time.setText(Ui.time(p) + " / " + Ui.time(durationMs));
                            ui.postDelayed(this, 400);
                        }
                    } catch (Exception ignored) {
                    }
                }
            };
        }
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void toggle() {
        try {
            if (videoView.isPlaying()) {
                videoView.pause();
                playPause.setText("▶");
                ui.removeCallbacks(tick);
            } else {
                start();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (stage != null) {
            layoutVideo();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (videoView != null && videoView.isPlaying()) {
                videoView.pause();
                playPause.setText("▶");
            }
        } catch (Exception ignored) {
        }
        ui.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        try {
            videoView.stopPlayback();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}