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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

final class FanMenu extends ViewGroup {
    static final int SIZE = 1, CLEAR = 2, COPY = 3, PASTE = 4, DELETE = 5, CANCEL = 6, SELECT = 7, OVERVIEW = 8, SYNC = 9;
    private static final int INK = 0xFF243137, TEAL = 0xFF167B70, RED = 0xFFAA5148;
    private static final float[] TOOLS = {-178, -149, -120, -91, 151, 114, 66, 29, -2};
    private static final float[] SELECTION = {-165, -126, -87, -48};
    private static final PathInterpolator OPEN = new PathInterpolator(.18f, .76f, .22f, 1f);
    private static final PathInterpolator CLOSE = new PathInterpolator(.36f, 0f, .72f, .54f);

    interface Actions {
        void color(int color);
        void command(int id);
        void penOutside(float x, float y);
    }

    private final Actions actions;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final RectF confirmationBounds = new RectF();
    private LinearLayout confirmation;
    private float morph, sourceX, sourceY, confirmX, confirmY;
    private ValueAnimator animation;
    private boolean selection, shown, closing;
    private float requestedX, requestedY, centerX, centerY;
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
        swatch("墨黑", CanvasView.DEFAULT_PEN_COLOR, activeColor);
        swatch("朱红", 0xFFCF4941, activeColor);
        swatch("靛蓝", 0xFF3169CA, activeColor);
        swatch("青绿", TEAL, activeColor);
        command("框选", SELECT, true);
        command("粗细", SIZE, true);
        command("清空", CLEAR, hasInk);
        command("总览", OVERVIEW, true);
        command("同步", SYNC, true);
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
        confirmation = null;
        morph = 0;
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
            if (id == CLEAR) {
                showClearConfirmation(tile);
                return;
            }
            dismiss(true);
            actions.command(id);
        });
        addView(tile);
    }

    private void showClearConfirmation(Item source) {
        if (confirmation != null || closing) return;
        if (animation != null) animation.cancel();
        sourceX = source.getX() + source.getWidth() / 2f;
        sourceY = source.getY() + source.getHeight() / 2f;

        confirmation = new LinearLayout(getContext());
        confirmation.setOrientation(LinearLayout.VERTICAL);
        confirmation.setGravity(Gravity.CENTER);
        confirmation.setAlpha(0);
        confirmation.setPadding(Math.round(dp(14)), 0, Math.round(dp(14)), 0);
        TextView title = new TextView(getContext());
        title.setText("清空草稿？");
        title.setTextColor(INK);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        confirmation.addView(title, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout buttons = new LinearLayout(getContext());
        buttons.setGravity(Gravity.CENTER);
        confirmation.addView(buttons, new LinearLayout.LayoutParams(-1, Math.round(dp(52))));
        buttons.addView(confirmationButton("取消", INK, () -> dismiss(true)),
                new LinearLayout.LayoutParams(0, -1, 1));
        buttons.addView(confirmationButton("清空", RED, () -> {
            dismiss(true);
            actions.command(CLEAR);
        }), new LinearLayout.LayoutParams(0, -1, 1));
        addView(confirmation);
        for (int i = 0; i < getChildCount() - 1; i++) {
            View item = getChildAt(i);
            item.setEnabled(false);
            item.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        float openingProgress = progress;
        animation = ValueAnimator.ofFloat(0f, 1f);
        animation.setDuration(230);
        animation.setInterpolator(OPEN);
        animation.addUpdateListener(value -> {
            morph = (float) value.getAnimatedValue();
            progress = blend(openingProgress, 1f, morph);
            placeItems();
            placeConfirmation();
            invalidate();
        });
        animation.start();
    }

    private TextView confirmationButton(String label, int color, Runnable action) {
        TextView button = new TextView(getContext());
        button.setText(label);
        button.setTextColor(color);
        button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(12));
        mask.setColor(0xFFFFFFFF);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x21167B70), null, mask));
        button.setOnClickListener(view -> action.run());
        return button;
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
            placeConfirmation();
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
        unit = Math.min(dp(1), Math.min(getMeasuredWidth(), getMeasuredHeight()) / 235f);
        int target = MeasureSpec.makeMeasureSpec(Math.round(43 * unit), MeasureSpec.EXACTLY);
        for (int i = 0; i < itemCount(); i++) getChildAt(i).measure(target, target);
        if (confirmation != null) {
            confirmation.measure(MeasureSpec.makeMeasureSpec(Math.round(218 * unit), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(108 * unit), MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        radius = (selection ? 77f : 86f) * unit;
        float[] angles = selection ? SELECTION : TOOLS;
        float minX = 0, maxX = 0, minY = 0, maxY = 0;
        for (float degrees : angles) {
            // Include the short rotation during the entrance animation in the edge clamp.
            for (float offset : new float[]{0, -10}) {
                double angle = Math.toRadians(degrees + offset);
                float x = radius * (float) Math.cos(angle);
                float y = radius * (float) Math.sin(angle);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        float padding = 26f * unit;
        centerX = Math.max(padding - minX, Math.min(getWidth() - padding - maxX, requestedX));
        centerY = Math.max(padding - minY, Math.min(getHeight() - padding - maxY, requestedY));
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
        }
        placeItems();
        placeConfirmation();
    }

    private int itemCount() { return getChildCount() - (confirmation == null ? 0 : 1); }

    private float blend(float start, float end, float amount) { return start + (end - start) * amount; }

    private void placeConfirmation() {
        if (confirmation == null || confirmation.getWidth() == 0) return;
        float w = confirmation.getWidth(), h = confirmation.getHeight();
        confirmX = Math.max(w / 2 + 8 * unit, Math.min(getWidth() - w / 2 - 8 * unit, centerX));
        confirmY = Math.max(h / 2 + 8 * unit, Math.min(getHeight() - h / 2 - 8 * unit, centerY));
        float x = blend(sourceX, confirmX, morph), y = blend(sourceY, confirmY, morph);
        float fraction = .9f + .1f * progress;
        float halfWidth = blend(22 * unit, w / 2f, morph) * fraction;
        float halfHeight = blend(22 * unit, h / 2f, morph) * fraction;
        confirmationBounds.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
        confirmation.setTranslationX(x - w / 2);
        confirmation.setTranslationY(y - h / 2);
        confirmation.setScaleX(fraction * blend(.72f, 1, morph));
        confirmation.setScaleY(fraction * blend(.72f, 1, morph));
        confirmation.setAlpha(Math.max(0, (morph - .42f) / .58f) * progress);
    }

    private void placeItems() {
        if (radius == 0f) return;
        float[] angles = selection ? SELECTION : TOOLS;
        for (int i = 0; i < itemCount(); i++) {
            View child = getChildAt(i);
            float stagger = i * .018f;
            float amount = Math.max(0f, Math.min(1f, (progress - stagger) / (1f - stagger)));
            float distance = radius * (.62f + .38f * amount);
            double angle = Math.toRadians(angles[i] - (1f - amount) * 10f);
            float x = centerX + distance * (float) Math.cos(angle);
            float y = centerY + distance * (float) Math.sin(angle);
            child.setTranslationX(blend(x, sourceX, morph) - child.getWidth() / 2f);
            child.setTranslationY(blend(y, sourceY, morph) - child.getHeight() / 2f);
            float enabledAlpha = child.isEnabled() || confirmation != null ? 1f : .36f;
            child.setAlpha(Math.min(1f, amount * 1.8f) * enabledAlpha * Math.max(0, 1 - morph * 1.65f));
            float scale = (.76f + .24f * amount) * (1 - .34f * morph);
            child.setScaleX(scale);
            child.setScaleY(scale);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (progress <= 0f) return;
        canvas.save();
        canvas.translate(centerX, centerY);
        float distance = radius * (.62f + .38f * progress);
        arc.set(-distance, -distance, distance, distance);
        if (selection) band(canvas, -165, 117);
        else {
            band(canvas, -178, 87);
            band(canvas, 114, 37);
            band(canvas, -2, 68);
        }
        canvas.restore();
        if (confirmation != null && morph > 0) {
            float corner = blend(22, 18, morph) * unit;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF243137);
            paint.setAlpha(Math.round(26 * progress * morph));
            canvas.save();
            canvas.translate(0, 2 * unit);
            canvas.drawRoundRect(confirmationBounds, corner, corner, paint);
            canvas.restore();
            paint.setColor(0xFFFFFEFA);
            paint.setAlpha(Math.round(255 * progress * Math.min(1, morph * 2)));
            canvas.drawRoundRect(confirmationBounds, corner, corner, paint);
        }
    }

    private void band(Canvas canvas, float start, float sweep) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(45f * unit);
        paint.setColor(0x22243137);
        paint.setAlpha(Math.round(27f * progress * (1 - morph)));
        canvas.save();
        canvas.translate(0, 2f * unit);
        canvas.drawArc(arc, start, sweep, false, paint);
        canvas.restore();
        paint.setStrokeWidth(44f * unit);
        paint.setColor(0xFFFFFEFA);
        paint.setAlpha(Math.round(250f * progress * (1 - morph)));
        canvas.drawArc(arc, start, sweep, false, paint);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (closing) return false;
            float x = event.getX(), y = event.getY();
            if (confirmation != null) {
                if (confirmationBounds.contains(x, y)) {
                    if (morph >= .85f) super.dispatchTouchEvent(event);
                    return true;
                }
            } else {
                for (int i = 0; i < itemCount(); i++) {
                    Item child = (Item) getChildAt(i);
                    if (child.getAlpha() > .08f && child.contains(x - child.getX(), y - child.getY())) {
                        if (child.isEnabled()) super.dispatchTouchEvent(event);
                        return true;
                    }
                }
            }
            dismiss(true);
            if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) actions.penOutside(x, y);
            return true;
        }
        super.dispatchTouchEvent(event);
        // The entire contact belongs to the menu once it took DOWN.
        return true;
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

        boolean contains(float x, float y) {
            float half = getWidth() / 2f;
            float dx = x - half, dy = y - half;
            return dx * dx + dy * dy <= half * half;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !contains(event.getX(), event.getY())) {
                return false;
            }
            return super.onTouchEvent(event);
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
                case OVERVIEW:
                    canvas.drawRoundRect(-8, -6, 8, 6, 1.5f, 1.5f, ink);
                    canvas.drawRect(-3, -2, 3, 2, ink);
                    break;
                case SYNC:
                    canvas.drawLines(new float[]{
                            -7, -4, 7, -4, 3, -8, 7, -4, 3, 0, 7, -4,
                            7, 4, -7, 4, -3, 0, -7, 4, -3, 8, -7, 4
                    }, ink);
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
