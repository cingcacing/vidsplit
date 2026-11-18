package com.opensplit.videosplitter;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {

    static final int BG = Color.parseColor("#0B0E14");
    static final int SURFACE = Color.parseColor("#141A24");
    static final int SURFACE_2 = Color.parseColor("#1C2432");
    static final int STROKE = Color.parseColor("#26303F");
    static final int TEXT = Color.parseColor("#EDF2FA");
    static final int MUTED = Color.parseColor("#8B96A8");
    static final int PRIMARY = Color.parseColor("#4F8CFF");
    static final int ACCENT = Color.parseColor("#2DD4A7");
    static final int DANGER = Color.parseColor("#FF6B6B");

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable roundStroke(int fill, int stroke, float radiusDp, Context c) {
        GradientDrawable d = round(fill, radiusDp, c);
        d.setStroke(dp(c, 1), stroke);
        return d;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(dp(c, 2), 1f);
        if (bold) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return t;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lpm(int w, int h, int topMarginDp, Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(c, topMarginDp);
        return p;
    }

    static LinearLayout card(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackground(roundStroke(SURFACE, STROKE, 18, c));
        int p = dp(c, 16);
        ll.setPadding(p, p, p, p);
        return ll;
    }

    static LinearLayout row(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        return ll;
    }

    static void margin(View v, int l, int t, int r, int b, Context c) {
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
        if (p == null) {
            p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        v.setLayoutParams(p);
    }

    static void weight(View v, float w) {
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
        if (p == null) {
            p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
        } else {
            p.width = 0;
            p.weight = w;
        }
        v.setLayoutParams(p);
    }

    static String time(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.US, "%.0f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", mb);
        }
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
    }

    static long parseTime(String s) {
        if (s == null) {
            return -1;
        }
        s = s.trim();
        if (s.isEmpty()) {
            return -1;
        }
        String[] parts = s.split(":");
        try {
            if (parts.length == 1) {
                return Long.parseLong(parts[0]) * 1000L;
            }
            if (parts.length == 2) {
                long m = Long.parseLong(parts[0]);
                long sec = Long.parseLong(parts[1]);
                return (m * 60 + sec) * 1000L;
            }
            if (parts.length == 3) {
                long h = Long.parseLong(parts[0]);
                long m = Long.parseLong(parts[1]);
                long sec = Long.parseLong(parts[2]);
                return ((h * 60 + m) * 60 + sec) * 1000L;
            }
        } catch (NumberFormatException ignored) {
        }
        return -1;
    }
}