package io.github.fantuani.draftcanvas;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.animation.PathInterpolator;

final class OfferRipple extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix transform = new Matrix();
    private final RadialGradient ring = new RadialGradient(0, 0, 1,
            new int[]{0x002BAF84, 0x002BAF84, 0x182BAF84, 0x622BAF84, 0x002BAF84},
            new float[]{0, .83f, .90f, .965f, 1}, Shader.TileMode.CLAMP);
    private ValueAnimator animation;
    private float x, y, reach, progress;

    OfferRipple(Context context) {
        super(context);
        paint.setShader(ring);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void pulse(float originX, float originY) {
        x = originX;
        y = originY;
        reach = 1.1f * (float) Math.hypot(Math.max(x, getWidth() - x), Math.max(y, getHeight() - y));
        if (animation != null) animation.cancel();
        progress = 0;
        animation = ValueAnimator.ofFloat(0, 1);
        animation.setDuration(500);
        animation.setInterpolator(new PathInterpolator(.17f, .48f, .29f, 1f));
        animation.addUpdateListener(frame -> {
            progress = (float) frame.getAnimatedValue();
            postInvalidateOnAnimation();
        });
        animation.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (progress <= 0 || progress >= 1) return;
        float radius = reach * progress;
        transform.setScale(radius, radius);
        transform.postTranslate(x, y);
        ring.setLocalMatrix(transform);
        paint.setAlpha(Math.round(255 * Math.min(1, (1 - progress) * 3)));
        canvas.drawCircle(x, y, radius, paint);
    }

    @Override protected void onDetachedFromWindow() {
        if (animation != null) animation.cancel();
        progress = 1;
        super.onDetachedFromWindow();
    }
}
