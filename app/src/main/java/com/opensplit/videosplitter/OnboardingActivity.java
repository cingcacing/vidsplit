package com.opensplit.videosplitter;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Four-page intro. Shown once; stores its flag in prefs. */
public class OnboardingActivity extends Activity {

    static final String PREFS = "app";
    static final String KEY_SEEN = "onboarded";

    private static final String[] ICONS = {"⚡", "⏱", "✂", "🛡"};
    private static final String[] TITLES = {
            "Easy Split",
            "Custom Length",
            "Precise Trim",
            "Clean by design"
    };
    private static final String[] DESCS = {
            "Split any long video into ready-to-post clips for WhatsApp, Reels, Shorts and more.",
            "Use 30, 60 or 90 second presets, or type any clip length you want.",
            "Pick a start and end point to keep exactly the moment you need.",
            "No ads. No watermark. No premium. No trackers — this app has no internet permission at all."
    };

    private int page = 0;
    private LinearLayout dots;
    private TextView icon;
    private TextView title;
    private TextView desc;
    private TextView next;
    private TextView skip;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 26);
        root.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 24));

        skip = Ui.text(this, "Skip", 14, Ui.MUTED, true);
        skip.setGravity(Gravity.END);
        skip.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        skip.setOnClickListener(v -> finishOnboarding());
        root.addView(skip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        root.addView(center, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView badge = Ui.text(this, "", 48, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.round(Ui.SURFACE_2, 34, this));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(this, 116), Ui.dp(this, 116));
        bp.gravity = Gravity.CENTER_HORIZONTAL;
        center.addView(badge, bp);

        icon = badge;
        title = Ui.text(this, "", 26, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        center.addView(title, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 28, this));

        desc = Ui.text(this, "", 14, Ui.MUTED, false);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(Ui.dp(this, 4), 1f);
        center.addView(desc, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12, this));

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp.topMargin = Ui.dp(this, 30);
        root.addView(dots, dp);

        next = Ui.text(this, "Next", 16, Color.WHITE, true);
        next.setGravity(Gravity.CENTER);
        next.setBackground(Ui.round(Ui.PRIMARY, 15, this));
        next.setOnClickListener(v -> {
            if (page < TITLES.length - 1) {
                page++;
                render();
            } else {
                finishOnboarding();
            }
        });
        root.addView(next, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 56), 26, this));

        setContentView(root);
        render();
    }

    private void render() {
        icon.setText(ICONS[page]);
        title.setText(TITLES[page]);
        desc.setText(DESCS[page]);
        next.setText(page == TITLES.length - 1 ? "Start splitting" : "Next");
        skip.setVisibility(page == TITLES.length - 1 ? View.INVISIBLE : View.VISIBLE);
        dots.removeAllViews();
        for (int i = 0; i < TITLES.length; i++) {
            View dot = new View(this);
            dot.setBackground(Ui.round(i == page ? Ui.PRIMARY : Ui.SURFACE_2, 4, this));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    Ui.dp(this, i == page ? 20 : 8), Ui.dp(this, 8));
            p.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            dots.addView(dot, p);
        }
    }

    private void finishOnboarding() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}