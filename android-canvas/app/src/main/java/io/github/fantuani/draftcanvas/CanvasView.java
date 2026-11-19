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
    private static final float RESIZE_HANDLE_DP = 20f;
    public final Ink ink;
    public int mode=PEN,color=0xFF243137;public float width,eraserSize,eraserSensitivity=2;
    public Runnable changed=()->{};
    private boolean fingerTap;
    private boolean navigationBlocked;
    private float fingerDownX, fingerDownY;
    private long lastTapTime = -1;
    private float lastTapX, lastTapY;
    private ValueAnimator zoomAnimator;
    private boolean zoomSettling;
    private ZoomPreview zoomPreview;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
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
    private int strokeColor;private float strokeWidth;
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
        stopZoomAnimation();
        lastTapTime = -1;
        finishContactForMenu();
    }

    void armSelection() {
        if (selectionArmed()) return;
        finishContactForMenu();
        tool(SELECT);
    }

    boolean selectionArmed() { return mode == SELECT; }

    boolean hasSelectionBox() { return selectionBox != null; }

    void dismissSelectionOutside(float x, float y) {
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
        selectionBox = null;
        ink.selected.clear();
    }

    void pasteSelection() {
        if (!ink.canPaste()) return;
        ink.paste(getWidth(), getHeight());
        double[] bounds = ink.selectionBounds();
        selectionBox = Lasso.rectangle(bounds[0], bounds[1], bounds[2], bounds[3]);
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
    public void undo() {
        clearSelection();
        if (penId != -1) finishPen();
        ink.undo();
        refresh();
    }
    private void refreshOverlay(){postInvalidateOnAnimation();changed.run();}
    public void refresh(){if(tiles!=null)tiles.invalidateAll();if(raster!=null)raster.modelChanged(ink);postInvalidateOnAnimation();changed.run();}
    @Override protected void onDraw(android.graphics.Canvas c){
        super.onDraw(c);drawGrid(c);
        if (Build.VERSION.SDK_INT >= 29 && c.isHardwareAccelerated() && zoomPreview != null) {
            raster.drawZoom(c, cameraAt(ink.view.x, ink.view.y, ink.view.scale), () -> zoomPreview.draw(c));
        } else if(c.isHardwareAccelerated()&&(isAttachedToWindow()||testAsync)&&!dragging){
            if(raster==null)raster=new AsyncInkTiles(this::postInvalidateOnAnimation);
            raster.draw(c,ink,cameraAt(ink.view.x,ink.view.y,ink.view.scale), fingers || zoomAnimator != null, zoomSettling, this::drawInk);
            if (zoomAnimator == null && raster.ready()) zoomSettling = false;
        }else if(Build.VERSION.SDK_INT>=29&&c.isHardwareAccelerated()&&!fingers&&!dragging&&dragX==0&&dragY==0)
            tiles.draw(c,getWidth(),getHeight(),ink.view.x,ink.view.y,ink.view.scale,this::drawInk);
        else drawInk(c,0,0,getWidth(),getHeight());
        if (!liveStroke.isEmpty()) {
            c.save();
            c.translate(screenX(strokeX), screenY(strokeY));
            c.scale((float)ink.view.scale, (float)ink.view.scale);
            setStrokePaint(strokeColor, strokeWidth);
            liveStroke.draw(c, paint, strokeWidth);
            c.restore();
        }
        drawLasso(c);
        if(displayMode()==ERASE&&cursorKnown&&(nearPen||penId!=-1||temporaryEraser)){
            float radius=cursorRadius>0?cursorRadius:eraserSize/2;
            paint.setStyle(Paint.Style.FILL);paint.setColor(0x10167B70);c.drawCircle(lastX,lastY,radius,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2.5f));paint.setColor(0xE6FFFFFF);c.drawCircle(lastX,lastY,radius,paint);
            paint.setStrokeWidth(dp(1));paint.setColor(0xD9167B70);c.drawCircle(lastX,lastY,radius,paint);
        }
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
            canvas.drawPath(lassoPath, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xff167b70);
        paint.setStrokeWidth(dp(1.2f));
        paint.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(4)}, 0));
        canvas.drawPath(lassoPath, paint);
        paint.setPathEffect(null);
        if (!marquee && !ink.selected.isEmpty()) {
            drawResizeHandle(canvas, screenX(anchorX + (selectionBox.right - anchorX) * scale + offsetX),
                    screenY(anchorY + (selectionBox.bottom - anchorY) * scale + offsetY));
        }
    }

    private void drawResizeHandle(Canvas c, float x, float y) {
        float half = dp(RESIZE_HANDLE_DP / 2), tip = dp(4.5f), wing = dp(3);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff167b70);
        c.drawRoundRect(x - half, y - half, x + half, y + half, dp(5), dp(5), paint);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(dp(1.4f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x - tip, y - tip, x + tip, y + tip, paint);
        c.drawLine(x - tip, y - tip, x - tip + wing, y - tip, paint);
        c.drawLine(x - tip, y - tip, x - tip, y - tip + wing, paint);
        c.drawLine(x + tip, y + tip, x + tip - wing, y + tip, paint);
        c.drawLine(x + tip, y + tip, x + tip, y + tip - wing, paint);
    }

    private void drawInk(android.graphics.Canvas c,int left,int top,int right,int bottom){
        double l=wx(left),t=wy(top),r=wx(right),b=wy(bottom);
        for (Ink.Stroke stroke : ink.strokes) {
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
                    stroke.x + localRight, stroke.y + localBottom)) continue;
            c.save();
            c.translate(screenX(x), screenY(y));
            float scale = (float) (ink.view.scale * factor);
            c.scale(scale, scale);
            drawStrokeShape(c, stroke, localLeft, localTop, localRight, localBottom);
            c.restore();
        }
    }
    @Override protected void onDetachedFromWindow(){stopZoomAnimation();if(raster!=null){raster.close();raster=null;}if(tiles!=null)tiles.clear();super.onDetachedFromWindow();}
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
    private Path sectionPath(StrokeData data,double start,double end){
        Path path=new Path();path.moveTo(data.coordinate(start,0),data.coordinate(start,1));
        for(int i=(int)Math.floor(start)+1;i<end;i++)path.lineTo(data.points[i*3],data.points[i*3+1]);
        path.lineTo(data.coordinate(end,0),data.coordinate(end,1));return path;
    }
    private void drawSections(android.graphics.Canvas c,Ink.Stroke s,StrokeData data,StrokeData.Node node,double l,double t,double r,double b){
        float padding=s.width/2;
        if(node.right+padding<l||node.left-padding>r||node.bottom+padding<t||node.top-padding>b||!StrokeData.visible(s.sections,node.start,node.end))return;
        int index=s.sections==null?0:StrokeData.first(s.sections,node.start);
        boolean full=s.sections==null||index<s.sections.length&&s.sections[index]<=node.start&&s.sections[index+1]>=node.end;
        if(full&&((node.left>=l&&node.right<=r&&node.top>=t&&node.bottom<=b)||node.end-node.start<=32)){
            if(node.renderPath==null)node.renderPath=sectionPath(data,node.start,node.end);c.drawPath((Path)node.renderPath,paint);return;
        }
        if(node.a!=null){drawSections(c,s,data,node.a,l,t,r,b);drawSections(c,s,data,node.b,l,t,r,b);return;}
        if(s.sections==null){if(node.renderPath==null)node.renderPath=sectionPath(data,node.start,node.end);c.drawPath((Path)node.renderPath,paint);return;}
        RenderState state=s.renderState instanceof RenderState?(RenderState)s.renderState:new RenderState(s.previousRender instanceof RenderState?(RenderState)s.previousRender:null);s.renderState=state;s.previousRender=null;
        for(int i=index;i<s.sections.length&&s.sections[i]<node.end;i+=2){double a=Math.max(node.start,s.sections[i]),z=Math.min(node.end,s.sections[i+1]);if(z-a<1e-9)continue;SectionKey key=new SectionKey(a,z);Path p=state.partial.get(key);if(p==null){p=state.previous.get(key);if(p==null)p=sectionPath(data,a,z);state.partial.put(key,p);}c.drawPath(p,paint);}
    }
    private void drawStrokeShape(Canvas c, Ink.Stroke s, double l, double t, double r, double b) {
        setStrokePaint(s.color, s.width);
        if(s.points.length==3){paint.setStyle(Paint.Style.FILL);c.drawCircle(s.points[0],s.points[1],s.width/2,paint);}
        else {StrokeData data=s.data();drawSections(c,s,data,data.root,l,t,r,b);}
    }
    private void setStrokePaint(int color, float width) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }
    private void beginPen(MotionEvent e,int i){
        lastX=e.getX(i);lastY=e.getY(i);lastPressure=e.getPressure(i);lastTool=e.getToolType(i);cursorKnown=true;
        penId=e.getPointerId(i);fingers=false;startX=wx(e.getX(i));startY=wy(e.getY(i));dragX=dragY=0;
        erasing=temporaryEraser||mode==ERASE||e.getToolType(i)==MotionEvent.TOOL_TYPE_ERASER;
        if(erasing){ink.begin();erasePoint=false;erase(startX,startY,e.getPressure(i));return;}
        if (selectionBox != null) {
            resizeContact = hitsResizeHandle(lastX, lastY);
            selectionContact = resizeContact || selectionBox.contains(startX, startY);
            if (!selectionContact) {
                discardSelection();
                ignoredPenId = penId;
                resetPenContact();
            }
            return;
        }
        if (mode == SELECT) {
            marquee = true;
            lassoTrace.clear();
            lassoTrace.add(startX, startY, 0);
            return;
        }
        liveStroke.clear();
        strokeX = startX;
        strokeY = startY;
        strokeColor = color;
        strokeWidth = (float) (width / ink.view.scale);
        liveStroke.add(0, 0, ink.view.scale, strokeWidth);
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
                changed |= liveStroke.add((float)(wx(e.getHistoricalX(i,h)) - strokeX),
                        (float)(wy(e.getHistoricalY(i,h)) - strokeY), ink.view.scale, strokeWidth);
            }
            changed |= liveStroke.add((float)(x - strokeX), (float)(y - strokeY), ink.view.scale, strokeWidth);
            return changed;
        }
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
        boolean erased=erasing,added=false;
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
            }
        }
        else if (!liveStroke.isEmpty()) {
            Ink.Stroke stroke = new Ink.Stroke(ink.nextId(), strokeColor, strokeWidth, strokeX, strokeY,
                    liveStroke.copyPoints());
            ink.add(stroke);
            if (raster != null) raster.stroke(ink, stroke);
            added = true;
            invalidateErasedArea(screenX(stroke.left), screenY(stroke.top), screenX(stroke.right), screenY(stroke.bottom));
        }
        resetPenContact();
        if (erased || added) {
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
    @Override public boolean onTouchEvent(MotionEvent e){
        getParent().requestDisallowInterceptTouchEvent(true);int action=e.getActionMasked(),index=e.getActionIndex();
        if (action == MotionEvent.ACTION_CANCEL) {
            blockNavigation();
            ignoredPenId = -1;
            cancelPen();
            refresh();
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            boolean finger = e.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER;
            navigationBlocked = finger && PalmContact.isWide(e);
            if (!navigationBlocked) stopZoomAnimation();
            fingerTap = finger && !navigationBlocked;
            if (!fingerTap) lastTapTime = -1;
            fingerDownX = e.getX(index);
            fingerDownY = e.getY(index);
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
                beginPen(e, index);
                if (!liveStroke.isEmpty()) {
                    requestUnbufferedDispatch(e);
                    invalidate();
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
        if (navigationBlocked || (action != MotionEvent.ACTION_DOWN && PalmContact.isWide(e))) {
            if (!navigationBlocked) blockNavigation();
            return true;
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
            double anchorX = wx(prevX), anchorY = wy(prevY);
            if (count == 2 && prevDistance > 1 && distance > 1) {
                ink.view.scale = pinch.update((double) distance / prevDistance);
            }
            ink.view.x = anchorX - (cx - getWidth() / 2.0) / ink.view.scale;
            ink.view.y = anchorY - (cy - getHeight() / 2.0) / ink.view.scale;
            prevX = cx;
            prevY = cy;
            prevDistance = distance;
            refresh();
        }
        return true;
    }
    @Override public boolean performClick(){return hasSelectionBox() && super.performClick();}

    private void handleFingerTap(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        long upTime = event.getEventTime();
        long interval = event.getDownTime() - lastTapTime;
        if (lastTapTime >= 0 && interval >= 0 && interval <= ViewConfiguration.getDoubleTapTimeout()
                && Math.hypot(x - lastTapX, y - lastTapY) <= dp(32)) {
            lastTapTime = -1;
            animateZoomTo(x, y, ink.view.scale == 1 ? 2 : 1);
        } else {
            lastTapTime = upTime;
            lastTapX = x;
            lastTapY = y;
        }
    }

    private void animateZoomTo(float x, float y, double targetScale) {
        double targetX = wx(x), targetY = wy(y);
        double startingScale = ink.view.scale;
        double logRatio = Math.log(startingScale / targetScale);
        double offsetX = x - getWidth() / 2.0, offsetY = y - getHeight() / 2.0;
        ValueAnimator animation = ValueAnimator.ofFloat(0f, 1f);
        animation.setDuration(240);
        animation.setInterpolator(new DecelerateInterpolator(1.65f));
        animation.addUpdateListener(frame -> {
            double remaining = 1 - (float) frame.getAnimatedValue();
            ink.view.scale = targetScale * Math.exp(remaining * logRatio);
            ink.view.x = targetX - remaining * offsetX / ink.view.scale;
            ink.view.y = targetY - remaining * offsetY / ink.view.scale;
            invalidate();
        });
        animation.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;

            @Override public void onAnimationCancel(Animator ended) { canceled = true; }

            @Override public void onAnimationEnd(Animator ended) {
                if (raster != null) raster.endZoom(!canceled);
                zoomAnimator = null;
                zoomPreview = null;
                changed.run();
                postInvalidateOnAnimation();
            }
        });
        zoomSettling = true;
        if (Build.VERSION.SDK_INT >= 29) {
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
        stopZoomAnimation();
        if (ink.view.scale == 1) return;
        ink.view.scale = 1;
        refresh();
    }

    void overview() {
        stopZoomAnimation();
        double left = Double.POSITIVE_INFINITY, top = left, right = -left, bottom = -left;
        for (Ink.Stroke stroke : ink.strokes) {
            left = Math.min(left, stroke.left);
            top = Math.min(top, stroke.top);
            right = Math.max(right, stroke.right);
            bottom = Math.max(bottom, stroke.bottom);
        }
        if (ink.strokes.isEmpty()) {
            ink.view.x = ink.view.y = 0;
            ink.view.scale = 1;
        } else {
            ink.view.x = (left + right) / 2;
            ink.view.y = (top + bottom) / 2;
            double fit = Math.min(getWidth() * .75 / Math.max(50, right - left),
                    getHeight() * .60 / Math.max(50, bottom - top));
            ink.view.scale = Math.max(.0001, Math.min(8, fit));
        }
        refresh();
    }
}
