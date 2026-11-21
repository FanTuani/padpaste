package io.github.fantuani.draftcanvas;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.fantuani.padpaste.DuplexSync;
import io.github.fantuani.padpaste.SyncService;

final class QuestionInbox implements DuplexSync.Listener {
    static final int PICK_PAIRING = 630;
    private static final String RECEIVE_ENABLED = "receive_enabled";
    private record Candidate(byte[] data, Bitmap bitmap) {}
    private final Activity activity;
    private final CanvasView canvas;
    private final DraftStore draftStore;
    private final QuestionAssets assets;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView importButton, cancelButton, locateButton;
    private final OfferRipple ripple;
    private boolean locatePulsePending;
    private final RectF bounds = new RectF();
    private QuestionImage placement;
    private ValueAnimator motion;
    private float offerAlpha, offerScale, offerLift, controlsAlpha;
    private final DuplexSync link;
    private TextView connectionLabel;
    private final BroadcastReceiver syncStatus = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { renderSyncStatus(); }
    };
    private AlertDialog syncDialog;
    private Candidate candidate;
    private boolean active, importing;
    private long previousId, lastImport;

    QuestionInbox(Activity activity, FrameLayout root, CanvasView canvas, DraftStore draftStore) {
        this.activity = activity;
        this.canvas = canvas;
        this.draftStore = draftStore;
        this.assets = QuestionAssets.get(activity);
        preferences = activity.getSharedPreferences("question_link", Activity.MODE_PRIVATE);
        link = DuplexSync.get(activity);
        canvas.questionAssets = assets;

        FrameLayout overlay = new FrameLayout(activity);
        overlay.setClipChildren(false);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.setElevation(dp(5));
        ripple = new OfferRipple(activity);
        overlay.addView(ripple, new FrameLayout.LayoutParams(-1, -1));
        importButton = button(overlay, "导入", Color.WHITE, 0xFF167B70, 74, this::accept);
        cancelButton = button(overlay, "取消", 0xFF40524E, 0xF5FFFEFA, 62, this::cancelOffer);
        locateButton = button(overlay, "查看题图", 0xFF167B70, 0xFFFFFEFA, 92, () -> {
            if (placement != null) canvas.locateQuestion(placement);
        });
        canvas.getViewTreeObserver().addOnPreDrawListener(() -> {
            positionControls();
            return true;
        });
    }

    void start() {
        active = true;
        updateSubscription();
        SyncService.resumeMonitoring(activity);
    }

    void stop() {
        active = false;
        updateSubscription();
    }

    private void updateSubscription() {
        link.listen(active && preferences.getBoolean(RECEIVE_ENABLED, true) ? this : null);
    }

    @Override public void status(boolean connected) { renderSyncStatus(); }

    private void renderSyncStatus() {
        if (connectionLabel == null) return;
        String state = !link.paired() ? "尚未配对"
                : link.connected() ? (link.duplex() ? "已连接 Mac" : "已连接 · 旧版 Mac")
                : !preferences.getBoolean(RECEIVE_ENABLED, true) && !SyncService.enabled(activity) ? "已暂停"
                : "等待 Mac 连接";
        connectionLabel.setText(state);
        connectionLabel.setTextColor(link.connected() ? 0xFF167B70 : 0xFF65706D);
    }

    @android.annotation.SuppressLint({"UnspecifiedRegisterReceiverFlag", "UseSwitchCompatOrMaterialCode"})
    void settings() {
        if (syncDialog != null) return;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(6));
        connectionLabel = new TextView(activity);
        connectionLabel.setTextSize(19);
        connectionLabel.setTypeface(null, Typeface.BOLD);
        content.addView(connectionLabel, new LinearLayout.LayoutParams(-1, dp(46)));
        Switch receive = new Switch(activity);
        receive.setText("Mac 剪贴板 → 草稿纸");
        receive.setChecked(preferences.getBoolean(RECEIVE_ENABLED, true));
        receive.setOnCheckedChangeListener((view, enabled) -> {
            preferences.edit().putBoolean(RECEIVE_ENABLED, enabled).apply();
            updateSubscription();
            renderSyncStatus();
        });
        content.addView(receive, new LinearLayout.LayoutParams(-1, dp(54)));
        Switch send = new Switch(activity);
        send.setText("平板截图 → Mac 剪贴板");
        send.setChecked(SyncService.enabled(activity));
        send.setOnCheckedChangeListener((view, enabled) -> {
            SyncService.setEnabled(activity, enabled);
            if (enabled && !SyncService.filesAllowed(activity)) {
                if (Build.VERSION.SDK_INT >= 30) {
                    activity.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:" + activity.getPackageName())));
                } else {
                    activity.requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 103);
                }
            }
            renderSyncStatus();
        });
        content.addView(send, new LinearLayout.LayoutParams(-1, dp(54)));
        syncDialog = new AlertDialog.Builder(activity).setTitle("同步").setView(content)
                .setPositiveButton("完成", null)
                .setNegativeButton("配对 Mac", (dialog, index) -> pairingSettings())
                .setNeutralButton("重新连接", null).create();
        syncDialog.setOnDismissListener(dialog -> {
            activity.unregisterReceiver(syncStatus);
            syncDialog = null;
            connectionLabel = null;
        });
        IntentFilter changes = new IntentFilter(SyncService.STATE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(syncStatus, changes, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(syncStatus, changes);
        syncDialog.show();
        syncDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> link.reconnect());
        renderSyncStatus();
    }

    private void pairingSettings() {
        EditText input = new EditText(activity);
        input.setHint("粘贴配对码");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("配对 Mac")
                .setMessage("从 Mac 的 PadPaste 菜单复制同步配对码，或导入配对文件。")
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
            link.pair(code);
            SyncService.resumeMonitoring(activity);
            toast("配对已保存");
            return true;
        } catch (Exception error) {
            toast("无法读取配对码");
            return false;
        }
    }

    @Override public boolean image(byte[] data) {
        try {
            Bitmap bitmap = QuestionAssets.decode(data);
            main.post(() -> {
                if (!active || !preferences.getBoolean(RECEIVE_ENABLED, true)) return;
                candidate = new Candidate(data, bitmap);
                showCandidate();
            });
            return true;
        } catch (java.io.IOException invalid) { return false; }
    }

    private void showCandidate() {
        if (candidate == null || importing || canvas.getWidth() == 0 || canvas.getHeight() == 0) return;
        long now = SystemClock.elapsedRealtime();
        placement = canvas.ink.previewQuestion(candidate.bitmap().getWidth(), candidate.bitmap().getHeight(),
                canvas.getWidth(), canvas.getHeight(), dp(36), now - lastImport < 120_000 ? previousId : 0);
        locatePulsePending = true;
        canvas.showQuestionOffer(placement, candidate.bitmap(), 0, () -> accept(true));
        style(0, .92f, dp(12), 0);
        animate(230, true, t -> {
            float visible = Math.min(1, t);
            style(.64f * visible, .92f + .08f * t, dp(12) * (1 - t), visible);
        }, () -> style(.64f, 1, 0, 1));
    }

    private void positionControls() {
        if (placement == null && candidate != null && !importing) showCandidate();
        boolean inline = false;
        boolean resizing = canvas.resizingQuestionOffer();
        boolean pending = placement != null && candidate != null && !importing && !resizing;
        if (placement != null && !resizing && (pending || controlsAlpha > 0)) {
            canvas.questionBounds(placement, bounds);
            inline = bounds.centerX() >= 0 && bounds.centerX() <= canvas.getWidth()
                    && bounds.centerY() >= 0 && bounds.centerY() <= canvas.getHeight();
        }
        importButton.setEnabled(pending);
        cancelButton.setEnabled(pending);
        visibility(importButton, inline);
        visibility(cancelButton, inline);
        visibility(locateButton, pending && !inline);
        if (inline) {
            float x = bounds.centerX(), y = bounds.centerY() + offerLift;
            float gap = dp(8);
            float offset = (importButton.getMeasuredWidth() + cancelButton.getMeasuredWidth()) / 2f + gap;
            float cancelX = x + offset + cancelButton.getMeasuredWidth() / 2f <= canvas.getWidth()
                    ? x + offset : x - offset;
            position(importButton, x, y);
            position(cancelButton, cancelX, y);
        } else if (pending) {
            position(locateButton, bounds.centerX(), bounds.centerY());
            if (locatePulsePending && locateButton.getWidth() > 0) {
                ripple.pulse(locateButton.getX() + locateButton.getWidth() / 2f,
                        locateButton.getY() + locateButton.getHeight() / 2f);
            }
        }
        if (inline || (pending && locateButton.getWidth() > 0)) locatePulsePending = false;
    }

    private void visibility(View view, boolean visible) {
        int state = visible ? View.VISIBLE : View.INVISIBLE;
        if (view.getVisibility() != state) view.setVisibility(state);
    }

    private void position(View view, float x, float y) {
        float halfWidth = view.getMeasuredWidth() / 2f, halfHeight = view.getMeasuredHeight() / 2f;
        float edge = dp(8);
        view.setTranslationX(Math.max(edge, Math.min(canvas.getWidth() - edge - 2 * halfWidth, x - halfWidth)));
        view.setTranslationY(Math.max(edge, Math.min(canvas.getHeight() - edge - 2 * halfHeight, y - halfHeight)));
    }

    private void cancelOffer() {
        if (candidate == null || importing) return;
        canvas.showQuestionOffer(placement, candidate.bitmap(), 0, null);
        candidate = null;
        float startAlpha = offerAlpha, startScale = offerScale, startLift = offerLift, startControls = controlsAlpha;
        animate(120, false, t -> style(startAlpha * (1 - t), startScale - .03f * t,
                startLift + dp(6) * t, startControls * (1 - t)), this::clearOffer);
        canvas.postInvalidateOnAnimation();
    }

    private void accept() {
        accept(false);
    }

    private void accept(boolean dragging) {
        if (candidate == null || placement == null || importing) return;
        Candidate accepted = candidate;
        candidate = null;
        importing = true;
        if (dragging) {
            ValueAnimator previous = motion;
            motion = null;
            if (previous != null) previous.cancel();
            style(1, 1, 0, 0);
        } else {
            canvas.showQuestionOffer(placement, accepted.bitmap(), 0, null);
            float startAlpha = offerAlpha, startScale = offerScale, startLift = offerLift, startControls = controlsAlpha;
            animate(125, false, t -> style(startAlpha + (.83f - startAlpha) * t,
                    startScale + (1 - startScale) * t, startLift * (1 - t), startControls * (1 - t)), () -> {});
        }
        assets.save(accepted.data(), accepted.bitmap(), asset -> {
            if (asset == null) {
                importing = false;
                if (candidate == null) candidate = accepted;
                showCandidate();
                toast("题图保存失败");
            } else {
                place(asset, accepted, dragging);
            }
        });
    }

    private void place(String asset, Candidate accepted, boolean dragged) {
        if (!canvas.canImportQuestion()) {
            main.postDelayed(() -> place(asset, accepted, dragged), 60);
            return;
        }
        QuestionImage target = canvas.questionOfferPlacement();
        QuestionImage image = canvas.ink.importQuestion(asset, target);
        previousId = image.id;
        lastImport = SystemClock.elapsedRealtime();
        if (!dragged) canvas.showQuestionOffer(target, accepted.bitmap(), image.id, null);
        canvas.questionAdded();
        draftStore.flush();
        if (dragged) {
            finishImport(image);
            return;
        }
        float startAlpha = offerAlpha, startScale = offerScale, startLift = offerLift, startControls = controlsAlpha;
        animate(175, false, t -> style(startAlpha + (1 - startAlpha) * t, startScale + (1 - startScale) * t,
                startLift * (1 - t), startControls * (1 - t)), () -> finishImport(image));
    }

    private void finishImport(QuestionImage image) {
        clearOffer();
        canvas.selectQuestion(image);
        importing = false;
        showCandidate();
    }

    private void style(float alpha, float scale, float lift, float controls) {
        offerAlpha = alpha;
        offerScale = scale;
        offerLift = lift;
        controlsAlpha = controls;
        canvas.styleQuestionOffer(alpha, scale, lift);
        float buttonScale = .91f + .09f * controlsAlpha;
        for (TextView button : new TextView[]{importButton, cancelButton, locateButton}) {
            button.setAlpha(controlsAlpha);
            button.setScaleX(buttonScale);
            button.setScaleY(buttonScale);
        }
    }

    private void clearOffer() {
        placement = null;
        canvas.showQuestionOffer(null, null, 0, null);
    }

    private void animate(long duration, boolean spring, java.util.function.Consumer<Float> update, Runnable end) {
        ValueAnimator next = ValueAnimator.ofFloat(0, 1);
        ValueAnimator previous = motion;
        motion = next;
        if (previous != null) previous.cancel();
        next.setDuration(duration);
        next.setInterpolator(spring ? new OvershootInterpolator(.95f) : new PathInterpolator(.2f, .74f, .24f, 1f));
        next.addUpdateListener(frame -> update.accept((Float) frame.getAnimatedValue()));
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (motion != next) return;
                motion = null;
                end.run();
            }
        });
        next.start();
    }

    private TextView button(FrameLayout overlay, String title, int color, int background, int width, Runnable callback) {
        TextView button = new TextView(activity);
        button.setText(title);
        button.setTextColor(color);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(null, Typeface.BOLD);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(background);
        surface.setCornerRadius(dp(20));
        if (background != 0xFF167B70) surface.setStroke(dp(1), 0xFFE2E8E3);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x1993AEA3), surface, null));
        button.setElevation(dp(2));
        button.setOnClickListener(view -> callback.run());
        button.setVisibility(View.INVISIBLE);
        overlay.addView(button, new FrameLayout.LayoutParams(dp(width), dp(44)));
        return button;
    }

    private void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show(); }
    private int dp(float value) { return Math.round(activity.getResources().getDisplayMetrics().density * value); }
}
