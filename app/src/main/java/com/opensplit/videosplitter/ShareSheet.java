package com.opensplit.videosplitter;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Share sheet with direct targets (WhatsApp, WhatsApp Status, Instagram, Facebook,
 * Telegram) plus the standard Android chooser.
 *
 * WhatsApp does not expose a public "post to status" API. The Status entry opens
 * WhatsApp's own share flow where the user picks "My status"; the attempt at the
 * status-specific action is made first and falls back to ACTION_SEND.
 */
public final class ShareSheet {

    private static final String WA = "com.whatsapp";
    private static final String WA_BUSINESS = "com.whatsapp.w4b";
    private static final String INSTAGRAM = "com.instagram.android";
    private static final String FACEBOOK = "com.facebook.katana";
    private static final String TELEGRAM = "org.telegram.messenger";

    private ShareSheet() {
    }

    public static void show(Activity a, List<Uri> uris, String title) {
        if (uris == null || uris.isEmpty() || a.isFinishing()) {
            return;
        }
        final ArrayList<Uri> list = new ArrayList<>(uris);
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout wrap = new FrameLayout(a);
        int pad = Ui.dp(a, 14);
        wrap.setPadding(pad, pad, pad, pad);

        LinearLayout sheet = new LinearLayout(a);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(Ui.roundStroke(Ui.SURFACE, Ui.STROKE, 18, a));
        int sp = Ui.dp(a, 18);
        sheet.setPadding(sp, Ui.dp(a, 14), sp, Ui.dp(a, 12));

        TextView head = Ui.text(a, title == null ? "Share clips" : title, 16, Ui.TEXT, true);
        sheet.addView(head);
        TextView sub = Ui.text(a, list.size() + (list.size() == 1 ? " clip" : " clips")
                + " · choose an app", 11, Ui.MUTED, false);
        sheet.addView(sub, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 3, a));

        if (installed(a, WA)) {
            addOption(a, sheet, "◍", Ui.ACCENT, "WhatsApp Status",
                    "Share all selected clips · pick \"My status\"", () -> {
                        d.dismiss();
                        sendStatus(a, list);
                    });
            addOption(a, sheet, "◉", Ui.ACCENT, "WhatsApp",
                    "Send to a chat", () -> {
                        d.dismiss();
                        launch(a, list, WA);
                    });
        }
        if (installed(a, WA_BUSINESS)) {
            addOption(a, sheet, "◉", Ui.ACCENT, "WhatsApp Business",
                    "Send to a chat", () -> {
                        d.dismiss();
                        launch(a, list, WA_BUSINESS);
                    });
        }
        if (installed(a, INSTAGRAM)) {
            addOption(a, sheet, "◎", Ui.PRIMARY, "Instagram",
                    "Reels, stories or DM", () -> {
                        d.dismiss();
                        launch(a, list, INSTAGRAM);
                    });
        }
        if (installed(a, FACEBOOK)) {
            addOption(a, sheet, "f", Ui.PRIMARY, "Facebook",
                    "Feed, story or message", () -> {
                        d.dismiss();
                        launch(a, list, FACEBOOK);
                    });
        }
        if (installed(a, TELEGRAM)) {
            addOption(a, sheet, "➤", Ui.PRIMARY, "Telegram",
                    "Send to a chat", () -> {
                        d.dismiss();
                        launch(a, list, TELEGRAM);
                    });
        }

        addOption(a, sheet, "⋯", Ui.MUTED, "More apps",
                "Standard Android share sheet", () -> {
                    d.dismiss();
                    chooser(a, list);
                });

        TextView cancel = Ui.text(a, "Cancel", 13, Ui.MUTED, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 12));
        cancel.setOnClickListener(v -> d.dismiss());
        sheet.addView(cancel, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, a));

        wrap.addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        d.setContentView(wrap);

        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.6f;
            w.setAttributes(lp);
        }
        d.show();
    }

    private interface Action {
        void run();
    }

    private static void addOption(Activity a, LinearLayout parent, String glyph, int glyphColor,
                                  String title, String subtitle, final Action action) {
        LinearLayout row = Ui.row(a);
        row.setPadding(0, Ui.dp(a, 9), 0, Ui.dp(a, 9));
        row.setOnClickListener(v -> action.run());

        TextView badge = Ui.text(a, glyph, 17, glyphColor, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.round(Ui.SURFACE_2, 13, a));
        row.addView(badge, new LinearLayout.LayoutParams(
                Ui.dp(a, 40), Ui.dp(a, 40)));

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(a, title, 14, Ui.TEXT, true));
        texts.addView(Ui.text(a, subtitle, 10.5f, Ui.MUTED, false));
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Ui.margin(texts, 12, 0, 0, 0, a);

        parent.addView(row, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 7, a));
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String mimeOf(Activity a, Uri uri) {
        String t = null;
        try {
            t = a.getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        return t == null ? "video/mp4" : t;
    }

    private static Intent baseIntent(Activity a, ArrayList<Uri> uris) {
        Intent i = new Intent(uris.size() == 1
                ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        i.setType(mimeOf(a, uris.get(0)));
        if (uris.size() == 1) {
            i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        ClipData clip = ClipData.newUri(a.getContentResolver(), "clip", uris.get(0));
        for (int k = 1; k < uris.size(); k++) {
            clip.addItem(new ClipData.Item(uris.get(k)));
        }
        i.setClipData(clip);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    private static void launch(Activity a, ArrayList<Uri> uris, String pkg) {
        Intent i = baseIntent(a, uris);
        if (pkg != null) {
            i.setPackage(pkg);
        }
        try {
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            if (pkg != null) {
                chooser(a, uris);
            } else {
                toast(a, "No app available to share.");
            }
        } catch (Exception e) {
            toast(a, "Could not open the share target.");
        }
    }

    /** Tries WhatsApp's status-specific action, then falls back to its normal share flow. */
    private static void sendStatus(Activity a, ArrayList<Uri> uris) {
        // Send every selected clip. WhatsApp decides how many it can put in one
        // status sequence; ACTION_SEND_MULTIPLE is the only public mechanism.
        Intent i = baseIntent(a, uris);
        i.setAction("com.whatsapp.intent.action.SHARE_TO_STATUS");
        i.setPackage(WA);
        try {
            if (i.resolveActivity(a.getPackageManager()) != null) {
                a.startActivity(i);
                return;
            }
        } catch (Exception ignored) {
        }
        // Fallback: WhatsApp's standard share screen also offers "My status".
        launch(a, uris, WA);
    }

    private static void chooser(Activity a, ArrayList<Uri> uris) {
        try {
            Intent i = baseIntent(a, uris);
            a.startActivity(Intent.createChooser(i, "Share clips"));
        } catch (Exception e) {
            toast(a, "No app available to share.");
        }
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }
}