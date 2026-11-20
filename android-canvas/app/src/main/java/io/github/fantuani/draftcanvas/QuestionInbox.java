package io.github.fantuani.draftcanvas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

final class QuestionInbox implements QuestionConnection.Listener {
    static final int PICK_PAIRING = 630;
    private static final String PAIRING = "pairing";
    private final Activity activity;
    private final CanvasView canvas;
    private final DraftStore draftStore;
    private final QuestionAssets assets;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout prompt;
    private QuestionConnection connection;
    private AlertDialog syncDialog;
    private QuestionConnection.Candidate candidate;
    private boolean active, connected, importing;
    private long previousId, lastImport;

    QuestionInbox(Activity activity, FrameLayout root, CanvasView canvas, DraftStore draftStore) {
        this.activity = activity;
        this.canvas = canvas;
        this.draftStore = draftStore;
        this.assets = QuestionAssets.get(activity);
        this.preferences = activity.getSharedPreferences("question_link", Activity.MODE_PRIVATE);
        canvas.questionAssets = assets;

        prompt = new LinearLayout(activity);
        prompt.setGravity(Gravity.CENTER_VERTICAL);
        prompt.setPadding(dp(12), dp(8), dp(8), dp(8));
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(0xFFFFFEFA);
        surface.setCornerRadius(dp(18));
        surface.setStroke(dp(1), 0xFFE2E8E3);
        prompt.setBackground(surface);
        prompt.setElevation(dp(5));
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        position.bottomMargin = dp(25);
        root.addView(prompt, position);
        prompt.setVisibility(View.GONE);
    }

    void start() {
        active = true;
        if (connection != null) return;
        String code = preferences.getString(PAIRING, "");
        if (code.isEmpty()) return;
        try {
            connection = new QuestionConnection(activity, QuestionConnection.Pairing.parse(code), this);
            connection.start();
        } catch (Exception error) {
            status(false);
        }
    }

    void stop() {
        active = false;
        if (connection != null) connection.stop();
        connection = null;
        status(false);
    }

    @Override public void status(boolean online) {
        connected = online;
        if (syncDialog != null) syncDialog.setTitle(syncTitle());
    }

    private String syncTitle() {
        String state = preferences.contains(PAIRING) ? (connected ? "已连接 Mac" : "等待 Mac 连接") : "尚未配对";
        return "同步 · " + state;
    }

    void settings() {
        syncDialog = new AlertDialog.Builder(activity).setTitle(syncTitle())
                .setItems(new String[]{"平板截图 → Mac 剪贴板", "Mac 剪贴板 → 草稿纸"}, (dialog, index) -> {
                    if (index == 0) activity.startActivity(new Intent(activity, io.github.fantuani.padpaste.MainActivity.class));
                    else pairingSettings();
                }).create();
        syncDialog.setOnDismissListener(dialog -> syncDialog = null);
        syncDialog.show();
    }

    private void pairingSettings() {
        EditText input = new EditText(activity);
        input.setHint("粘贴配对码");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Mac 剪贴板 → 草稿纸")
                .setMessage("从 Mac 菜单栏的 PadPaste 复制配对码，也可以导入配对文件。")
                .setView(input)
                .setPositiveButton("配对", null)
                .setNeutralButton("导入文件", (d, which) -> activity.startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),
                        PICK_PAIRING))
                .setNegativeButton("返回", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (pair(input.getText().toString())) dialog.dismiss();
        }));
        dialog.show();
    }

    boolean pair(String code) {
        try {
            QuestionConnection.Pairing.parse(code);
            preferences.edit().putString(PAIRING, code.trim()).apply();
            boolean restart = active;
            stop();
            if (restart) start();
            toast("配对已保存");
            return true;
        } catch (Exception error) {
            toast("无法读取配对码");
            return false;
        }
    }

    @Override public void candidate(QuestionConnection.Candidate image) {
        candidate = image;
        showCandidate();
    }

    private void showCandidate() {
        if (candidate == null) { hidePrompt(); return; }
        prompt.removeAllViews();
        ImageView thumbnail = new ImageView(activity);
        thumbnail.setImageBitmap(candidate.bitmap());
        thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumbnail.setContentDescription("收到的图片预览");
        prompt.addView(thumbnail, new LinearLayout.LayoutParams(dp(58), dp(44)));
        prompt.addView(label("电脑图片", 0xFF243137));
        TextView ignore = action("忽略", 0xFF596A68, () -> {
            candidate = null;
            hidePrompt();
        });
        prompt.addView(ignore);
        prompt.addView(action("导入", 0xFF167B70, this::accept));
        prompt.setVisibility(View.VISIBLE);
    }

    private void accept() {
        if (candidate == null || importing) return;
        QuestionConnection.Candidate accepted = candidate;
        candidate = null;
        importing = true;
        hidePrompt();
        assets.save(accepted.data(), accepted.bitmap(), asset -> {
            if (asset == null) {
                importing = false;
                if (candidate == null) candidate = accepted;
                showCandidate();
                toast("题图保存失败");
            } else {
                place(asset, accepted.bitmap().getWidth(), accepted.bitmap().getHeight());
            }
        });
    }

    private void place(String asset, int width, int height) {
        if (!canvas.canImportQuestion()) {
            main.postDelayed(() -> place(asset, width, height), 60);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        QuestionImage image = canvas.ink.importQuestion(asset, width, height, canvas.getWidth(),
                canvas.getHeight(), dp(36), now - lastImport < 120_000 ? previousId : 0);
        previousId = image.id;
        canvas.selectQuestion(image);
        lastImport = now;
        importing = false;
        canvas.refresh();
        draftStore.flush();
        if (candidate != null) showCandidate();
        else if (active && !canvas.questionVisible(image)) showLocate(image);
    }

    private void showLocate(QuestionImage image) {
        prompt.removeAllViews();
        prompt.addView(label("题图已导入", Color.DKGRAY));
        prompt.addView(action("定位", 0xFF167B70, () -> {
            QuestionImage current = canvas.ink.question(image.id);
            if (current != null && canvas.canImportQuestion()) canvas.locateQuestion(current);
            prompt.setVisibility(View.GONE);
        }));
        prompt.addView(action("关闭", 0xFF596A68, () -> prompt.setVisibility(View.GONE)));
        prompt.setVisibility(View.VISIBLE);
    }

    private TextView label(String title, int color) {
        TextView label = new TextView(activity);
        label.setText(title);
        label.setTextSize(14);
        label.setTextColor(color);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(12), 0, dp(12), 0);
        label.setMinHeight(dp(44));
        return label;
    }

    private void hidePrompt() {
        prompt.setVisibility(View.GONE);
        prompt.removeAllViews();
    }

    private TextView action(String title, int color, Runnable callback) {
        TextView button = label(title, color);
        button.setTypeface(null, Typeface.BOLD);
        button.setOnClickListener(view -> callback.run());
        return button;
    }

    private void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show(); }
    private int dp(float value) { return Math.round(activity.getResources().getDisplayMetrics().density * value); }
}
