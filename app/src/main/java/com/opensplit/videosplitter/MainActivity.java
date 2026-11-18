package com.opensplit.videosplitter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.VideoView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements QueueManager.Listener {

    private static final int REQ_PICK = 1001;
    private static final int REQ_NOTIF = 1002;

    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout content;
    private LinearLayout controls;
    private LinearLayout queueBox;
    private LinearLayout queueHeader;
    private TextView queueTitle;
    private TextView queueClear;

    private ImageView thumb;
    private TextView videoName;
    private TextView videoMeta;
    private TextView videoAction;
    private LinearLayout previewCard;
    private ImageView previewImage;
    private FrameLayout previewFrame;
    private VideoView videoView;
    private TextView previewPlay;
    private SeekBar previewSeek;
    private TextView previewTime;
    private final android.os.Handler previewHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable previewTick;
    private boolean previewStarted;

    private final ArrayList<TextView> pills = new ArrayList<>();
    private final ArrayList<TextView> presets = new ArrayList<>();
    private final ArrayList<TextView> qualityChips = new ArrayList<>();

    private SeekBar partsSeek;
    private SeekBar everySeek;
    private SeekBar startSeek;
    private SeekBar endSeek;
    private TextView partsValue;
    private TextView everyValue;
    private TextView startValue;
    private TextView endValue;
    private LinearLayout stripBox;

    private EditText folderInput;
    private TextView planHint;
    private TextView splitButton;

    private int mode = Splitter.MODE_PARTS;
    private int maxDim = 0;
    private boolean precise = false;
    private int partsCount = 6;
    private int everySeconds = 60;
    private long trimStartMs = 0;
    private long trimEndMs = 0;

    private Uri videoUri;
    private String displayName = "";
    private long durationMs;
    private int videoRotation;
    private int videoWidth;
    private int videoHeight;
    private long sizeBytes;

    private int stripToken;
    private String lastQueueSignature = "";
    private final java.util.HashMap<String, TextView> queueStatus = new java.util.HashMap<>();
    private final java.util.HashMap<String, ProgressBar> queueBars = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!getSharedPreferences(OnboardingActivity.PREFS, MODE_PRIVATE)
                .getBoolean(OnboardingActivity.KEY_SEEN, false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        buildUi();
        QueueManager.get(this).addListener(this);
        refreshQueue();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Accepts a video shared or opened from another app. */
    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_SEND.equals(action)) {
            return;
        }
        Uri uri = intent.getData();
        if (uri == null && intent.hasExtra(Intent.EXTRA_STREAM)) {
            Object extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (extra instanceof Uri) {
                uri = (Uri) extra;
            }
        }
        if (uri == null) {
            return;
        }
        String type = intent.getType();
        if (type != null && !type.startsWith("video")) {
            return;
        }
        loadVideo(uri);
    }

    @Override
    protected void onDestroy() {
        QueueManager.get(this).removeListener(this);
        previewHandler.removeCallbacks(previewTick);
        try {
            videoView.stopPlayback();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onQueueChanged() {
        refreshQueue();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (videoView != null && videoView.isPlaying()) {
                videoView.pause();
                previewPlay.setText("▶");
                previewPlay.setVisibility(View.VISIBLE);
            }
        } catch (Exception ignored) {
        }
        previewHandler.removeCallbacks(previewTick);
    }

    // ---------------- UI ----------------

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 20);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 32));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // header
        LinearLayout header = Ui.row(this);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, "Video Splitter", 26, Ui.TEXT, true));
        titles.addView(Ui.text(this, "Split long videos. No ads, no watermark.", 12, Ui.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView library = Ui.text(this, "My Stuff", 12, Ui.PRIMARY, true);
        library.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        library.setBackground(Ui.round(Ui.SURFACE_2, 10, this));
        library.setOnClickListener(v -> startActivity(new Intent(this, LibraryActivity.class)));
        header.addView(library);
        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // video card
        LinearLayout videoCard = Ui.card(this);
        LinearLayout selRow = Ui.row(this);
        thumb = new ImageView(this);
        thumb.setBackground(Ui.round(Ui.SURFACE_2, 12, this));
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setClipToOutline(true);
        thumb.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.dp(MainActivity.this, 12));
            }
        });
        selRow.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 58)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        videoName = Ui.text(this, "No video selected", 15, Ui.TEXT, true);
        videoName.setMaxLines(1);
        videoName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        videoMeta = Ui.text(this, "Choose a video to begin", 11.5f, Ui.MUTED, false);
        videoMeta.setMaxLines(1);
        videoMeta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(videoName);
        texts.addView(videoMeta, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 3, this));
        selRow.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Ui.margin(texts, 14, 0, 0, 0, this);
        videoAction = Ui.text(this, "Choose", 13, Ui.PRIMARY, true);
        videoAction.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
        videoAction.setBackground(Ui.round(Ui.SURFACE_2, 10, this));
        videoAction.setOnClickListener(v -> pickVideo());
        selRow.addView(videoAction);
        videoCard.addView(selRow);
        videoCard.setOnClickListener(v -> pickVideo());
        content.addView(videoCard, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 18, this));

        // preview (in-app player, aspect-correct)
        previewCard = Ui.card(this);
        previewCard.setVisibility(View.GONE);
        previewCard.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));

        previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        videoView = new VideoView(this);
        previewFrame.addView(videoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        previewImage = new ImageView(this);
        previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImage.setBackgroundColor(Color.BLACK);
        previewFrame.addView(previewImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        previewPlay = Ui.text(this, "▶", 24, Color.WHITE, true);
        previewPlay.setGravity(Gravity.CENTER);
        previewPlay.setBackground(Ui.round(0xCC000000, 30, this));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                Ui.dp(this, 62), Ui.dp(this, 62));
        pp.gravity = Gravity.CENTER;
        previewFrame.addView(previewPlay, pp);

        previewCard.addView(previewFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 188)));

        LinearLayout previewBar = Ui.row(this);
        previewSeek = new SeekBar(this);
        previewSeek.setMax(1000);
        previewSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        previewSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        previewSeek.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Ui.SURFACE_2));
        previewSeek.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && previewStarted) {
                    videoView.seekTo(p);
                }
                if (previewTime != null) {
                    previewTime.setText(Ui.time(p) + " / " + Ui.time(durationMs));
                }
            }
        });
        previewBar.addView(previewSeek, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        previewTime = Ui.text(this, "00:00 / 00:00", 10, Ui.MUTED, false);
        Ui.margin(previewTime, 8, 0, 4, 0, this);
        previewBar.addView(previewTime);
        previewCard.addView(previewBar, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, this));

        LinearLayout previewFooter = Ui.row(this);
        previewFooter.addView(Ui.text(this, "In-app player", 12, Ui.PRIMARY, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        previewFooter.addView(Ui.text(this, "Tap to play or pause", 10, Ui.MUTED, false));
        previewCard.addView(previewFooter, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6, this));

        previewFrame.setOnClickListener(v -> togglePreview());
        previewImage.setOnClickListener(v -> startPreview());
        previewPlay.setOnClickListener(v -> startPreview());

        videoView.setOnPreparedListener(mp -> {
            previewSeek.setMax(Math.max(1, (int) durationMs));
            videoView.seekTo(1);
            previewTime.setText("00:00 / " + Ui.time(durationMs));
        });
        videoView.setOnCompletionListener(mp -> {
            previewHandler.removeCallbacks(previewTick);
            previewStarted = false;
            previewPlay.setText("▶");
            previewPlay.setVisibility(View.VISIBLE);
            previewImage.setVisibility(View.VISIBLE);
            previewSeek.setProgress(0);
            previewTime.setText("00:00 / " + Ui.time(durationMs));
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            toast("This video cannot be played in-app.");
            return true;
        });

        content.addView(previewCard, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12, this));

        // modes
        content.addView(sectionLabel("SPLIT MODE"), Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 22, this));
        LinearLayout modeRow = Ui.row(this);
        modeRow.setBackground(Ui.round(Ui.SURFACE, 14, this));
        int mp = Ui.dp(this, 5);
        modeRow.setPadding(mp, mp, mp, mp);
        addPill(modeRow, Splitter.MODE_PARTS, "Parts");
        addPill(modeRow, Splitter.MODE_EVERY, "Every N sec");
        addPill(modeRow, Splitter.MODE_TRIM, "Trim");
        content.addView(modeRow, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8, this));

        controls = Ui.card(this);
        content.addView(controls, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12, this));

        // folder
        LinearLayout folderCard = Ui.card(this);
        folderCard.addView(Ui.text(this, "Save to folder", 12, Ui.MUTED, false));
        folderInput = new EditText(this);
        folderInput.setText("VideoSplitter");
        folderInput.setSingleLine(true);
        folderInput.setTextColor(Ui.TEXT);
        folderInput.setTextSize(14);
        folderInput.setBackground(Ui.roundStroke(Ui.SURFACE_2, Ui.STROKE, 11, this));
        folderInput.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        folderCard.addView(folderInput, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8, this));
        content.addView(folderCard, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12, this));

        // quality / precision
        LinearLayout qualityCard = Ui.card(this);
        qualityCard.addView(Ui.text(this, "QUALITY", 11, Ui.MUTED, true));
        android.widget.CheckBox preciseBox = new android.widget.CheckBox(this);
        preciseBox.setText("Precise cuts (re-encode)");
        preciseBox.setTextColor(Ui.TEXT);
        preciseBox.setTextSize(13);
        preciseBox.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        preciseBox.setOnCheckedChangeListener((b, checked) -> precise = checked);
        qualityCard.addView(preciseBox, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6, this));

        qualityCard.addView(Ui.text(this, "Max resolution", 11, Ui.MUTED, false),
                Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 8, this));
        LinearLayout qualityRow = Ui.row(this);
        addQualityChip(qualityRow, "Original", 0);
        addQualityChip(qualityRow, "1080p", 1080);
        addQualityChip(qualityRow, "720p", 720);
        addQualityChip(qualityRow, "480p", 480);
        qualityCard.addView(qualityRow, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6, this));
        content.addView(qualityCard, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12, this));

        planHint = Ui.text(this, "", 12, Ui.MUTED, false);
        content.addView(planHint, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12, this));

        splitButton = Ui.text(this, "Split video", 16, Color.WHITE, true);
        splitButton.setGravity(Gravity.CENTER);
        splitButton.setBackground(Ui.round(Ui.PRIMARY, 15, this));
        splitButton.setEnabled(false);
        splitButton.setAlpha(0.45f);
        splitButton.setOnClickListener(v -> enqueue());
        content.addView(splitButton, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 56), 16, this));

        // queue
        queueHeader = Ui.row(this);
        queueTitle = Ui.text(this, "Queue", 17, Ui.TEXT, true);
        queueHeader.addView(queueTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        queueClear = Ui.text(this, "Clear finished", 11, Ui.MUTED, true);
        queueClear.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
        queueClear.setBackground(Ui.round(Ui.SURFACE_2, 9, this));
        queueClear.setOnClickListener(v -> queue().clearFinished());
        queueHeader.addView(queueClear);
        content.addView(queueHeader, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 24, this));

        queueBox = new LinearLayout(this);
        queueBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(queueBox, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8, this));

        TextView privacy = Ui.text(this,
                "No internet permission. No ads, no trackers, nothing leaves your device.",
                11, Ui.MUTED, false);
        privacy.setGravity(Gravity.CENTER);
        content.addView(privacy, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 18, this));

        rebuildControls();
        refreshPlan();
        setContentView(root);
    }

    private QueueManager queue() {
        return QueueManager.get(this);
    }

    private TextView sectionLabel(String s) {
        TextView t = Ui.text(this, s, 11, Ui.MUTED, true);
        t.setLetterSpacing(0.12f);
        return t;
    }

    private void addPill(LinearLayout row, final int m, String label) {
        TextView t = Ui.text(this, label, 13, Ui.MUTED, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        t.setBackground(Ui.round(Color.TRANSPARENT, 10, this));
        t.setOnClickListener(v -> {
            mode = m;
            updatePills();
            rebuildControls();
            refreshPlan();
        });
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pills.add(t);
    }

    private void updatePills() {
        for (int i = 0; i < pills.size(); i++) {
            TextView t = pills.get(i);
            boolean sel = (mode == Splitter.MODE_PARTS && i == 0)
                    || (mode == Splitter.MODE_EVERY && i == 1)
                    || (mode == Splitter.MODE_TRIM && i == 2);
            t.setTextColor(sel ? Ui.TEXT : Ui.MUTED);
            t.setBackground(sel ? Ui.round(Ui.SURFACE_2, 10, this)
                    : Ui.round(Color.TRANSPARENT, 10, this));
        }
    }

    private SeekBar seekBar(int max, int progress) {
        SeekBar s = new SeekBar(this);
        s.setMax(Math.max(1, max));
        s.setProgress(progress);
        s.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        s.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        s.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.SURFACE_2));
        s.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6));
        return s;
    }

    private TextView valueLabel(String s) {
        TextView t = Ui.text(this, s, 15, Ui.TEXT, true);
        return t;
    }

    private void rebuildControls() {
        controls.removeAllViews();
        presets.clear();
        partsSeek = null;
        everySeek = null;
        startSeek = null;
        endSeek = null;
        stripBox = null;

        if (mode == Splitter.MODE_PARTS) {
            controls.addView(Ui.text(this, "Number of equal clips", 12, Ui.MUTED, false));
            partsValue = valueLabel(partsCount + " clips");
            controls.addView(partsValue, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 6, this));
            partsSeek = seekBar(58, partsCount - 2);
            partsSeek.setOnSeekBarChangeListener(new SimpleSeek() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    partsCount = p + 2;
                    partsValue.setText(partsCount + " clips");
                    refreshPlan();
                }
            });
            controls.addView(partsSeek, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 4, this));
        } else if (mode == Splitter.MODE_EVERY) {
            controls.addView(Ui.text(this, "Seconds per clip", 12, Ui.MUTED, false));
            everyValue = valueLabel(everySeconds + " sec");
            controls.addView(everyValue, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 6, this));
            LinearLayout chips = Ui.row(this);
            addPreset(chips, 30);
            addPreset(chips, 60);
            addPreset(chips, 90);
            controls.addView(chips, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 4, this));
            everySeek = seekBar(295, everySeconds - 5);
            everySeek.setOnSeekBarChangeListener(new SimpleSeek() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    everySeconds = p + 5;
                    everyValue.setText(everySeconds + " sec");
                    updatePresets();
                    refreshPlan();
                }
            });
            controls.addView(everySeek, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 6, this));
            updatePresets();
        } else {
            if (trimEndMs <= 0 || trimEndMs > durationMs) {
                trimEndMs = durationMs;
            }
            if (trimStartMs < 0) {
                trimStartMs = 0;
            }
            LinearLayout labels = Ui.row(this);
            labels.addView(Ui.text(this, "Start", 12, Ui.MUTED, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView endLabel = Ui.text(this, "End", 12, Ui.MUTED, false);
            endLabel.setGravity(Gravity.END);
            labels.addView(endLabel, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            controls.addView(labels);

            LinearLayout values = Ui.row(this);
            startValue = valueLabel(Ui.time(trimStartMs));
            endValue = valueLabel(Ui.time(trimEndMs));
            endValue.setGravity(Gravity.END);
            values.addView(startValue, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            values.addView(endValue, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            controls.addView(values, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 6, this));

            int maxSec = Math.max(1, (int) (durationMs / 1000));
            startSeek = seekBar(maxSec, (int) (trimStartMs / 1000));
            endSeek = seekBar(maxSec, (int) (trimEndMs / 1000));
            startSeek.setOnSeekBarChangeListener(new SimpleSeek() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    trimStartMs = p * 1000L;
                    if (trimStartMs > trimEndMs - 1000) {
                        trimStartMs = Math.max(0, trimEndMs - 1000);
                    }
                    s.setProgress((int) (trimStartMs / 1000));
                    startValue.setText(Ui.time(trimStartMs));
                    refreshPlan();
                }
            });
            endSeek.setOnSeekBarChangeListener(new SimpleSeek() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    trimEndMs = p * 1000L;
                    if (trimEndMs < trimStartMs + 1000) {
                        trimEndMs = Math.min(durationMs, trimStartMs + 1000);
                    }
                    s.setProgress((int) (trimEndMs / 1000));
                    endValue.setText(Ui.time(trimEndMs));
                    refreshPlan();
                }
            });
            controls.addView(startSeek, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 2, this));
            controls.addView(endSeek, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 2, this));

            stripBox = new LinearLayout(this);
            stripBox.setOrientation(LinearLayout.HORIZONTAL);
            int h = Ui.dp(this, 46);
            stripBox.setMinimumHeight(h);
            if (videoUri != null) {
                loadStrip(stripBox);
            }
            controls.addView(stripBox, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT, h, 8, this));
            TextView tip = Ui.text(this,
                    "Drag the two handles to choose exactly what to keep.",
                    10, Ui.MUTED, false);
            controls.addView(tip, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 6, this));
        }
        refreshPlan();
    }

    private void addPreset(LinearLayout row, final int seconds) {
        final TextView t = Ui.text(this, seconds + "s", 12, Ui.MUTED, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        t.setBackground(Ui.roundStroke(Ui.SURFACE_2, Ui.STROKE, 10, this));
        t.setOnClickListener(v -> {
            everySeconds = seconds;
            if (everySeek != null) {
                everySeek.setProgress(seconds - 5);
            }
            if (everyValue != null) {
                everyValue.setText(seconds + " sec");
            }
            updatePresets();
            refreshPlan();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = Ui.dp(this, 8);
        row.addView(t, p);
        presets.add(t);
    }

    private void updatePresets() {
        for (TextView t : presets) {
            boolean sel = t.getText().toString().equals(everySeconds + "s");
            t.setTextColor(sel ? Ui.PRIMARY : Ui.MUTED);
            t.setBackground(sel ? Ui.roundStroke(Ui.SURFACE_2, Ui.PRIMARY, 10, this)
                    : Ui.roundStroke(Ui.SURFACE_2, Ui.STROKE, 10, this));
        }
    }

    private void addQualityChip(LinearLayout row, String label, final int dim) {
        final TextView t = Ui.text(this, label, 12, Ui.MUTED, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        t.setBackground(Ui.roundStroke(Ui.SURFACE_2, Ui.STROKE, 10, this));
        t.setOnClickListener(v -> {
            maxDim = dim;
            updateQualityChips();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = Ui.dp(this, 6);
        row.addView(t, p);
        qualityChips.add(t);
        updateQualityChips();
    }

    private void updateQualityChips() {
        String selected = maxDim == 0 ? "Original" : (maxDim + "p");
        for (TextView t : qualityChips) {
            boolean sel = t.getText().toString().equals(selected);
            t.setTextColor(sel ? Ui.PRIMARY : Ui.MUTED);
            t.setBackground(sel ? Ui.roundStroke(Ui.SURFACE_2, Ui.PRIMARY, 10, this)
                    : Ui.roundStroke(Ui.SURFACE_2, Ui.STROKE, 10, this));
        }
    }

    private void loadStrip(LinearLayout box) {
        final int token = ++stripToken;
        final Uri uri = videoUri;
        final long dur = durationMs;
        if (uri == null || dur <= 0) {
            return;
        }
        new Thread(() -> {
            List<Bitmap> frames = Thumbs.strip(MainActivity.this, uri, dur, 7,
                    Ui.dp(MainActivity.this, 120), Ui.dp(MainActivity.this, 54));
            runOnUiThread(() -> {
                if (token != stripToken || box != stripBox) {
                    return;
                }
                box.removeAllViews();
                for (Bitmap b : frames) {
                    ImageView iv = new ImageView(MainActivity.this);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setImageBitmap(b);
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                    p.rightMargin = Ui.dp(MainActivity.this, 2);
                    box.addView(iv, p);
                }
            });
        }, "strip").start();
    }

    // ---------------- video selection ----------------

    private void startPreview() {
        if (videoUri == null) {
            return;
        }
        previewImage.setVisibility(View.GONE);
        previewPlay.setVisibility(View.GONE);
        try {
            videoView.start();
            previewStarted = true;
        } catch (Exception ignored) {
        }
        startPreviewTick();
    }

    private void togglePreview() {
        if (videoUri == null) {
            return;
        }
        if (!previewStarted) {
            startPreview();
            return;
        }
        try {
            if (videoView.isPlaying()) {
                videoView.pause();
                previewHandler.removeCallbacks(previewTick);
                previewPlay.setText("▶");
                previewPlay.setVisibility(View.VISIBLE);
            } else {
                previewPlay.setVisibility(View.GONE);
                videoView.start();
                startPreviewTick();
            }
        } catch (Exception ignored) {
        }
    }

    private void startPreviewTick() {
        if (previewTick == null) {
            previewTick = new Runnable() {
                @Override
                public void run() {
                    try {
                        if (videoView.isPlaying()) {
                            int p = videoView.getCurrentPosition();
                            previewSeek.setProgress(p);
                            previewTime.setText(Ui.time(p) + " / " + Ui.time(durationMs));
                        }
                    } catch (Exception ignored) {
                    }
                    boolean playing = false;
                    try {
                        playing = videoView.isPlaying();
                    } catch (Exception ignored) {
                    }
                    if (playing) {
                        previewHandler.postDelayed(this, 400);
                    }
                }
            };
        }
        previewHandler.removeCallbacks(previewTick);
        previewHandler.post(previewTick);
    }

    private void pickVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            loadVideo(uri);
        }
    }

    private void loadVideo(Uri uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, uri);
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            durationMs = dur == null ? 0 : Long.parseLong(dur);
            String w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            videoWidth = w == null ? 0 : Integer.parseInt(w);
            videoHeight = h == null ? 0 : Integer.parseInt(h);
            videoRotation = rot == null ? 0 : Integer.parseInt(rot);
            if (videoRotation == 90 || videoRotation == 270) {
                int tmp = videoWidth;
                videoWidth = videoHeight;
                videoHeight = tmp;
            }
        } catch (Exception e) {
            toast("Could not read this video. Try another one.");
            return;
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }
        videoUri = uri;
        displayName = queryName(uri);
        sizeBytes = querySize(uri);
        trimStartMs = 0;
        trimEndMs = durationMs;

        videoName.setText(displayName);
        String meta = Ui.time(durationMs);
        if (videoWidth > 0 && videoHeight > 0) {
            meta += " · " + videoWidth + "×" + videoHeight;
        }
        if (sizeBytes > 0) {
            meta += " · " + Ui.size(sizeBytes);
        }
        videoMeta.setText(meta);
        videoAction.setText("Change");
        splitButton.setEnabled(true);
        splitButton.setAlpha(1f);

        String base = Splitter.safeName(displayName);
        folderInput.setText(base);

        thumb.setImageDrawable(null);
        Bitmap tb = Thumbs.load(this, uri, Ui.dp(this, 168), Ui.dp(this, 116));
        if (tb != null) {
            thumb.setImageBitmap(tb);
        }

        previewCard.setVisibility(View.VISIBLE);
        // size the preview to the video's own aspect ratio (never crop)
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int innerW = Math.max(1, screenW - Ui.dp(this, 40) - Ui.dp(this, 16));
        float aspect = (videoWidth > 0 && videoHeight > 0)
                ? (float) videoHeight / videoWidth : 9f / 16f;
        int previewH = Math.round(innerW * aspect);
        previewH = Math.max(Ui.dp(this, 140), Math.min(Ui.dp(this, 430), previewH));
        ViewGroup.LayoutParams plp = previewFrame.getLayoutParams();
        if (plp != null) {
            plp.height = previewH;
            previewFrame.setLayoutParams(plp);
        }
        Bitmap fit = Thumbs.frameFit(this, uri, innerW, previewH);
        previewImage.setImageBitmap(fit != null ? fit : tb);
        previewImage.setVisibility(View.VISIBLE);
        previewPlay.setText("▶");
        previewPlay.setVisibility(View.VISIBLE);
        previewStarted = false;
        previewHandler.removeCallbacks(previewTick);
        previewSeek.setProgress(0);
        previewTime.setText("00:00 / " + Ui.time(durationMs));
        try {
            videoView.stopPlayback();
            videoView.setVideoURI(uri);
        } catch (Exception ignored) {
        }

        rebuildControls();
        refreshPlan();
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.trim().isEmpty()) {
                        return n;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "video" : last;
    }

    private long querySize(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getLong(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    // ---------------- plan / enqueue ----------------

    private List<Splitter.Seg> currentSegments() {
        if (durationMs <= 0) {
            return new ArrayList<>();
        }
        if (mode == Splitter.MODE_PARTS) {
            return Splitter.plan(Splitter.MODE_PARTS, durationMs, partsCount, 0);
        }
        if (mode == Splitter.MODE_EVERY) {
            return Splitter.plan(Splitter.MODE_EVERY, durationMs, everySeconds * 1000L, 0);
        }
        return Splitter.plan(Splitter.MODE_TRIM, durationMs, trimStartMs, trimEndMs);
    }

    private void refreshPlan() {
        if (planHint == null) {
            return;
        }
        if (videoUri == null) {
            planHint.setText("Pick a video to see the split plan.");
            splitButton.setEnabled(false);
            splitButton.setAlpha(0.45f);
            return;
        }
        List<Splitter.Seg> segs = currentSegments();
        if (segs.isEmpty()) {
            planHint.setText("This range is too short. Adjust the values.");
            splitButton.setEnabled(false);
            splitButton.setAlpha(0.45f);
            return;
        }
        long total = 0;
        for (Splitter.Seg s : segs) {
            total += s.endMs - s.startMs;
        }
        String per = segs.size() == 1 ? Ui.time(total) : Ui.time(total / segs.size()) + " average";
        planHint.setText(String.format(Locale.US, "%d clip%s · %s total · %s per clip",
                segs.size(), segs.size() == 1 ? "" : "s", Ui.time(total), per));
        splitButton.setEnabled(true);
        splitButton.setAlpha(1f);
    }

    private void enqueue() {
        if (videoUri == null) {
            return;
        }
        List<Splitter.Seg> segs = currentSegments();
        if (segs.isEmpty()) {
            toast("Nothing to split. Adjust the range.");
            return;
        }
        Job job = new Job();
        job.sourceUri = videoUri.toString();
        job.displayName = displayName;
        job.durationMs = durationMs;
        job.rotation = videoRotation;
        job.mode = mode;
        job.a = mode == Splitter.MODE_TRIM ? trimStartMs : (mode == Splitter.MODE_EVERY ? everySeconds : partsCount);
        job.b = trimEndMs;
        String folder = folderInput.getText().toString().trim();
        job.folder = folder.isEmpty() ? Splitter.safeName(displayName) : Splitter.safeName(folder);
        job.maxDim = maxDim;
        job.precise = precise;
        queue().enqueue(job);
        askNotifications();
        toast("Added to queue · " + segs.size() + " clips");
        refreshQueue();
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    // ---------------- queue UI ----------------

    private void refreshQueue() {
        if (queueBox == null) {
            return;
        }
        List<Job> jobs = queue().jobs();
        boolean any = !jobs.isEmpty();
        queueHeader.setVisibility(any ? View.VISIBLE : View.GONE);
        queueBox.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any) {
            queueBox.removeAllViews();
            return;
        }
        boolean anyFinished = false;
        for (Job j : jobs) {
            if (!j.isActive()) {
                anyFinished = true;
                break;
            }
        }
        queueClear.setVisibility(anyFinished ? View.VISIBLE : View.GONE);

        StringBuilder sig = new StringBuilder();
        for (Job j : jobs) {
            sig.append(j.id).append('|').append(j.status).append('|')
                    .append(j.outs.size()).append(';');
        }
        String signature = sig.toString();
        if (signature.equals(lastQueueSignature)) {
            updateQueueProgress();
            return;
        }
        lastQueueSignature = signature;

        queueBox.removeAllViews();
        queueStatus.clear();
        queueBars.clear();
        int shown = 0;
        for (Job j : jobs) {
            if (shown >= 8) {
                break;
            }
            queueBox.addView(jobRow(j), Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 8, this));
            shown++;
        }
    }

    private void updateQueueProgress() {
        for (Job j : queue().jobs()) {
            if (!Job.RUNNING.equals(j.status)) {
                continue;
            }
            TextView status = queueStatus.get(j.id);
            if (status != null) {
                status.setText("Splitting · " + j.progress + "%");
            }
            ProgressBar bar = queueBars.get(j.id);
            if (bar != null) {
                bar.setProgress(j.progress);
            }
        }
    }

    private View jobRow(Job j) {
        LinearLayout card = Ui.card(this);
        LinearLayout top = Ui.row(this);
        TextView name = Ui.text(this, j.displayName, 13, Ui.TEXT, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView badge = Ui.text(this, j.modeLabel(), 10, Ui.MUTED, true);
        badge.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        badge.setBackground(Ui.round(Ui.SURFACE_2, 8, this));
        top.addView(badge);
        card.addView(top);

        TextView status = Ui.text(this, "", 11, Ui.MUTED, false);
        if (Job.QUEUED.equals(j.status)) {
            status.setText("In queue");
        } else if (Job.RUNNING.equals(j.status)) {
            status.setText("Splitting · " + j.progress + "%");
        } else if (Job.DONE.equals(j.status)) {
            status.setText(j.outs.size() + (j.outs.size() == 1 ? " clip" : " clips")
                    + " · saved to " + j.folder);
            status.setTextColor(Ui.ACCENT);
        } else if (Job.FAILED.equals(j.status)) {
            status.setText(j.error == null ? "Failed" : j.error);
            status.setTextColor(Ui.DANGER);
        } else {
            status.setText("Cancelled");
        }
        queueStatus.put(j.id, status);
        card.addView(status, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6, this));

        if (Job.RUNNING.equals(j.status)) {
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgress(j.progress);
            pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
            pb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.SURFACE_2));
            queueBars.put(j.id, pb);
            card.addView(pb, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    Ui.dp(this, 6), 8, this));
        }

        LinearLayout actions = Ui.row(this);
        actions.setGravity(Gravity.END);
        if (j.isActive()) {
            TextView cancel = action("Cancel", Ui.DANGER);
            cancel.setOnClickListener(v -> queue().cancel(j.id));
            actions.addView(cancel);
        } else {
            if (Job.DONE.equals(j.status)) {
                TextView share = action("Share", Ui.ACCENT);
                share.setOnClickListener(v -> {
                    ArrayList<Uri> uris = new ArrayList<>();
                    for (Job.Out o : j.outs) {
                        uris.add(Uri.parse(o.uri));
                    }
                    ShareSheet.show(this, uris, "Share clips");
                });
                actions.addView(share);

                TextView open = action("View clips", Ui.PRIMARY);
                open.setOnClickListener(v -> startActivity(new Intent(this, LibraryActivity.class)));
                Ui.margin(open, 8, 0, 0, 0, this);
                actions.addView(open);
            }
            TextView remove = action("Dismiss", Ui.MUTED);
            remove.setOnClickListener(v -> queue().remove(j.id));
            Ui.margin(remove, 8, 0, 0, 0, this);
            actions.addView(remove);
        }
        card.addView(actions, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8, this));
        return card;
    }

    private TextView action(String label, int color) {
        TextView t = Ui.text(this, label, 12, color, true);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        t.setBackground(Ui.round(Ui.SURFACE_2, 9, this));
        return t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------- small helpers ----------------

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}