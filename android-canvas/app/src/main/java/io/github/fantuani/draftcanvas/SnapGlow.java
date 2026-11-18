package io.github.fantuani.draftcanvas;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

final class SnapGlow extends View {
    private static final int GREEN = 0xff89e8ac;
    private final Paint paint = new Paint();
    private final ValueAnimator fade = ValueAnimator.ofInt(170, 0);
    private Shader top, bottom, left, right;
    private float depth;
    private int opacity;

    SnapGlow(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        fade.setDuration(200);
        fade.setInterpolator(new DecelerateInterpolator());
        fade.addUpdateListener(animator -> {
            opacity = (Integer) animator.getAnimatedValue();
            invalidate();
        });
    }

    void flash() {
        fade.cancel();
        fade.start();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        depth = Math.min(32 * getResources().getDisplayMetrics().density, Math.min(width, height) / 2f);
        if (depth == 0) return;
        top = gradient(0, 0, 0, depth);
        bottom = gradient(0, height, 0, height - depth);
        left = gradient(0, 0, depth, 0);
        right = gradient(width, 0, width - depth, 0);
    }

    private static Shader gradient(float x1, float y1, float x2, float y2) {
        return new LinearGradient(x1, y1, x2, y2, GREEN, GREEN & 0x00ffffff, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (opacity == 0 || depth == 0) return;
        int width = getWidth(), height = getHeight();
        paint.setAlpha(opacity);
        paint.setShader(top);
        canvas.drawRect(0, 0, width, depth, paint);
        paint.setShader(bottom);
        canvas.drawRect(0, height - depth, width, height, paint);
        paint.setShader(left);
        canvas.drawRect(0, 0, depth, height, paint);
        paint.setShader(right);
        canvas.drawRect(width - depth, 0, width, height, paint);
    }

    @Override protected void onDetachedFromWindow() {
        fade.cancel();
        opacity = 0;
        super.onDetachedFromWindow();
    }
}
