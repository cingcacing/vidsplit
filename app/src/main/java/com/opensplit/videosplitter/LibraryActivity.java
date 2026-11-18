package com.opensplit.videosplitter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** "My Stuff": folders created by the splitter, with multi-select share/delete. */
public class LibraryActivity extends Activity {

    private static final class Vid {
        long id;
        Uri uri;
        String name;
        long durationMs;
        long sizeBytes;
        String folder;
    }

    private LinearLayout root;
    private LinearLayout list;
    private LinearLayout bottomBar;
    private TextView selectionLabel;
    private TextView empty;
    private EditText search;

    private final java.util.concurrent.ExecutorService thumbPool =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    private final List<Vid> all = new ArrayList<>();
    private final Set<Long> selected = new LinkedHashSet<>();
    private String query = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        buildUi();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onDestroy() {
        thumbPool.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 18);
        root.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 10));

        LinearLayout bar = Ui.row(this);
        TextView back = Ui.text(this, "‹", 30, Ui.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 14), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, "My Stuff", 22, Ui.TEXT, true));
        TextView sub = Ui.text(this, "Movies/VideoSplitter", 11, Ui.MUTED, false);
        titles.addView(sub);
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView selectAll = Ui.text(this, "Select all", 12, Ui.MUTED, true);
        selectAll.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        selectAll.setBackground(Ui.round(Ui.SURFACE_2, 9, this));
        selectAll.setOnClickListener(v -> {
            for (Vid vid : all) {
                selected.add(vid.id);
            }
            render();
        });
        bar.addView(selectAll);
        root.addView(bar);

        search = new EditText(this);
        search.setHint("Search clips");
        search.setHintTextColor(Ui.MUTED);
        search.setTextColor(Ui.TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setBackground(Ui.roundStroke(Ui.SURFACE, Ui.STROKE, 12, this));
        search.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.US);
                render();
            }
        });
        root.addView(search, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 14, this));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 20));
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = Ui.text(this, "No clips yet.\nSplit a video and it will appear here.",
                13, Ui.MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        root.addView(empty, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2, this));

        bottomBar = Ui.row(this);
        bottomBar.setBackground(Ui.roundStroke(Ui.SURFACE, Ui.STROKE, 14, this));
        int bp = Ui.dp(this, 10);
        bottomBar.setPadding(bp, bp, bp, bp);
        bottomBar.setVisibility(View.GONE);

        selectionLabel = Ui.text(this, "", 12, Ui.MUTED, true);
        bottomBar.addView(selectionLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView share = Ui.text(this, "Share", 13, Ui.ACCENT, true);
        share.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        share.setBackground(Ui.round(Ui.SURFACE_2, 10, this));
        share.setOnClickListener(v -> shareSelected());
        bottomBar.addView(share);

        TextView delete = Ui.text(this, "Delete", 13, Ui.DANGER, true);
        delete.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        delete.setBackground(Ui.round(Ui.SURFACE_2, 10, this));
        delete.setOnClickListener(v -> deleteSelected());
        Ui.margin(delete, 8, 0, 0, 0, this);
        bottomBar.addView(delete);

        root.addView(bottomBar, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8, this));

        setContentView(root);
    }

    private void load() {
        all.clear();
        selected.clear();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.RELATIVE_PATH
        };
        String sel = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = {Environment.DIRECTORY_MOVIES + "/VideoSplitter%"};
        try (Cursor c = getContentResolver().query(collection, proj, sel, args,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    Vid v = new Vid();
                    v.id = c.getLong(0);
                    v.uri = android.content.ContentUris.withAppendedId(collection, v.id);
                    v.name = c.getString(1);
                    v.durationMs = c.getLong(2);
                    v.sizeBytes = c.getLong(3);
                    String rel = c.getString(4);
                    v.folder = folderOf(rel);
                    all.add(v);
                }
            }
        } catch (Exception ignored) {
        }
        render();
    }

    private String folderOf(String rel) {
        if (rel == null) {
            return "VideoSplitter";
        }
        String marker = "VideoSplitter/";
        int i = rel.indexOf(marker);
        if (i < 0) {
            return "VideoSplitter";
        }
        String rest = rel.substring(i + marker.length());
        rest = rest.replaceAll("/+$", "");
        return rest.isEmpty() ? "VideoSplitter" : rest;
    }

    private void render() {
        list.removeAllViews();
        Map<String, List<Vid>> groups = new LinkedHashMap<>();
        int shown = 0;
        for (Vid v : all) {
            if (!query.isEmpty() && !v.name.toLowerCase(Locale.US).contains(query)
                    && !v.folder.toLowerCase(Locale.US).contains(query)) {
                continue;
            }
            List<Vid> g = groups.get(v.folder);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(v.folder, g);
            }
            g.add(v);
            shown++;
        }
        empty.setVisibility(all.isEmpty() || shown == 0 ? View.VISIBLE : View.GONE);
        if (all.isEmpty()) {
            empty.setText("No clips yet.\nSplit a video and it will appear here.");
        } else if (shown == 0) {
            empty.setText("No clips match \"" + query + "\".");
        }

        for (Map.Entry<String, List<Vid>> e : groups.entrySet()) {
            list.addView(folderHeader(e.getKey(), e.getValue()));
            for (Vid v : e.getValue()) {
                list.addView(videoRow(v), Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 6, this));
            }
        }
        updateBottom();
    }

    private View folderHeader(String name, List<Vid> vids) {
        long total = 0;
        for (Vid v : vids) {
            total += v.sizeBytes;
        }
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 4));
        TextView t = Ui.text(this, name, 15, Ui.TEXT, true);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView meta = Ui.text(this,
                vids.size() + " clips · " + Ui.size(total), 11, Ui.MUTED, false);
        row.addView(meta);
        return row;
    }

    private View videoRow(Vid v) {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 10), Ui.dp(this, 10),
                Ui.dp(this, 10), Ui.dp(this, 10));
        LinearLayout row = Ui.row(this);

        CheckBox cb = new CheckBox(this);
        cb.setChecked(selected.contains(v.id));
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        cb.setOnClickListener(x -> toggle(v.id));
        row.addView(cb, new LinearLayout.LayoutParams(
                Ui.dp(this, 34), Ui.dp(this, 34)));

        ImageView iv = new ImageView(this);
        iv.setBackground(Ui.round(Ui.SURFACE_2, 9, this));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.dp(LibraryActivity.this, 9));
            }
        });
        final String tag = v.uri.toString();
        iv.setTag(tag);
        thumbPool.execute(() -> {
            Bitmap bmp = Thumbs.load(LibraryActivity.this, v.uri,
                    Ui.dp(LibraryActivity.this, 140), Ui.dp(LibraryActivity.this, 90));
            if (bmp == null) {
                return;
            }
            runOnUiThread(() -> {
                if (tag.equals(iv.getTag())) {
                    iv.setImageBitmap(bmp);
                }
            });
        });
        row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 40)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(this, v.name, 12, Ui.TEXT, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(name);
        col.addView(Ui.text(this, Ui.time(v.durationMs) + " · " + Ui.size(v.sizeBytes),
                10, Ui.MUTED, false));
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Ui.margin(col, 10, 0, 6, 0, this);

        TextView share = Ui.text(this, "↗", 13, Ui.ACCENT, true);
        share.setGravity(Gravity.CENTER);
        share.setPadding(Ui.dp(this, 9), Ui.dp(this, 5), Ui.dp(this, 9), Ui.dp(this, 5));
        share.setBackground(Ui.round(Ui.SURFACE_2, 8, this));
        share.setOnClickListener(x -> ShareSheet.show(this,
                java.util.Collections.singletonList(v.uri), "Share clip"));
        row.addView(share);

        TextView play = Ui.text(this, "Play", 11, Ui.PRIMARY, true);
        play.setPadding(Ui.dp(this, 9), Ui.dp(this, 7), Ui.dp(this, 9), Ui.dp(this, 7));
        play.setBackground(Ui.round(Ui.SURFACE_2, 8, this));
        play.setOnClickListener(x -> play(v));
        Ui.margin(play, 6, 0, 0, 0, this);
        row.addView(play);

        card.addView(row);
        card.setOnClickListener(x -> toggle(v.id));
        return card;
    }

    private void toggle(long id) {
        if (!selected.remove(id)) {
            selected.add(id);
        }
        render();
    }

    private void updateBottom() {
        bottomBar.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        selectionLabel.setText(selected.size() + " selected");
    }

    private List<Uri> selectedUris() {
        List<Uri> out = new ArrayList<>();
        for (Vid v : all) {
            if (selected.contains(v.id)) {
                out.add(v.uri);
            }
        }
        return out;
    }

    private void shareSelected() {
        ArrayList<Uri> uris = new ArrayList<>(selectedUris());
        if (uris.isEmpty()) {
            return;
        }
        ShareSheet.show(this, uris, "Share clips");
    }

    private void deleteSelected() {
        if (selected.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete " + selected.size() + " clips?")
                .setMessage("They will be removed from your gallery. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    int ok = 0;
                    for (Uri u : selectedUris()) {
                        try {
                            if (getContentResolver().delete(u, null, null) > 0) {
                                ok++;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    toast(ok + " clips deleted");
                    selected.clear();
                    load();
                })
                .show();
    }

    private void play(Vid v) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("uri", v.uri.toString());
        i.putExtra("title", v.name);
        startActivity(i);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}