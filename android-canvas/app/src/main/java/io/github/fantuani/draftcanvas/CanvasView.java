package io.github.fantuani.draftcanvas;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import java.util.*;

public final class CanvasView extends View {
    public static final int PEN=0,SELECT=1,ERASE=2;
    static final double MIN_GRID_SCALE = .70;
    static final int PAPER_COLOR = 0xfffafaf7;
    static final float GRID_STEP_DP = 32f;
    static final float GRID_DOT_DP = 1.6f;
    static final int MAX_PEN_PRESSURE_THRESHOLD = 200;
    static final float DEFAULT_PEN_SENSITIVITY = 1f;
    private static final float MAX_RAW_PEN_PRESSURE = 4095f;
    private static final float RESIZE_HANDLE_DP = 20f;
    private static final float PHOTO_RESIZE_HIT_RADIUS_DP = 24f;
    private static final long SELECTION_MOTION_MS = 150, PASTE_IMAGE_FADE_MS = 135;
    private static final int PASTE_INITIAL_ALPHA = 92;
    public final Ink ink;
    static final int DEFAULT_PEN_COLOR = 0xFF243137;
    public int mode=PEN,color=DEFAULT_PEN_COLOR;public float width,eraserSize,eraserSensitivity=2;
    private float penPressureThreshold;
    float penSensitivity = DEFAULT_PEN_SENSITIVITY;
    public Runnable changed=()->{};
    private boolean fingerTap;
    private boolean navigationBlocked;
    private float fingerDownX, fingerDownY;
    private long lastTapTime = -1;
    private float lastTapX, lastTapY;
    private ValueAnimator zoomAnimator;
    private boolean canReturnFromDoubleTapZoom;
    private double beforeDoubleTapX, beforeDoubleTapY;
    private boolean zoomSettling;
    private ZoomPreview zoomPreview;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF photoBounds = new RectF();
    private final QuestionInk questionInk = new QuestionInk();
    private final RectF compositedInkBounds = new RectF();
    private final RectF liveInkBounds = new RectF();
    private final Rect mixedInkClip = new Rect();
    private final RectF mixedStrokeBounds = new RectF();
    private final InkBounds cachedInkBounds = new InkBounds(), movedInkBounds = new InkBounds();
    private long cachedInkRevision = -1;
    private final DashPathEffect lassoDashes = new DashPathEffect(new float[]{dp(5), dp(4)}, 0);
    private final Runnable redrawQuestions = this::postInvalidateOnAnimation;
    private final RectF offerBounds = new RectF();
    private QuestionImage questionOffer;
    private Bitmap questionOfferBitmap;
    private Runnable acceptDraggedOffer;
    private boolean offerAcceptedByDrag;
    private float offerAlpha, offerScale, offerLift;
    private long offerImportId;
    QuestionAssets questionAssets;
    private long selectedQuestionId;
    private ValueAnimator selectionAnimator;
    private float selectionProgress = 1;
    private boolean pasteEntrance;
    private int pasteImageAlpha = 255;
    private float photoMenuX, photoMenuY;
    private PhotoGesture photoGesture;

    private static final class PhotoGesture {
        final QuestionImage original;
        final float startX, startY;
        final boolean resize, offer;
        QuestionImage preview;

        PhotoGesture(QuestionImage image, float x, float y, boolean resize, boolean offer) {
            original = image;
            startX = x;
            startY = y;
            this.resize = resize;
            this.offer = offer;
        }
    }
    private static final class InkBounds {
        double left, top, right, bottom;
        boolean hasColoredInk;

        void reset() {
            left = top = Double.POSITIVE_INFINITY;
            right = bottom = Double.NEGATIVE_INFINITY;
            hasColoredInk = false;
        }

        void include(Ink.Stroke stroke, double x, double y, double scale) {
            if (stroke.color != DEFAULT_PEN_COLOR) {
                hasColoredInk = true;
                return;
            }
            left = Math.min(left, x + (stroke.left - stroke.x) * scale);
            top = Math.min(top, y + (stroke.top - stroke.y) * scale);
            right = Math.max(right, x + (stroke.right - stroke.x) * scale);
            bottom = Math.max(bottom, y + (stroke.bottom - stroke.y) * scale);
        }

        boolean hasInk() { return left <= right; }
    }

    private static final class SectionKey {
        final double a,b;SectionKey(double a,double b){this.a=a;this.b=b;}
        @Override public boolean equals(Object other){return other instanceof SectionKey&&((SectionKey)other).a==a&&((SectionKey)other).b==b;}
        @Override public int hashCode(){return Double.hashCode(a)*31+Double.hashCode(b);}
    }
    private static final class RenderState {
        final Map<SectionKey,Path> partial=new HashMap<>(),previous;
        RenderState(RenderState previous){this.previous=previous==null?Collections.emptyMap():previous.partial;}
    }
    private float[] gridPoints;private int gridCount,gridWidth,gridHeight;
    private double gridX=Double.NaN,gridY,gridScale;
    boolean testAsync;private AsyncInkTiles raster;
    private final InkTiles tiles=Build.VERSION.SDK_INT>=29?new InkTiles():null;
    private int penId=-1;private boolean erasing,dragging,marquee,nearPen;
    private boolean temporaryEraser;
    private boolean selectionContact, resizeContact, hoverKnown;
    private double selectionScale = 1;
    private int ignoredPenId = -1;
    private Lasso selectionBox;
    private final Lasso.Trace lassoTrace = new Lasso.Trace();
    private final Path lassoPath = new Path();
    private float hoverX, hoverY;
    private float lastX,lastY,lastPressure;
    private int lastTool;
    private boolean cursorKnown,erasePoint;private float cursorRadius;
    private double eraseX,eraseY,eraseRadius;
    private double startX,startY,dragX,dragY;
    private final LiveStroke liveStroke = new LiveStroke();
    private double strokeX,strokeY;
    private int strokeColor;private float strokeWidth,strokeSensitivity;
    private boolean fingers;private int fingerCount;private float prevX,prevY,prevDistance;
    private double gestureX,gestureY,gestureScale;
    private final Viewport.Pinch pinch = new Viewport.Pinch();
    public CanvasView(Context c,Ink document){super(c);ink=document;width=dp(2.3f);eraserSize=dp(24);setBackgroundColor(PAPER_COLOR);setFocusable(true);setContentDescription("草稿画布：手写笔书写，手指移动，双指缩放");if(Build.VERSION.SDK_INT>=26)setDefaultFocusHighlightEnabled(false);}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    private double wx(float x){return ink.view.worldX(x,getWidth());}private double wy(float y){return ink.view.worldY(y,getHeight());}
    public void tool(int value){cancelPen();discardSelection();mode=value;refreshOverlay();}
    public int displayMode(){return temporaryEraser?ERASE:mode;}
    public void temporaryEraser(boolean held){
        if(temporaryEraser==held)return;
        int active=penId;float x=lastX,y=lastY,p=lastPressure;int tool=lastTool;
        if (marquee || selectionContact) cancelPen();
        else if (active != -1) finishPen();
        temporaryEraser=held;
        if (!held || selectionArmed()) mode = PEN;
        discardSelection();
        if(active!=-1)resumePen(active,x,y,p,tool);refreshOverlay();
    }
    PointF menuAnchor() {
        if (hoverKnown) return new PointF(hoverX, hoverY);
        if (cursorKnown) return new PointF(lastX, lastY);
        return new PointF(getWidth() / 2f, getHeight() / 2f);
    }

    PointF selectionMenuAnchor() {
        if (selectedQuestion() != null) return new PointF(photoMenuX, photoMenuY);
        if (selectionBox == null || selectionBox.contains(wx(lastX), wy(lastY))) {
            return new PointF(lastX, lastY);
        }
        return new PointF(screenX((selectionBox.left + selectionBox.right) / 2),
                screenY((selectionBox.top + selectionBox.bottom) / 2));
    }

    void finishContactForMenu() {
        if (penId == -1) return;
        ignoredPenId = penId;
        if (marquee || selectionContact) {
            cancelPen();
            postInvalidateOnAnimation();
        } else finishPen();
    }

    void settleForSave() {
        finishSelectionMotion();
        cancelPhotoGesture();
        stopZoomAnimation();
        lastTapTime = -1;
        finishContactForMenu();
    }

    void armSelection() {
        if (selectionArmed()) return;
        finishContactForMenu();
        selectQuestion(null);
        tool(SELECT);
    }

    boolean selectionArmed() { return mode == SELECT; }

    boolean hasSelectionBox() { return selectionBox != null; }

    void dismissSelectionOutside(float x, float y) {
        QuestionImage image = selectedQuestion();
        if (image != null && !image.contains(wx(x), wy(y))) selectQuestion(null);
        if (selectionBox != null && !selectionBox.contains(wx(x), wy(y)) && !hitsResizeHandle(x, y)) {
            clearSelection();
        }
    }

    private boolean hitsResizeHandle(float x, float y) {
        float hit = dp(RESIZE_HANDLE_DP / 2 + 4);
        return !ink.selected.isEmpty() && Math.abs(x - screenX(selectionBox.right)) <= hit
                && Math.abs(y - screenY(selectionBox.bottom)) <= hit;
    }

    void clearSelection() {
        if (marquee || selectionContact) {
            ignoredPenId = penId;
            cancelPen();
        }
        if (selectionArmed()) mode = PEN;
        discardSelection();
        postInvalidateOnAnimation();
    }

    private void discardSelection() {
        finishSelectionMotion();
        selectionBox = null;
        ink.selected.clear();
    }

    void pasteSelection() {
        boolean fromPhoto = selectedQuestion() != null;
        if (fromPhoto ? !ink.canPasteQuestions() : !ink.canPaste()) return;
        ink.paste(getWidth(), getHeight());
        showPastedSelection(fromPhoto);
    }

    void pasteAt(double worldX, double worldY) {
        if (!ink.canPaste()) return;
        ink.pasteAt(worldX, worldY);
        showPastedSelection(ink.canPasteQuestions());
    }

    private void showPastedSelection(boolean preferPhoto) {
        if (preferPhoto && ink.selected.size() == 1) {
            selectQuestion(ink.question(ink.selected.iterator().next()), true);
        } else {
            selectQuestion(null);
            double[] bounds = ink.selectionBounds();
            selectionBox = Lasso.rectangle(bounds[0], bounds[1], bounds[2], bounds[3]);
            startSelectionMotion(true);
        }
        refresh();
    }

    public void setPenColor(int next){
        if(color==next)return;int active=penId;float x=lastX,y=lastY,p=lastPressure;int tool=lastTool;
        boolean split=active!=-1&&!erasing;if(split)finishPen();color=next;
        if(split)resumePen(active,x,y,p,tool);refreshOverlay();
    }
    private void resumePen(int id,float x,float y,float pressure,int tool){
        MotionEvent.PointerProperties prop=new MotionEvent.PointerProperties();prop.id=id;prop.toolType=tool;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.x=x;coords.y=y;coords.pressure=pressure;
        long time=android.os.SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,1,new MotionEvent.PointerProperties[]{prop},new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);beginPen(e,0);e.recycle();
    }
    void invalidateErasedArea(float left,float top,float right,float bottom){if(tiles!=null)tiles.invalidate(left-2,top-2,right+2,bottom+2);}
    public void setEraserSize(float size){eraserSize=size;cursorRadius=size/2;postInvalidateOnAnimation();}
    void setPenPressureThreshold(int level) {
        penPressureThreshold = Math.max(0, Math.min(MAX_PEN_PRESSURE_THRESHOLD, level)) / MAX_RAW_PEN_PRESSURE;
    }
    public void undo() { applyHistory(false); }
    void redo() { applyHistory(true); }

    private void applyHistory(boolean redo) {
        cancelPhotoGesture();
        finishContactForMenu();
        clearSelection();
        if (redo) ink.redo();
        else ink.undo();
        refresh();
    }
    void questionAdded() {
        if (raster != null) raster.noteRevision(ink);
        refreshOverlay();
    }

    private void refreshOverlay(){postInvalidateOnAnimation();changed.run();}
    public void refresh(){if(tiles!=null)tiles.invalidateAll();if(raster!=null)raster.modelChanged(ink);postInvalidateOnAnimation();changed.run();}
    @Override protected void onDraw(android.graphics.Canvas c){
        super.onDraw(c);drawGrid(c);
        drawQuestions(c, !ink.strokes.isEmpty() || !liveStroke.isEmpty());
        boolean invertInk = !questionInk.isEmpty() && findInvertedInkBounds();
        if (invertInk && hasColoredInk()) {
            drawMixedInk(c);
        } else {
            int inkLayer = invertInk ? c.saveLayer(compositedInkBounds, null) : -1;
            drawCanvasInk(c, invertInk);
            if (invertInk) finishInvertedInkLayer(c, inkLayer);
        }
        questionInk.clear();
        drawLasso(c);
        drawQuestionSelection(c);
        if(displayMode()==ERASE&&cursorKnown&&(nearPen||penId!=-1||temporaryEraser)){
            float radius=cursorRadius>0?cursorRadius:eraserSize/2;
            paint.setStyle(Paint.Style.FILL);paint.setColor(0x10167B70);c.drawCircle(lastX,lastY,radius,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2.5f));paint.setColor(0xE6FFFFFF);c.drawCircle(lastX,lastY,radius,paint);
            paint.setStrokeWidth(dp(1));paint.setColor(0xD9167B70);c.drawCircle(lastX,lastY,radius,paint);
        }
    }

    private void drawCanvasInk(Canvas c, boolean isolated) {
        if (Build.VERSION.SDK_INT >= 29 && c.isHardwareAccelerated() && zoomPreview != null) {
            raster.drawZoom(c, cameraAt(ink.view.x, ink.view.y, ink.view.scale), () -> zoomPreview.draw(c));
        } else if(c.isHardwareAccelerated()&&(isAttachedToWindow()||testAsync)&&!dragging){
            if(raster==null)raster=new AsyncInkTiles(this::postInvalidateOnAnimation);
            raster.draw(c,ink,cameraAt(ink.view.x,ink.view.y,ink.view.scale), fingers || zoomAnimator != null, zoomSettling, isolated, this::drawInk);
            if (zoomAnimator == null && raster.ready()) zoomSettling = false;
        }else if(Build.VERSION.SDK_INT>=29&&c.isHardwareAccelerated()&&!fingers&&!dragging&&dragX==0&&dragY==0)
            tiles.draw(c,getWidth(),getHeight(),ink.view.x,ink.view.y,ink.view.scale,this::drawInk);
        else drawInk(c,0,0,getWidth(),getHeight());
        drawLiveInk(c);
    }

    private void drawLiveInk(Canvas c) {
        if (liveStroke.isEmpty()) return;
        c.save();
        c.translate(screenX(strokeX), screenY(strokeY));
        c.scale((float) ink.view.scale, (float) ink.view.scale);
        setStrokePaint(strokeColor, strokeWidth);
        liveStroke.draw(c, paint, strokeWidth, ink.view.scale, strokeSensitivity > 0);
        c.restore();
    }

    private boolean hasColoredInk() {
        InkBounds bounds = dragging ? movedInkBounds : cachedInkBounds;
        return bounds.hasColoredInk || (!liveStroke.isEmpty() && strokeColor != DEFAULT_PEN_COLOR);
    }

    private void finishInvertedInkLayer(Canvas c, int layer) {
        questionInk.draw(c, compositedInkBounds);
        c.restoreToCount(layer);
    }

    private void drawMixedInk(Canvas c) {
        // Keep the cached ink outside photos. Inside, preserve stroke order when mixing colors.
        c.save();
        questionInk.clipOutside(c);
        drawCanvasInk(c, false);
        c.restore();

        c.save();
        if (questionInk.clipInside(c) && c.getClipBounds(mixedInkClip)) {
            double left = wx(mixedInkClip.left), top = wy(mixedInkClip.top);
            double right = wx(mixedInkClip.right), bottom = wy(mixedInkClip.bottom);
            int blackLayer = -1;
            for (Ink.Stroke stroke : ink.strokes) {
                boolean transform = dragging && ink.selected.contains(stroke.id);
                double scale = transform ? selectionScale : 1;
                double x = transform ? selectionBox.left + (stroke.x - selectionBox.left) * scale + dragX : stroke.x;
                double y = transform ? selectionBox.top + (stroke.y - selectionBox.top) * scale + dragY : stroke.y;
                mixedStrokeBounds.set(screenX(x + (stroke.left - stroke.x) * scale),
                        screenY(y + (stroke.top - stroke.y) * scale),
                        screenX(x + (stroke.right - stroke.x) * scale),
                        screenY(y + (stroke.bottom - stroke.y) * scale));
                if (!questionInk.intersects(mixedStrokeBounds)) continue;
                blackLayer = orderedInkLayer(c, blackLayer, stroke.color);
                drawInkStroke(c, stroke, left, top, right, bottom);
            }
            if (!liveStroke.isEmpty()) {
                blackLayer = orderedInkLayer(c, blackLayer, strokeColor);
                drawLiveInk(c);
            }
            if (blackLayer != -1) finishInvertedInkLayer(c, blackLayer);
        }
        c.restore();
    }

    private int orderedInkLayer(Canvas c, int layer, int color) {
        if (color == DEFAULT_PEN_COLOR) return layer != -1 ? layer : c.saveLayer(compositedInkBounds, null);
        if (layer != -1) finishInvertedInkLayer(c, layer);
        return -1;
    }

    private boolean findInvertedInkBounds() {
        InkBounds bounds = dragging ? movedInkBounds : cachedInkBounds;
        if (dragging || cachedInkRevision != ink.revision) {
            bounds.reset();
            for (Ink.Stroke stroke : ink.strokes) {
                if (dragging && ink.selected.contains(stroke.id)) {
                    double x = selectionBox.left + (stroke.x - selectionBox.left) * selectionScale + dragX;
                    double y = selectionBox.top + (stroke.y - selectionBox.top) * selectionScale + dragY;
                    bounds.include(stroke, x, y, selectionScale);
                } else {
                    bounds.include(stroke, stroke.x, stroke.y, 1);
                }
            }
            if (!dragging) cachedInkRevision = ink.revision;
        }
        compositedInkBounds.setEmpty();
        if (bounds.hasInk()) {
            compositedInkBounds.set(screenX(bounds.left), screenY(bounds.top),
                    screenX(bounds.right), screenY(bounds.bottom));
        }
        if (!liveStroke.isEmpty() && strokeColor == DEFAULT_PEN_COLOR) {
            liveStroke.bounds(liveInkBounds, strokeWidth);
            compositedInkBounds.union(screenX(strokeX + liveInkBounds.left), screenY(strokeY + liveInkBounds.top),
                    screenX(strokeX + liveInkBounds.right), screenY(strokeY + liveInkBounds.bottom));
        }
        if (compositedInkBounds.isEmpty()) return false;
        // Include the antialiased fringe, but don't allocate beyond the visible ink.
        compositedInkBounds.inset(-2, -2);
        return compositedInkBounds.intersect(0, 0, getWidth(), getHeight())
                && questionInk.intersects(compositedInkBounds);
    }

    private void drawQuestions(Canvas canvas, boolean collectForInk) {
        if (questionAssets == null) return;
        boolean resizingPhoto = photoGesture != null && !photoGesture.offer
                && photoGesture.resize && photoGesture.preview != null;
        boolean resizingSelection = dragging && resizeContact;
        for (QuestionImage question : ink.questions) {
            if (questionOffer != null && question.id == offerImportId) continue;
            if (photoGesture != null && photoGesture.preview != null && photoGesture.original.id == question.id) {
                question = photoGesture.preview;
            }
            questionDisplayBounds(question);
            int margin = QuestionAssets.PREFETCH_PX;
            if (!photoBounds.intersects(-margin, -margin, getWidth() + margin, getHeight() + margin)) continue;
            boolean onScreen = photoBounds.intersects(0, 0, getWidth(), getHeight());
            double edge = Math.max(question.width, question.height);
            // Keep the starting detail when shrinking and refine in the background when growing.
            if (resizingPhoto && question.id == photoGesture.original.id) {
                edge = Math.max(edge, Math.max(photoGesture.original.width, photoGesture.original.height));
            } else if (resizingSelection && ink.selected.contains(question.id)) {
                edge *= Math.max(1, selectionScale);
            }
            int displayEdge = Math.max(1, (int) Math.ceil(edge * ink.view.scale));
            boolean fading = pasteImageAlpha < 255 && (question.id == selectedQuestionId || ink.selected.contains(question.id));
            if (fading) photoPaint.setAlpha(pasteImageAlpha);
            questionAssets.draw(question.asset, canvas, photoBounds, displayEdge, getWidth(), getHeight(),
                    photoPaint, collectForInk ? questionInk : null, onScreen, redrawQuestions);
            if (fading) photoPaint.setAlpha(255);
        }
        if (questionOffer != null) {
            updateOfferBounds(questionOffer);
            photoPaint.setAlpha(Math.max(0, Math.min(255, Math.round(255 * offerAlpha))));
            canvas.drawBitmap(questionOfferBitmap, null, offerBounds, photoPaint);
            if (collectForInk && offerImportId != 0) questionInk.add(questionOfferBitmap, offerBounds, photoPaint.getAlpha());
            photoPaint.setAlpha(255);
        }
    }

    private void questionDisplayBounds(QuestionImage question) {
        if (!dragging || !ink.selected.contains(question.id)) {
            questionBounds(question, photoBounds);
            return;
        }
        double x = selectionBox.left + (question.x - selectionBox.left) * selectionScale + dragX;
        double y = selectionBox.top + (question.y - selectionBox.top) * selectionScale + dragY;
        photoBounds.set(screenX(x), screenY(y), screenX(x + question.width * selectionScale),
                screenY(y + question.height * selectionScale));
    }

    void questionBounds(QuestionImage image, RectF bounds) {
        bounds.set(screenX(image.x), screenY(image.y), screenX(image.right()), screenY(image.bottom()));
    }

    private void updateOfferBounds(QuestionImage image) {
        questionBounds(image, offerBounds);
        offerBounds.inset(offerBounds.width() * (1 - offerScale) / 2,
                offerBounds.height() * (1 - offerScale) / 2);
        offerBounds.offset(0, offerLift);
    }

    void showQuestionOffer(QuestionImage image, Bitmap bitmap, long importedId, Runnable onDrag) {
        if (photoGesture != null && photoGesture.offer) blockNavigation();
        questionOffer = image;
        questionOfferBitmap = bitmap;
        offerImportId = importedId;
        acceptDraggedOffer = onDrag;
        offerAcceptedByDrag = false;
        postInvalidateOnAnimation();
    }

    boolean resizingQuestionOffer() {
        return photoGesture != null && photoGesture.offer && photoGesture.preview != null;
    }

    QuestionImage questionOfferPlacement() { return questionOffer; }

    void styleQuestionOffer(float alpha, float scale, float lift) {
        offerAlpha = alpha;
        offerScale = scale;
        offerLift = lift;
        postInvalidateOnAnimation();
    }

    boolean canImportQuestion() {
        return getWidth() > 0 && getHeight() > 0 && penId == -1 && !fingers
                && photoGesture == null && !ink.hasOpenEdit();
    }

    QuestionImage selectedQuestion() { return ink.question(selectedQuestionId); }

    void selectQuestion(QuestionImage image) {
        selectQuestion(image, false);
    }

    private void selectQuestion(QuestionImage image, boolean pasted) {
        long previous = selectedQuestionId;
        if (image != null) clearSelection();
        selectedQuestionId = image == null ? 0 : image.id;
        if (image != null && previous != selectedQuestionId) startSelectionMotion(pasted);
        else finishSelectionMotion();
        postInvalidateOnAnimation();
    }

    private void startSelectionMotion(boolean pasted) {
        finishSelectionMotion();
        if (!isAttachedToWindow() || !isShown() || !ValueAnimator.areAnimatorsEnabled()) return;
        if (selectionAnimator == null) {
            selectionAnimator = ValueAnimator.ofFloat(0, 1);
            selectionAnimator.setDuration(SELECTION_MOTION_MS);
            selectionAnimator.setInterpolator(new DecelerateInterpolator());
            selectionAnimator.addUpdateListener(frame -> {
                selectionProgress = (float) frame.getAnimatedValue();
                if (pasteEntrance) {
                    float fraction = Math.min(1, (float) frame.getCurrentPlayTime() / PASTE_IMAGE_FADE_MS);
                    float fade = frame.getInterpolator().getInterpolation(fraction);
                    pasteImageAlpha = Math.round(PASTE_INITIAL_ALPHA + (255 - PASTE_INITIAL_ALPHA) * fade);
                    if (selectionProgress >= 1) pasteImageAlpha = 255;
                }
                postInvalidateOnAnimation();
            });
        }
        pasteEntrance = pasted;
        pasteImageAlpha = pasted ? PASTE_INITIAL_ALPHA : 255;
        selectionProgress = 0;
        selectionAnimator.start();
    }

    private void finishSelectionMotion() {
        if (selectionAnimator != null && selectionAnimator.isStarted()) selectionAnimator.cancel();
        if (selectionProgress < 1) postInvalidateOnAnimation();
        selectionProgress = 1;
        pasteImageAlpha = 255;
        pasteEntrance = false;
    }

    // Apply the inset to the outline only. Hit testing always uses the final world bounds.
    private float selectionInset(float span) {
        if (selectionProgress >= 1) return 0;
        float distance = Math.min(dp(pasteEntrance ? 3 : 2), Math.max(0, span) * .10f);
        return -distance * (1 - selectionProgress);
    }

    private int selectionAlpha(int alpha) {
        return Math.round(alpha * (.35f + .65f * selectionProgress));
    }

    private void drawQuestionSelection(Canvas c) {
        QuestionImage image = photoGesture != null && !photoGesture.offer && photoGesture.preview != null
                ? photoGesture.preview : selectedQuestion();
        if (image != null) {
            float left = screenX(image.x), top = screenY(image.y);
            float right = screenX(image.right()), bottom = screenY(image.bottom());
            float insetX = selectionInset(right - left), insetY = selectionInset(bottom - top);
            paint.setColor(0xFF167B70);
            paint.setAlpha(selectionAlpha(255));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.2f));
            c.drawRect(left + insetX, top + insetY, right - insetX, bottom - insetY, paint);
            drawSelectionHandle(c, right - insetX, bottom - insetY);
        }
        if ((acceptDraggedOffer != null || offerAcceptedByDrag) && offerAlpha > 0) {
            updateOfferBounds(questionOffer);
            drawResizeHandle(c, offerBounds.right, offerBounds.bottom, 255);
        }
    }

    private QuestionImage questionAt(float x, float y) {
        double worldX = wx(x), worldY = wy(y);
        for (int i = ink.questions.size() - 1; i >= 0; i--) {
            QuestionImage image = ink.questions.get(i);
            if (image.contains(worldX, worldY)) return image;
        }
        return null;
    }

    private boolean beginPhotoGesture(float x, float y) {
        if (acceptDraggedOffer != null && offerAlpha > 0) {
            updateOfferBounds(questionOffer);
            if (hitsPhotoResizeHandle(x, y, offerBounds.right, offerBounds.bottom)) {
                photoGesture = new PhotoGesture(questionOffer, x, y, true, true);
                lastTapTime = -1;
                fingerTap = false;
                return true;
            }
        }
        QuestionImage image = selectedQuestion();
        if (image == null) return false;
        boolean resize = hitsPhotoResizeHandle(x, y, screenX(image.right()), screenY(image.bottom()));
        if (!resize && questionAt(x, y) != image) return false;
        photoGesture = new PhotoGesture(image, x, y, resize, false);
        lastTapTime = -1;
        fingerTap = false;
        return true;
    }

    private boolean hitsPhotoResizeHandle(float x, float y, float right, float bottom) {
        return Math.hypot(x - right, y - bottom) <= dp(PHOTO_RESIZE_HIT_RADIUS_DP);
    }

    private void updatePhotoGesture(float x, float y) {
        PhotoGesture gesture = photoGesture;
        double dx = x - gesture.startX, dy = y - gesture.startY;
        if (gesture.preview == null && Math.hypot(dx, dy) < dp(4)) return;
        dx /= ink.view.scale;
        dy /= ink.view.scale;
        QuestionImage original = gesture.original;
        if (gesture.resize) {
            double w = original.width, h = original.height;
            double factor = Math.max(.05, Math.min(100, 1 + (dx * w + dy * h) / (w * w + h * h)));
            gesture.preview = original.scaled(original.x, original.y, factor);
        } else {
            gesture.preview = original.offset(original.id, dx, dy);
        }
        if (gesture.offer) {
            questionOffer = gesture.preview;
            Runnable accept = acceptDraggedOffer;
            acceptDraggedOffer = null;
            if (accept != null) {
                offerAcceptedByDrag = true;
                accept.run();
            }
        }
        postInvalidateOnAnimation();
    }

    private void cancelPhotoGesture() {
        if (photoGesture == null) return;
        photoGesture = null;
        postInvalidateOnAnimation();
    }

    void locateQuestion(QuestionImage question) {
        if (!canImportQuestion()) return;
        canReturnFromDoubleTapZoom = false;
        stopZoomAnimation();
        double scale = Math.min(ink.view.scale, Math.min(getWidth() * .82 / question.width,
                getHeight() * .80 / question.height));
        double x = question.x + question.width / 2, y = question.y + question.height / 2;
        animateCamera(x, y, scale, false);
    }

    private void drawLasso(Canvas canvas) {
        if (!marquee && selectionBox == null) return;
        double[] points = marquee ? lassoTrace.points : selectionBox.points;
        int count = marquee ? lassoTrace.size : points.length;
        if (count < 4) return;
        lassoPath.rewind();
        lassoPath.setFillType(Path.FillType.EVEN_ODD);
        double scale = dragging ? selectionScale : 1;
        double anchorX = dragging ? selectionBox.left : 0;
        double anchorY = dragging ? selectionBox.top : 0;
        double offsetX = dragging ? dragX : 0;
        double offsetY = dragging ? dragY : 0;
        float insetX = 0, insetY = 0;
        int outlineTransform = -1;
        if (!marquee && selectionProgress < 1) {
            float left = screenX(selectionBox.left), top = screenY(selectionBox.top);
            float w = screenX(selectionBox.right) - left, h = screenY(selectionBox.bottom) - top;
            insetX = selectionInset(w);
            insetY = selectionInset(h);
            outlineTransform = canvas.save();
            canvas.scale(w > 0 ? 1 - 2 * insetX / w : 1, h > 0 ? 1 - 2 * insetY / h : 1,
                    left + w / 2, top + h / 2);
        }
        for (int i = 0; i < count; i += 2) {
            float x = screenX(anchorX + (points[i] - anchorX) * scale + offsetX);
            float y = screenY(anchorY + (points[i + 1] - anchorY) * scale + offsetY);
            if (i == 0) lassoPath.moveTo(x, y);
            else lassoPath.lineTo(x, y);
        }
        if (!marquee) {
            lassoPath.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x11167b70);
            paint.setAlpha(selectionAlpha(0x11));
            canvas.drawPath(lassoPath, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xff167b70);
        if (!marquee) paint.setAlpha(selectionAlpha(255));
        paint.setStrokeWidth(dp(1.2f));
        paint.setPathEffect(lassoDashes);
        canvas.drawPath(lassoPath, paint);
        paint.setPathEffect(null);
        if (outlineTransform != -1) canvas.restoreToCount(outlineTransform);
        if (!marquee && !ink.selected.isEmpty()) {
            drawSelectionHandle(canvas, screenX(anchorX + (selectionBox.right - anchorX) * scale + offsetX) - insetX,
                    screenY(anchorY + (selectionBox.bottom - anchorY) * scale + offsetY) - insetY);
        }
    }

    private void drawSelectionHandle(Canvas c, float x, float y) {
        if (selectionProgress >= 1) {
            drawResizeHandle(c, x, y, 255);
            return;
        }
        float scale = 1 - .13f * (1 - selectionProgress) * (1 - 3 * selectionProgress);
        int save = c.save();
        c.scale(scale, scale, x, y);
        drawResizeHandle(c, x, y, selectionAlpha(255));
        c.restoreToCount(save);
    }

    private void drawResizeHandle(Canvas c, float x, float y, int alpha) {
        float half = dp(RESIZE_HANDLE_DP / 2), tip = dp(4.5f), wing = dp(3);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff167b70);
        paint.setAlpha(alpha);
        c.drawRoundRect(x - half, y - half, x + half, y + half, dp(5), dp(5), paint);
        paint.setColor(Color.WHITE);
        paint.setAlpha(alpha);
        paint.setStrokeWidth(dp(1.4f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x - tip, y - tip, x + tip, y + tip, paint);
        c.drawLine(x - tip, y - tip, x - tip + wing, y - tip, paint);
        c.drawLine(x - tip, y - tip, x - tip, y - tip + wing, paint);
        c.drawLine(x + tip, y + tip, x + tip - wing, y + tip, paint);
        c.drawLine(x + tip, y + tip, x + tip, y + tip - wing, paint);
    }

    private void drawInk(Canvas c, int left, int top, int right, int bottom) {
        double l = wx(left), t = wy(top), r = wx(right), b = wy(bottom);
        for (Ink.Stroke stroke : ink.strokes) drawInkStroke(c, stroke, l, t, r, b);
    }

    private void drawInkStroke(Canvas c, Ink.Stroke stroke, double l, double t, double r, double b) {
        boolean transform = dragging && ink.selected.contains(stroke.id);
        double factor = transform ? selectionScale : 1;
        double x = stroke.x, y = stroke.y;
        if (transform) {
            x = selectionBox.left + (x - selectionBox.left) * factor + dragX;
            y = selectionBox.top + (y - selectionBox.top) * factor + dragY;
        }
        double localLeft = (l - x) / factor, localTop = (t - y) / factor;
        double localRight = (r - x) / factor, localBottom = (b - y) / factor;
        if (!stroke.intersects(stroke.x + localLeft, stroke.y + localTop,
                stroke.x + localRight, stroke.y + localBottom)) return;
        c.save();
        c.translate(screenX(x), screenY(y));
        float scale = (float) (ink.view.scale * factor);
        c.scale(scale, scale);
        drawStrokeShape(c, stroke, localLeft, localTop, localRight, localBottom);
        c.restore();
    }
    @Override protected void onDetachedFromWindow() {
        finishSelectionMotion();
        stopZoomAnimation();
        closeRaster();
        if (tiles != null) tiles.clear();
        super.onDetachedFromWindow();
    }
    private float screenX(double x){return (float)((x-ink.view.x)*ink.view.scale+getWidth()/2.0);}private float screenY(double y){return (float)((y-ink.view.y)*ink.view.scale+getHeight()/2.0);}
    private void drawGrid(android.graphics.Canvas c){
        if (ink.view.scale < MIN_GRID_SCALE) return;
        float diameter = (float) (dp(GRID_DOT_DP) * ink.view.scale);
        if(gridPoints==null||gridX!=ink.view.x||gridY!=ink.view.y||gridScale!=ink.view.scale||gridWidth!=getWidth()||gridHeight!=getHeight()){
            gridX=ink.view.x;gridY=ink.view.y;gridScale=ink.view.scale;gridWidth=getWidth();gridHeight=getHeight();
            double step = dp(GRID_STEP_DP), pixels = step * ink.view.scale;
            float radius = diameter / 2;
            double originX = getWidth() / 2.0 - (ink.view.x % step) * ink.view.scale;
            double originY = getHeight() / 2.0 - (ink.view.y % step) * ink.view.scale;
            float firstX = (float) (originX - Math.floor((originX + radius) / pixels) * pixels);
            float firstY = (float) (originY - Math.floor((originY + radius) / pixels) * pixels);
            int capacity = ((int)Math.ceil(getWidth()/pixels)+2)*((int)Math.ceil(getHeight()/pixels)+2)*2;
            if (gridPoints == null || gridPoints.length < capacity) gridPoints = new float[capacity];
            gridCount = 0;
            for (float x = firstX; x < getWidth() + radius; x += pixels) {
                for (float y = firstY; y < getHeight() + radius; y += pixels) {
                    gridPoints[gridCount++] = x;
                    gridPoints[gridCount++] = y;
                }
            }
        }
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(diameter);paint.setColor(0xFFB9C4BC);c.drawPoints(gridPoints,0,gridCount,paint);
    }
    private Path sectionPath(Ink.Stroke stroke,StrokeData data,double start,double end){
        Path path=new Path();
        if (stroke.variableWidth) {
            PressurePath.append(path, data, start, end, stroke.width / 2);
            return path;
        }
        path.moveTo(data.coordinate(start,0),data.coordinate(start,1));
        for(int i=(int)Math.floor(start)+1;i<end;i++)path.lineTo(data.points[i*3],data.points[i*3+1]);
        path.lineTo(data.coordinate(end,0),data.coordinate(end,1));return path;
    }
    private void drawSections(android.graphics.Canvas c,Ink.Stroke s,StrokeData data,StrokeData.Node node,double l,double t,double r,double b){
        float padding=s.maxWidth/2;
        if(node.right+padding<l||node.left-padding>r||node.bottom+padding<t||node.top-padding>b||!StrokeData.visible(s.sections,node.start,node.end))return;
        int index=s.sections==null?0:StrokeData.first(s.sections,node.start);
        boolean full=s.sections==null||index<s.sections.length&&s.sections[index]<=node.start&&s.sections[index+1]>=node.end;
        if(full&&((node.left>=l&&node.right<=r&&node.top>=t&&node.bottom<=b)||node.end-node.start<=32)){
            if(node.renderPath==null)node.renderPath=sectionPath(s,data,node.start,node.end);c.drawPath((Path)node.renderPath,paint);return;
        }
        if(node.a!=null){drawSections(c,s,data,node.a,l,t,r,b);drawSections(c,s,data,node.b,l,t,r,b);return;}
        if(s.sections==null){if(node.renderPath==null)node.renderPath=sectionPath(s,data,node.start,node.end);c.drawPath((Path)node.renderPath,paint);return;}
        RenderState state=s.renderState instanceof RenderState?(RenderState)s.renderState:new RenderState(s.previousRender instanceof RenderState?(RenderState)s.previousRender:null);s.renderState=state;s.previousRender=null;
        for(int i=index;i<s.sections.length&&s.sections[i]<node.end;i+=2){double a=Math.max(node.start,s.sections[i]),z=Math.min(node.end,s.sections[i+1]);if(z-a<1e-9)continue;SectionKey key=new SectionKey(a,z);Path p=state.partial.get(key);if(p==null){p=state.previous.get(key);if(p==null)p=sectionPath(s,data,a,z);state.partial.put(key,p);}c.drawPath(p,paint);}
    }
    private void drawStrokeShape(Canvas c, Ink.Stroke s, double l, double t, double r, double b) {
        setStrokePaint(s.color, s.width);
        if (s.variableWidth || s.points.length == 3) paint.setStyle(Paint.Style.FILL);
        if(s.points.length==3){c.drawCircle(s.points[0],s.points[1],s.maxWidth/2,paint);}
        else {StrokeData data=s.data();drawSections(c,s,data,data.root,l,t,r,b);}
    }
    private void setStrokePaint(int color, float width) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }
    private boolean beginPen(MotionEvent e,int i){
        lastX=e.getX(i);lastY=e.getY(i);lastPressure=e.getPressure(i);lastTool=e.getToolType(i);cursorKnown=true;
        penId=e.getPointerId(i);fingers=false;startX=wx(e.getX(i));startY=wy(e.getY(i));dragX=dragY=0;
        erasing=temporaryEraser||mode==ERASE||e.getToolType(i)==MotionEvent.TOOL_TYPE_ERASER;
        if(erasing){ink.begin();erasePoint=false;erase(startX,startY,e.getPressure(i));return false;}
        if (selectionBox != null) {
            resizeContact = hitsResizeHandle(lastX, lastY);
            selectionContact = resizeContact || selectionBox.contains(startX, startY);
            if (!selectionContact) {
                discardSelection();
                ignoredPenId = penId;
                resetPenContact();
            }
            return false;
        }
        if (mode == SELECT) {
            marquee = true;
            lassoTrace.clear();
            lassoTrace.add(startX, startY, 0);
            return false;
        }
        liveStroke.clear();
        addPenSample(startX, startY, lastPressure);
        return true;
    }
    private boolean movePen(MotionEvent e,int i){
        lastX=e.getX(i);lastY=e.getY(i);lastPressure=e.getPressure(i);
        double x=wx(e.getX(i)),y=wy(e.getY(i));
        if(erasing){
            boolean straight=erasePoint;float maxPressure=e.getPressure(i);double tolerance=dp(.4f)/ink.view.scale;
            for(int h=0;h<e.getHistorySize();h++){maxPressure=Math.max(maxPressure,e.getHistoricalPressure(i,h));if(!erasePoint||Eraser.distanceSquared(wx(e.getHistoricalX(i,h)),wy(e.getHistoricalY(i,h)),eraseX,eraseY,x,y)>tolerance*tolerance)straight=false;}
            if(straight)erase(x,y,maxPressure);else {for(int h=0;h<e.getHistorySize();h++)erase(wx(e.getHistoricalX(i,h)),wy(e.getHistoricalY(i,h)),e.getHistoricalPressure(i,h));erase(x,y,e.getPressure(i));}
            cursorRadius=(float)(eraserSize/2*Eraser.pressureScale(e.getPressure(i),eraserSensitivity));
        }
        else if (selectionContact) {
            double dx = x - startX, dy = y - startY;
            if (!dragging && Math.hypot(dx, dy) * ink.view.scale < dp(6)) return false;
            dragging = true;
            if (resizeContact) {
                double w = selectionBox.right - selectionBox.left, h = selectionBox.bottom - selectionBox.top;
                selectionScale = Math.max(.05, Math.min(100, 1 + (dx * w + dy * h) / (w * w + h * h)));
            } else {
                dragX = dx;
                dragY = dy;
            }
        }
        else if (marquee) {
            double spacing = dp(.65f) / ink.view.scale;
            boolean changed = false;
            for (int h = 0; h < e.getHistorySize(); h++) {
                changed |= lassoTrace.add(wx(e.getHistoricalX(i, h)), wy(e.getHistoricalY(i, h)), spacing);
            }
            return lassoTrace.add(x, y, spacing) || changed;
        }
        else {
            boolean changed = false;
            for (int h = 0; h < e.getHistorySize(); h++) {
                changed |= addPenSample(wx(e.getHistoricalX(i, h)), wy(e.getHistoricalY(i, h)),
                        e.getHistoricalPressure(i, h));
            }
            changed |= addPenSample(x, y, lastPressure);
            return changed;
        }
        return true;
    }
    private boolean addPenSample(double x, double y, float pressure) {
        if (pressure < penPressureThreshold) return commitLiveStroke();
        if (liveStroke.isEmpty()) {
            strokeX = x;
            strokeY = y;
            strokeColor = color;
            strokeWidth = (float) (width / ink.view.scale);
            strokeSensitivity = penSensitivity;
        }
        float multiplier = StrokeGeometry.pressureWidth(pressure, strokeSensitivity);
        return liveStroke.add((float) (x - strokeX), (float) (y - strokeY), ink.view.scale,
                strokeWidth, multiplier, strokeSensitivity > 0);
    }

    private boolean commitLiveStroke() {
        if (liveStroke.isEmpty()) return false;
        Ink.Stroke stroke = new Ink.Stroke(ink.nextId(), strokeColor, strokeWidth, strokeX, strokeY,
                liveStroke.copyPoints(), false, null, strokeSensitivity > 0);
        liveStroke.clear();
        ink.add(stroke);
        if (raster != null) {
            raster.stroke(ink, stroke);
            raster.noteRevision(ink);
        }
        invalidateErasedArea(screenX(stroke.left), screenY(stroke.top), screenX(stroke.right), screenY(stroke.bottom));
        changed.run();
        return true;
    }

    private void erase(double x,double y,float pressure){
        cursorRadius=(float)(eraserSize/2*Eraser.pressureScale(pressure,eraserSensitivity));double radius=cursorRadius/ink.view.scale;
        double ax=erasePoint?eraseX:x,ay=erasePoint?eraseY:y,r=erasePoint?Math.max(radius,eraseRadius):radius;
        eraseWorld(ax,ay,x,y,r);
        erasePoint=true;eraseX=x;eraseY=y;eraseRadius=radius;
    }
    void eraseWorld(double ax,double ay,double bx,double by,double radius){
        if(!ink.erase(ax,ay,bx,by,radius))return;double dirty=radius+ink.erasePadding;
        if(tiles!=null)tiles.invalidate(screenX(Math.min(ax,bx)-dirty)-2,screenY(Math.min(ay,by)-dirty)-2,screenX(Math.max(ax,bx)+dirty)+2,screenY(Math.max(ay,by)+dirty)+2);
        if(raster!=null)raster.erase(ink,ax,ay,bx,by,radius,ink.erasePadding);
    }
    void closeRaster(){if(raster!=null){raster.close();raster=null;}}
    boolean rasterReady(){return raster!=null&&raster.ready();}
    private void finishPen(){
        boolean erased=erasing;
        boolean tapSelection = selectionContact && !resizeContact && !dragging
                && selectionBox.contains(wx(lastX), wy(lastY));
        boolean movedSelection = dragging;
        if (erasing) ink.commit();
        else if (dragging) {
            double[] from = raster == null ? null : ink.selectionBounds();
            long before = ink.revision;
            if (resizeContact) ink.resizeSelection(selectionBox.left, selectionBox.top, selectionScale);
            else ink.move(dragX, dragY);
            if (from != null && ink.revision != before) {
                raster.selectionTransformed(ink, from, ink.selectionBounds());
            }
            selectionBox = selectionBox.transform(selectionScale, dragX, dragY);
        } else if (marquee) {
            double minimum = dp(4) / ink.view.scale;
            Lasso completed = lassoTrace.finish(minimum * minimum);
            if (completed != null) {
                selectionBox = completed;
                ink.select(selectionBox);
                mode = PEN;
                if (!ink.selected.isEmpty()) startSelectionMotion(false);
            }
        }
        else commitLiveStroke();
        resetPenContact();
        if (erased) {
            if (raster != null) raster.noteRevision(ink);
            postInvalidateOnAnimation();
            changed.run();
        } else if (movedSelection) refresh();
        else postInvalidateOnAnimation();
        if (tapSelection) performClick();
    }

    private void resetPenContact() {
        liveStroke.clear();
        erasePoint = false;
        penId = -1;
        selectionContact = resizeContact = dragging = marquee = erasing = false;
        selectionScale = 1;
        lassoTrace.clear();
        dragX = dragY = 0;
    }

    private void cancelPen() {
        if (erasing) {
            ink.cancel();
            if (tiles != null) tiles.invalidateAll();
            if (raster != null) raster.modelChanged(ink);
        }
        resetPenContact();
    }

    private void blockNavigation() {
        cancelPhotoGesture();
        navigationBlocked = true;
        lastTapTime = -1;
        fingerTap = false;
        if (!fingers) return;
        fingers = false;
        if (ink.view.x == gestureX && ink.view.y == gestureY && ink.view.scale == gestureScale) return;
        ink.view.x = gestureX;
        ink.view.y = gestureY;
        ink.view.scale = gestureScale;
        refresh();
    }

    @Override public boolean onHoverEvent(MotionEvent e){
        if(e.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS||e.getToolType(0)==MotionEvent.TOOL_TYPE_ERASER){
            nearPen=e.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT;
            if (nearPen) {
                blockNavigation();
                hoverKnown = true;
                hoverX = lastX = e.getX();
                hoverY = lastY = e.getY();
                cursorKnown = true;
            }
            cursorRadius=eraserSize/2;
            if (displayMode() == ERASE) postInvalidateOnAnimation();
            return true;
        }return super.onHoverEvent(e);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) {
            nearPen = false;
            finishSelectionMotion();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        getParent().requestDisallowInterceptTouchEvent(true);int action=e.getActionMasked(),index=e.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_CANCEL) finishSelectionMotion();
        if (action == MotionEvent.ACTION_CANCEL) {
            blockNavigation();
            ignoredPenId = -1;
            cancelPen();
            refresh();
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            boolean finger = e.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER;
            navigationBlocked = finger && (nearPen || PalmContact.isWide(e));
            if (!navigationBlocked) stopZoomAnimation();
            fingerTap = finger && !navigationBlocked;
            if (!fingerTap) lastTapTime = -1;
            fingerDownX = e.getX(index);
            fingerDownY = e.getY(index);
            if (fingerTap && beginPhotoGesture(fingerDownX, fingerDownY)) return true;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN
                || Math.hypot(e.getX(index) - fingerDownX, e.getY(index) - fingerDownY) > dp(8)) {
            fingerTap = false;
            lastTapTime = -1;
        }
        if (ignoredPenId != -1) {
            if (action == MotionEvent.ACTION_DOWN || e.findPointerIndex(ignoredPenId) < 0) ignoredPenId = -1;
            else {
                if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                        && e.getPointerId(index) == ignoredPenId) ignoredPenId = -1;
                return true;
            }
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int tool = e.getToolType(index);
            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
                blockNavigation();
                stopZoomAnimation();
                QuestionImage image = selectedQuestion();
                if (image != null && !image.contains(wx(e.getX(index)), wy(e.getY(index)))) selectQuestion(null);
                if (beginPen(e, index)) {
                    requestUnbufferedDispatch(e);
                    if (!liveStroke.isEmpty()) invalidate();
                } else postInvalidateOnAnimation();
                return true;
            }
        }
        if(penId!=-1){int i=e.findPointerIndex(penId);if(i<0){cancelPen();return true;}if((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)&&e.getPointerId(index)==penId){
                if(Build.VERSION.SDK_INT>=33&&(e.getFlags()&MotionEvent.FLAG_CANCELED)!=0){cancelPen();refresh();}else{movePen(e,i);finishPen();}
            } else if (action == MotionEvent.ACTION_MOVE && movePen(e, i)) {
                if (!liveStroke.isEmpty()) invalidate();
                else postInvalidateOnAnimation();
            }
            return true;
        }
        if (Build.VERSION.SDK_INT >= 33 && action == MotionEvent.ACTION_POINTER_UP
                && (e.getFlags() & MotionEvent.FLAG_CANCELED) != 0) blockNavigation();
        // Reject the rest of this contact sequence, even if the reported touch size shrinks.
        if (navigationBlocked || nearPen || (action != MotionEvent.ACTION_DOWN && PalmContact.isWide(e))) {
            if (!navigationBlocked) blockNavigation();
            return true;
        }
        if (photoGesture != null) {
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                cancelPhotoGesture();
            } else {
                if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                    updatePhotoGesture(e.getX(), e.getY());
                }
                if (action == MotionEvent.ACTION_UP) {
                    QuestionImage preview = photoGesture.preview;
                    boolean offer = photoGesture.offer;
                    boolean imageTap = preview == null && !photoGesture.resize
                            && e.getEventTime() - e.getDownTime() < ViewConfiguration.getLongPressTimeout()
                            && questionAt(e.getX(), e.getY()) == photoGesture.original;
                    cancelPhotoGesture();
                    if (!offer && preview != null) {
                        ink.replaceQuestion(preview);
                        if (raster != null) raster.noteRevision(ink);
                        refreshOverlay();
                    } else if (imageTap) {
                        photoMenuX = e.getX();
                        photoMenuY = e.getY();
                        performClick();
                    }
                }
                return true;
            }
        }
        if(action==MotionEvent.ACTION_UP){
            if (fingers) postInvalidateOnAnimation();
            fingers=false;
            if (fingerTap && e.getEventTime() - e.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
                handleFingerTap(e);
            } else lastTapTime = -1;
            fingerTap=false;
            return true;
        }
        int count=0;float cx=0,cy=0,distance=0,firstX=0,firstY=0;
        for(int i=0;i<e.getPointerCount();i++){if(action==MotionEvent.ACTION_POINTER_UP&&i==index)continue;if(e.getToolType(i)!=MotionEvent.TOOL_TYPE_FINGER)continue;if(count==0){firstX=e.getX(i);firstY=e.getY(i);}else if(count==1)distance=(float)Math.hypot(e.getX(i)-firstX,e.getY(i)-firstY);cx+=e.getX(i);cy+=e.getY(i);count++;if(count==2)break;}
        if(count==0){fingers=false;return true;}cx/=count;cy/=count;
        if (!fingers || count != fingerCount) {
            if (!fingers) {
                gestureX = ink.view.x;
                gestureY = ink.view.y;
                gestureScale = ink.view.scale;
            }
            if (count == 2) pinch.start(ink.view.scale);
            fingers = true;
            fingerCount = count;
            prevX = cx;
            prevY = cy;
            prevDistance = distance;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && !fingerTap) {
            if (cx != prevX || cy != prevY || (count == 2 && distance != prevDistance)) {
                canReturnFromDoubleTapZoom = false;
            }
            double anchorX = wx(prevX), anchorY = wy(prevY);
            if (count == 2 && prevDistance > 1 && distance > 1) {
                ink.view.scale = pinch.update((double) distance / prevDistance);
            }
            ink.view.x = anchorX - (cx - getWidth() / 2.0) / ink.view.scale;
            ink.view.y = anchorY - (cy - getHeight() / 2.0) / ink.view.scale;
            prevX = cx;
            prevY = cy;
            prevDistance = distance;
            refreshOverlay();
        }
        return true;
    }
    @Override public boolean performClick(){return (hasSelectionBox() || selectedQuestion() != null) && super.performClick();}

    private void handleFingerTap(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        QuestionImage image = questionAt(x, y);
        if (image != null) {
            lastTapTime = -1;
            selectQuestion(image);
            return;
        }
        if (selectedQuestion() != null) selectQuestion(null);
        long upTime = event.getEventTime();
        long interval = event.getDownTime() - lastTapTime;
        if (lastTapTime >= 0 && interval >= 0 && interval <= ViewConfiguration.getDoubleTapTimeout()
                && Math.hypot(x - lastTapX, y - lastTapY) <= dp(32)) {
            lastTapTime = -1;
            if (canReturnFromDoubleTapZoom) {
                canReturnFromDoubleTapZoom = false;
                animateCamera(beforeDoubleTapX, beforeDoubleTapY, 1, false);
            } else if (ink.view.scale == 1) {
                beforeDoubleTapX = ink.view.x;
                beforeDoubleTapY = ink.view.y;
                canReturnFromDoubleTapZoom = true;
                animateZoomTo(x, y, 2);
            } else {
                animateZoomTo(x, y, 1);
            }
        } else {
            lastTapTime = upTime;
            lastTapX = x;
            lastTapY = y;
        }
    }

    private void animateZoomTo(float x, float y, double targetScale) {
        animateCamera(wx(x), wy(y), targetScale, false);
    }

    private void animateCamera(double targetX, double targetY, double targetScale, boolean overview) {
        double startingX = ink.view.x, startingY = ink.view.y, startingScale = ink.view.scale;
        if (startingX == targetX && startingY == targetY && startingScale == targetScale) return;
        if (!ValueAnimator.areAnimatorsEnabled() || getWidth() == 0 || getHeight() == 0) {
            ink.view.x = targetX;
            ink.view.y = targetY;
            ink.view.scale = targetScale;
            zoomSettling = true;
            refreshOverlay();
            return;
        }
        double logRatio = Math.log(startingScale / targetScale);
        double offsetX = (targetX - startingX) * startingScale;
        double offsetY = (targetY - startingY) * startingScale;
        ValueAnimator animation = ValueAnimator.ofFloat(0f, 1f);
        animation.setDuration(240);
        animation.setInterpolator(new DecelerateInterpolator(1.65f));
        animation.addUpdateListener(frame -> {
            double t = (float) frame.getAnimatedValue();
            if (t == 0) return;
            double remaining = 1 - t;
            ink.view.scale = targetScale * Math.exp(remaining * logRatio);
            if (t == 1) {
                ink.view.x = targetX;
                ink.view.y = targetY;
            } else if (overview) {
                // Move the old center out (or the destination in) along a straight screen-space path.
                double weight = targetScale < startingScale
                        ? t * (targetScale / ink.view.scale)
                        : 1 - remaining * (startingScale / ink.view.scale);
                ink.view.x = (1 - weight) * startingX + weight * targetX;
                ink.view.y = (1 - weight) * startingY + weight * targetY;
            } else {
                ink.view.x = targetX - remaining * offsetX / ink.view.scale;
                ink.view.y = targetY - remaining * offsetY / ink.view.scale;
            }
            invalidate();
        });
        animation.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;

            @Override public void onAnimationCancel(Animator ended) { canceled = true; }

            @Override public void onAnimationEnd(Animator ended) {
                if (raster != null && zoomPreview != null) raster.endZoom(!canceled);
                if (overview) zoomSettling = !canceled;
                zoomAnimator = null;
                zoomPreview = null;
                changed.run();
                postInvalidateOnAnimation();
            }
        });
        // An overview can span the whole document. Draw only the visible tiles while travelling.
        zoomSettling = !overview;
        if (!overview && Build.VERSION.SDK_INT >= 29) {
            if (raster == null) raster = new AsyncInkTiles(this::postInvalidateOnAnimation);
            raster.prepareZoom(ink, cameraAt(targetX, targetY, targetScale));
            zoomPreview = new ZoomPreview(targetX, targetY, Math.min(startingScale, targetScale), offsetX, offsetY);
        }
        zoomAnimator = animation;
        animation.start();
    }

    private AsyncInkTiles.Camera cameraAt(double x, double y, double scale) {
        return new AsyncInkTiles.Camera(getWidth(), getHeight(), x, y, scale,
                getResources().getDisplayMetrics().density);
    }

    @android.annotation.TargetApi(29)
    private final class ZoomPreview {
        private final RenderNode inkLayer = new RenderNode("double-tap-ink");
        private final double centerX, centerY, scale, halfWidth, halfHeight;
        private final int width, height;
        private long recordedRevision = -1;

        ZoomPreview(double centerX, double centerY, double scale, double offsetX, double offsetY) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.scale = scale;
            width = (int) Math.ceil(getWidth() + 2 * Math.abs(offsetX));
            height = (int) Math.ceil(getHeight() + 2 * Math.abs(offsetY));
            halfWidth = width / (2 * scale);
            halfHeight = height / (2 * scale);
            inkLayer.setPosition(0, 0, width, height);
        }

        void draw(Canvas canvas) {
            if (recordedRevision != ink.revision) record();
            canvas.save();
            canvas.translate(screenX(centerX), screenY(centerY));
            float ratio = (float) (ink.view.scale / scale);
            canvas.scale(ratio, ratio);
            canvas.translate(-width / 2f, -height / 2f);
            canvas.drawRenderNode(inkLayer);
            canvas.restore();
        }

        private void record() {
            double left = centerX - halfWidth, top = centerY - halfHeight;
            double right = centerX + halfWidth, bottom = centerY + halfHeight;
            Canvas layer = inkLayer.beginRecording();
            try {
                if (raster == null || !raster.recordZoomPreview(layer, ink, left, top, scale, width, height)) {
                    for (Ink.Stroke stroke : ink.strokes) {
                        if (!stroke.intersects(left, top, right, bottom)) continue;
                        layer.save();
                        layer.translate((float) ((stroke.x - left) * scale), (float) ((stroke.y - top) * scale));
                        layer.scale((float) scale, (float) scale);
                        drawStrokeShape(layer, stroke, left - stroke.x, top - stroke.y, right - stroke.x, bottom - stroke.y);
                        layer.restore();
                    }
                }
            } finally {
                inkLayer.endRecording();
            }
            recordedRevision = ink.revision;
        }
    }

    private void stopZoomAnimation() {
        if (zoomAnimator != null) zoomAnimator.cancel();
    }

    void resetZoom() {
        canReturnFromDoubleTapZoom = false;
        stopZoomAnimation();
        if (ink.view.scale == 1) return;
        ink.view.scale = 1;
        refresh();
    }

    void overview() {
        canReturnFromDoubleTapZoom = false;
        stopZoomAnimation();
        double left = Double.POSITIVE_INFINITY, top = left, right = -left, bottom = -left;
        for (Ink.Stroke stroke : ink.strokes) {
            left = Math.min(left, stroke.left);
            top = Math.min(top, stroke.top);
            right = Math.max(right, stroke.right);
            bottom = Math.max(bottom, stroke.bottom);
        }
        for (QuestionImage image : ink.questions) {
            left = Math.min(left, image.x);
            top = Math.min(top, image.y);
            right = Math.max(right, image.right());
            bottom = Math.max(bottom, image.bottom());
        }
        if (!ink.hasContent()) {
            animateCamera(0, 0, 1, true);
            return;
        }
        double fit = Math.min(getWidth() * .75 / Math.max(50, right - left),
                getHeight() * .60 / Math.max(50, bottom - top));
        animateCamera(left * .5 + right * .5, top * .5 + bottom * .5,
                Math.max(.0001, Math.min(8, fit)), true);
    }
}
