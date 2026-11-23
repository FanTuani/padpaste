package io.github.fantuani.draftcanvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import io.github.fantuani.padpaste.DuplexSync;

final class TransferIndicator extends View {
    private static final PathInterpolator EASE_OUT = new PathInterpolator(.18f, .76f, .22f, 1f);
    private static final PathInterpolator EASE_IN = new PathInterpolator(.36f, 0f, .72f, .54f);
    private static final OvershootInterpolator SPRING = new OvershootInterpolator(1.15f);
    private static final long ENTER_MS = 240, MIN_SHOWN_MS = 300, EXIT_MS = 230, CANCEL_MS = 380;
    private static final int TEAL = 0xFF167B70, RED = 0xFFB54043;
    private final DuplexSync link;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float unit;
    private final Runnable frame = this::refresh;
    private DuplexSync.TransferProgress progress;
    private long shownAt, finishedAt, progressAt;
    private float startFraction, targetFraction, fraction;
    private float opacity, scale, red;

    TransferIndicator(Context context) {
        super(context);
        link = DuplexSync.get(context);
        unit = getResources().getDisplayMetrics().density;
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setOnClickListener(view -> {
            if (link.cancelTransfer(progress)) refresh();
        });
    }

    void start() {
        // A transfer already finished before resume does not need a replay.
        progress = link.transferProgress();
        if (progress != null && !progress.finished()) progress = null;
        link.observeProgress(this::refresh);
    }

    void stop() {
        link.observeProgress(null);
        removeCallbacks(frame);
        progress = null;
        setVisibility(GONE);
    }

    private void refresh() {
        removeCallbacks(frame);
        long now = SystemClock.uptimeMillis();
        DuplexSync.TransferProgress current = link.transferProgress();
        if (current == null) return;
        if (current != progress) {
            progress = current;
            shownAt = progressAt = now;
            finishedAt = 0;
            startFraction = targetFraction = fraction = 0;
            setContentDescription(current.uploading ? "正在上传截图，轻点取消" : "正在下载题图，轻点取消");
            setVisibility(VISIBLE);
        }
        if (getVisibility() != VISIBLE) return;

        if (current.finished() && finishedAt == 0) {
            finishedAt = current.cancelled() ? now : Math.max(now + 115, shownAt + MIN_SHOWN_MS);
        }
        setClickable(current.canCancel());
        updateFraction(current.fraction(), now);

        float enter = phase(now - shownAt, ENTER_MS);
        opacity = EASE_OUT.getInterpolation(Math.min(1f, enter * 1.65f));
        scale = .68f + .32f * SPRING.getInterpolation(enter);
        red = 0f;
        if (finishedAt != 0 && now >= finishedAt) {
            float exit = phase(now - finishedAt, current.cancelled() ? CANCEL_MS : EXIT_MS);
            if (exit >= 1f) {
                setVisibility(GONE);
                return;
            }
            if (current.cancelled()) {
                red = EASE_OUT.getInterpolation(Math.min(1f, exit * 5f));
                float rise = EASE_OUT.getInterpolation(Math.min(1f, exit / .35f));
                float fall = EASE_IN.getInterpolation(phaseAfter(exit, .35f));
                scale += .34f * rise - .17f * fall;
                opacity = Math.max(opacity, .85f) * (1f - EASE_IN.getInterpolation(phaseAfter(exit, .30f)));
            } else {
                float eased = EASE_IN.getInterpolation(exit);
                scale *= 1f - .16f * eased;
                opacity *= 1f - eased;
            }
        }
        invalidate();
        if (enter < 1f || finishedAt != 0 || fraction < targetFraction) postOnAnimation(frame);
        else postDelayed(frame, 40);
    }

    private void updateFraction(float target, long now) {
        float t = phase(now - progressAt, 135);
        fraction = startFraction + (targetFraction - startFraction) * EASE_OUT.getInterpolation(t);
        if (target != targetFraction) {
            startFraction = fraction;
            targetFraction = target;
            progressAt = now;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (progress == null) return;
        float x = getWidth() * .5f, y = getHeight() * .5f;
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(unit * scale, unit * scale);
        paint.setStyle(Paint.Style.FILL);
        color(0xFFFAFAF7, 0xFFFFECE9, .97f);
        canvas.drawCircle(0, 0, 15f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2f);
        color(0xFFC7DCD6, 0xFFE7A9A8, 1f);
        canvas.drawCircle(0, 0, 12f, paint);
        color(TEAL, RED, 1f);
        if (fraction > 0f) canvas.drawArc(-12, -12, 12, 12, -90, 360 * fraction, false, paint);
        float tip = progress.uploading ? -5f : 5f;
        float wing = progress.uploading ? -1f : 1f;
        canvas.drawLine(0, -tip, 0, tip, paint);
        canvas.drawLine(-4, wing, 0, tip, paint);
        canvas.drawLine(4, wing, 0, tip, paint);
        canvas.restore();
    }

    private void color(int normal, int cancelled, float alpha) {
        int r = blend(normal >> 16 & 255, cancelled >> 16 & 255);
        int g = blend(normal >> 8 & 255, cancelled >> 8 & 255);
        int b = blend(normal & 255, cancelled & 255);
        paint.setARGB(Math.round(255 * opacity * alpha), r, g, b);
    }

    private int blend(int a, int b) { return Math.round(a + (b - a) * red); }
    private static float phase(long elapsed, long duration) { return Math.min(1f, Math.max(0f, (float) elapsed / duration)); }
    private static float phaseAfter(float phase, float threshold) { return Math.max(0f, (phase - threshold) / (1f - threshold)); }
}
