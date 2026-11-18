package io.github.fantuani.draftcanvas;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

final class FanMenu extends ViewGroup {
    static final int SIZE = 1, CLEAR = 2, COPY = 3, PASTE = 4, DELETE = 5, CANCEL = 6, SELECT = 7;
    private static final int INK = 0xFF243137, TEAL = 0xFF167B70, RED = 0xFFAA5148;
    private static final float[] TOOLS = {-178, -148, -118, -88, 9, 44, 0};
    private static final float[] SELECTION = {-165, -126, -87, -48};
    private static final PathInterpolator OPEN = new PathInterpolator(.18f, .76f, .22f, 1f);
    private static final PathInterpolator CLOSE = new PathInterpolator(.36f, 0f, .72f, .54f);

    interface Actions {
        void color(int color);
        void command(int id);
    }

    private final Actions actions;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private ValueAnimator animation;
    private boolean selection, shown, closing;
    private float requestedX, requestedY, centerX, centerY, directionX, directionY;
    private float progress, radius, unit;

    FanMenu(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        setWillNotDraw(false);
        setClipChildren(false);
        setVisibility(GONE);
    }

    boolean isOpen() { return shown && !closing; }

    void showTools(float x, float y, int activeColor, boolean hasInk) {
        prepare(x, y, false);
        swatch("墨黑", INK, activeColor);
        swatch("朱红", 0xFFCF4941, activeColor);
        swatch("靛蓝", 0xFF3169CA, activeColor);
        swatch("青绿", TEAL, activeColor);
        command("粗细", SIZE, true);
        command("清空", CLEAR, hasInk);
        command("框选", SELECT, true);
        open();
    }

    void showSelection(float x, float y, boolean hasSelection, boolean canPaste) {
        prepare(x, y, true);
        command("复制", COPY, hasSelection);
        command("粘贴", PASTE, canPaste);
        command("删除", DELETE, hasSelection);
        command("取消", CANCEL, true);
        open();
    }

    private void prepare(float x, float y, boolean selecting) {
        if (animation != null) animation.cancel();
        removeAllViews();
        selection = selecting;
        requestedX = x;
        requestedY = y;
        shown = true;
        closing = false;
        progress = 0f;
    }

    private void swatch(String label, int color, int activeColor) {
        Item tile = new Item(label, 0, color);
        tile.setSelected(color == activeColor);
        tile.setOnClickListener(view -> {
            dismiss(true);
            actions.color(color);
        });
        addView(tile);
    }

    private void command(String label, int id, boolean enabled) {
        Item tile = new Item(label, id, 0);
        tile.setEnabled(enabled);
        tile.setOnClickListener(view -> {
            dismiss(true);
            actions.command(id);
        });
        addView(tile);
    }

    private void open() {
        setVisibility(VISIBLE);
        requestLayout();
        animateTo(1f, 205, OPEN, false);
    }

    void dismiss(boolean animated) {
        if (!shown) return;
        if (animation != null) animation.cancel();
        if (!animated) {
            hide();
            return;
        }
        closing = true;
        animateTo(0f, 105, CLOSE, true);
    }

    private void hide() {
        shown = closing = false;
        setVisibility(GONE);
    }

    private void animateTo(float end, int duration, PathInterpolator ease, boolean hideAtEnd) {
        animation = ValueAnimator.ofFloat(progress, end);
        animation.setDuration(duration);
        animation.setInterpolator(ease);
        animation.addUpdateListener(value -> {
            progress = (float) value.getAnimatedValue();
            placeItems();
            invalidate();
        });
        if (hideAtEnd) {
            animation.addListener(new AnimatorListenerAdapter() {
                private boolean canceled;
                @Override public void onAnimationCancel(Animator animator) { canceled = true; }
                @Override public void onAnimationEnd(Animator animator) { if (!canceled) hide(); }
            });
        }
        animation.start();
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
        unit = Math.min(dp(1), Math.min(getMeasuredWidth(), getMeasuredHeight()) / 290f);
        int target = MeasureSpec.makeMeasureSpec(Math.round(50 * unit), MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(target, target);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        radius = 105f * unit;
        float reach = 144f * unit;
        centerX = Math.max(8 * unit, Math.min(getWidth() - 8 * unit, requestedX));
        centerY = Math.max(8 * unit, Math.min(getHeight() - 8 * unit, requestedY));
        directionX = centerX < reach ? -1 : 1;
        directionY = centerY < reach ? -1 : 1;
        float xInset = 137 * unit;
        centerX = Math.max(xInset, Math.min(getWidth() - xInset, centerX));
        float above = (directionY > 0 ? 137 : selection ? 28 : 108) * unit;
        float below = (directionY < 0 ? 137 : selection ? 28 : 108) * unit;
        centerY = Math.max(above, Math.min(getHeight() - below, centerY));
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
        }
        placeItems();
    }

    private void placeItems() {
        if (radius == 0f) return;
        float[] angles = selection ? SELECTION : TOOLS;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            boolean hub = !selection && i == TOOLS.length - 1;
            float stagger = hub ? 0f : i * .022f;
            float amount = Math.max(0f, Math.min(1f, (progress - stagger) / (1f - stagger)));
            float distance = hub ? 0f : radius * (.62f + .38f * amount);
            double angle = Math.toRadians(angles[i] - (1f - amount) * 10f);
            child.setTranslationX(centerX + directionX * distance * (float) Math.cos(angle) - child.getWidth() / 2f);
            child.setTranslationY(centerY + directionY * distance * (float) Math.sin(angle) - child.getHeight() / 2f);
            child.setAlpha(Math.min(1f, amount * 1.8f) * (child.isEnabled() ? 1f : .36f));
            child.setScaleX(.76f + .24f * amount);
            child.setScaleY(.76f + .24f * amount);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (progress <= 0f) return;
        canvas.save();
        canvas.translate(centerX, centerY);
        canvas.scale(directionX, directionY);
        float distance = radius * (.62f + .38f * progress);
        arc.set(-distance, -distance, distance, distance);
        if (selection) band(canvas, -165, 117);
        else {
            band(canvas, -178, 90);
            band(canvas, 9, 35);
        }
        canvas.restore();
    }

    private void band(Canvas canvas, float start, float sweep) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(55f * unit);
        paint.setColor(0x22243137);
        paint.setAlpha(Math.round(27f * progress));
        canvas.save();
        canvas.translate(0, 2f * unit);
        canvas.drawArc(arc, start, sweep, false, paint);
        canvas.restore();
        paint.setStrokeWidth(54f * unit);
        paint.setColor(0xFFFFFEFA);
        paint.setAlpha(Math.round(250f * progress));
        canvas.drawArc(arc, start, sweep, false, paint);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (closing) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float x = event.getX(), y = event.getY();
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getAlpha() > .08f && x >= child.getX() && x <= child.getX() + child.getWidth()
                        && y >= child.getY() && y <= child.getY() + child.getHeight()) {
                    if (!child.isEnabled()) return true;
                    return super.dispatchTouchEvent(event);
                }
            }
            dismiss(true);
            return event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER;
        }
        return super.dispatchTouchEvent(event);
    }

    private final class Item extends View {
        private final String label;
        private final int id, color;
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);

        Item(String label, int id, int color) {
            super(FanMenu.this.getContext());
            this.label = label;
            this.id = id;
            this.color = color;
            setContentDescription(label);
            setFocusable(true);
            setClickable(true);
            setDefaultFocusHighlightEnabled(false);
            GradientDrawable rippleMask = new GradientDrawable();
            rippleMask.setShape(GradientDrawable.OVAL);
            rippleMask.setColor(0xFFFFFFFF);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x21167B70), null, rippleMask));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            canvas.scale(unit, unit);
            ink.setStyle(Paint.Style.FILL);
            if (id == 0) {
                if (isSelected()) {
                    ink.setColor(color);
                    ink.setStyle(Paint.Style.STROKE);
                    ink.setStrokeWidth(1.6f);
                    canvas.drawCircle(0, 0, 19, ink);
                    ink.setStyle(Paint.Style.FILL);
                }
                ink.setColor(color);
                canvas.drawCircle(0, 0, 13.5f, ink);
            } else if (id == SELECT) {
                ink.setColor(0xFFF1F7F4);
                canvas.drawCircle(0, 0, 22, ink);
                ink.setStyle(Paint.Style.STROKE);
                ink.setColor(0xFFD2E4DB);
                ink.setStrokeWidth(1);
                canvas.drawCircle(0, 0, 22, ink);
                glyph(canvas, 0, TEAL);
            } else {
                int tint = id == CLEAR || id == DELETE ? RED : INK;
                glyph(canvas, -8, tint);
                ink.setColor(tint);
                ink.setStyle(Paint.Style.FILL);
                ink.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                ink.setTextSize(11);
                ink.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(label, 0, 15, ink);
            }
            canvas.restore();
        }

        private void glyph(Canvas canvas, float y, int color) {
            canvas.save();
            canvas.translate(0, y);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(1.65f);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            ink.setColor(color);
            switch (id) {
                case SIZE:
                    canvas.drawLine(-8, -4, 8, -4, ink);
                    canvas.drawLine(-8, 4, 8, 4, ink);
                    canvas.drawLine(-3, -7, -3, -1, ink);
                    canvas.drawLine(3, 1, 3, 7, ink);
                    break;
                case CLEAR:
                    canvas.drawRect(-7, -6, 7, 6, ink);
                    canvas.drawLine(-4, 2, 4, -2, ink);
                    break;
                case COPY:
                    canvas.drawRoundRect(-3, -4, 7, 7, 1.5f, 1.5f, ink);
                    canvas.drawLines(new float[]{-6, 4, -7, 4, -7, 4, -7, -7, -7, -7, 3, -7}, ink);
                    break;
                case PASTE:
                    canvas.drawRoundRect(-6, -6, 6, 7, 1.5f, 1.5f, ink);
                    canvas.drawRoundRect(-3, -8, 3, -4, 1.2f, 1.2f, ink);
                    canvas.drawLine(-2.5f, 1, 2.5f, 1, ink);
                    break;
                case DELETE:
                    canvas.drawLine(-7, -5, 7, -5, ink);
                    canvas.drawLine(-3, -8, 3, -8, ink);
                    canvas.drawRoundRect(-5, -5, 5, 7, 1f, 1f, ink);
                    break;
                case CANCEL:
                    canvas.drawLine(-5.5f, -5.5f, 5.5f, 5.5f, ink);
                    canvas.drawLine(-5.5f, 5.5f, 5.5f, -5.5f, ink);
                    break;
                case SELECT:
                    canvas.drawLines(new float[]{-8,-3,-8,-8,-8,-8,-3,-8,3,-8,8,-8,8,-8,8,-3,
                            8,3,8,8,8,8,3,8,-3,8,-8,8,-8,8,-8,3}, ink);
                    break;
                default:
            }
            canvas.restore();
        }
    }
}
