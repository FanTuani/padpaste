package io.github.fantuani.draftcanvas;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import io.github.fantuani.padpaste.DuplexSync;
import io.github.fantuani.padpaste.SyncService;

final class QuestionInbox implements DuplexSync.Listener {
    static final String RECEIVE_ENABLED = "receive_enabled";
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
    private Candidate candidate;
    private boolean active, importing;
    private long previousId, lastImport;

    QuestionInbox(Activity activity, FrameLayout root, CanvasView canvas, DraftStore draftStore) {
        this.activity = activity;
        this.canvas = canvas;
        this.draftStore = draftStore;
        this.assets = QuestionAssets.get(activity);
        preferences = preferences(activity);
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
        locateButton = button(overlay, "查看题图", Color.WHITE, 0xFF0C5748, 92, () -> {
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

    void updateSubscription() {
        link.listen(active && preferences.getBoolean(RECEIVE_ENABLED, true) ? this : null);
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("question_link", Context.MODE_PRIVATE);
    }

    @Override public void status(boolean connected) {}

    @Override public boolean image(byte[] data, java.util.function.BooleanSupplier accept) {
        try {
            Bitmap bitmap = QuestionAssets.decode(data);
            if (!accept.getAsBoolean()) {
                bitmap.recycle();
                return false;
            }
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
        boolean showLocator = pending && !inline;
        boolean entering = showLocator && locateButton.getVisibility() != View.VISIBLE;
        if (!showLocator && locateButton.getVisibility() == View.VISIBLE) locateButton.animate().cancel();
        visibility(locateButton, showLocator);
        if (inline) {
            float x = bounds.centerX(), y = bounds.centerY() + offerLift;
            float gap = dp(8);
            float offset = (importButton.getMeasuredWidth() + cancelButton.getMeasuredWidth()) / 2f + gap;
            float cancelX = x + offset + cancelButton.getMeasuredWidth() / 2f <= canvas.getWidth()
                    ? x + offset : x - offset;
            position(importButton, x, y);
            position(cancelButton, cancelX, y);
        } else if (showLocator) {
            position(locateButton, bounds.centerX(), bounds.centerY());
            if (entering || locatePulsePending) animateLocator();
            if (locatePulsePending && locateButton.getWidth() > 0) {
                ripple.pulse(locateButton.getX() + locateButton.getWidth() / 2f,
                        locateButton.getY() + locateButton.getHeight() / 2f);
            }
        }
        if (inline || (pending && locateButton.getWidth() > 0)) locatePulsePending = false;
    }

    private void animateLocator() {
        locateButton.animate().cancel();
        locateButton.setScaleX(1.18f);
        locateButton.setScaleY(1.18f);
        locateButton.animate().scaleX(1).scaleY(1).setDuration(320)
                .setInterpolator(new PathInterpolator(.18f, .72f, .28f, 1f)).start();
    }

    private void visibility(View view, boolean visible) {
        int state = visible ? View.VISIBLE : View.INVISIBLE;
        if (view.getVisibility() != state) view.setVisibility(state);
    }

    private void position(View view, float x, float y) {
        float halfWidth = view.getMeasuredWidth() / 2f, halfHeight = view.getMeasuredHeight() / 2f;
        float edge = dp(view == locateButton ? 13 : 8);
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
        for (TextView button : new TextView[]{importButton, cancelButton}) {
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
        if (color != Color.WHITE) surface.setStroke(dp(1), 0xFFE2E8E3);
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
