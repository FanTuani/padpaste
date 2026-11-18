package io.github.fantuani.draftcanvas;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.view.*;
import java.util.*;

public final class CanvasView extends View {
    public static final int PEN=0,SELECT=1,ERASE=2;
    public final Ink ink;
    public int mode=PEN,color=0xFF243137;public float width,eraserSize,eraserSensitivity=2;
    public Runnable changed=()->{};
    private boolean fingerTap;
    private float fingerDownX, fingerDownY;
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
    private long lastPen;
    private boolean temporaryEraser;
    private boolean selectionContact, hoverKnown;
    private int ignoredPenId = -1;
    private SelectionBox selectionBox;
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
    private record SelectionBox(double left, double top, double right, double bottom) {
        static SelectionBox between(double x, double y, double otherX, double otherY) {
            return new SelectionBox(Math.min(x, otherX), Math.min(y, otherY),
                    Math.max(x, otherX), Math.max(y, otherY));
        }

        boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }

        SelectionBox translated(double x, double y) {
            return new SelectionBox(left + x, top + y, right + x, bottom + y);
        }
    }

    public CanvasView(Context c,Ink document){super(c);ink=document;width=dp(2.3f);eraserSize=dp(24);setBackgroundColor(0xFFFAFAF7);setFocusable(true);setContentDescription("草稿画布：手写笔书写，手指移动，双指缩放");if(Build.VERSION.SDK_INT>=26)setDefaultFocusHighlightEnabled(false);}
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
        finishContactForMenu();
    }

    void armSelection() {
        if (selectionArmed()) return;
        finishContactForMenu();
        tool(SELECT);
    }

    boolean selectionArmed() { return mode == SELECT; }

    boolean hasSelectionBox() { return selectionBox != null; }

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
        selectionBox = new SelectionBox(bounds[0], bounds[1], bounds[2], bounds[3]);
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
        if(c.isHardwareAccelerated()&&(isAttachedToWindow()||testAsync)&&!dragging){
            if(raster==null)raster=new AsyncInkTiles(this::postInvalidateOnAnimation);
            raster.draw(c,ink,new AsyncInkTiles.Camera(getWidth(),getHeight(),ink.view.x,ink.view.y,ink.view.scale,getResources().getDisplayMetrics().density), fingers);
        }else if(Build.VERSION.SDK_INT>=29&&c.isHardwareAccelerated()&&!fingers&&dragX==0&&dragY==0)
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
        SelectionBox bounds = marquee ? SelectionBox.between(startX, startY, dragX, dragY) : selectionBox;
        if (dragging && bounds != null) bounds = bounds.translated(dragX, dragY);
        if(bounds!=null){
            float x1=screenX(bounds.left),y1=screenY(bounds.top),x2=screenX(bounds.right),y2=screenY(bounds.bottom);
            paint.setStyle(Paint.Style.FILL);paint.setColor(0x11167B70);c.drawRect(x1,y1,x2,y2,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setColor(0xFF167B70);paint.setStrokeWidth(dp(1.2f));paint.setPathEffect(new DashPathEffect(new float[]{dp(5),dp(4)},0));c.drawRect(x1,y1,x2,y2,paint);paint.setPathEffect(null);
            if(!marquee){paint.setStyle(Paint.Style.FILL);for(float x:new float[]{x1,x2})for(float y:new float[]{y1,y2}){paint.setColor(Color.WHITE);c.drawCircle(x,y,dp(4),paint);paint.setStyle(Paint.Style.STROKE);paint.setColor(0xFF167B70);c.drawCircle(x,y,dp(4),paint);paint.setStyle(Paint.Style.FILL);}}
        }
        if(displayMode()==ERASE&&cursorKnown&&(nearPen||penId!=-1||temporaryEraser)){
            float radius=cursorRadius>0?cursorRadius:eraserSize/2;
            paint.setStyle(Paint.Style.FILL);paint.setColor(0x10167B70);c.drawCircle(lastX,lastY,radius,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2.5f));paint.setColor(0xE6FFFFFF);c.drawCircle(lastX,lastY,radius,paint);
            paint.setStrokeWidth(dp(1));paint.setColor(0xD9167B70);c.drawCircle(lastX,lastY,radius,paint);
        }
    }
    private void drawInk(android.graphics.Canvas c,int left,int top,int right,int bottom){
        double l=wx(left),t=wy(top),r=wx(right),b=wy(bottom);
        for(Ink.Stroke s:ink.strokes){double dx=ink.selected.contains(s.id)?dragX:0,dy=ink.selected.contains(s.id)?dragY:0;if(s.intersects(l-dx,t-dy,r-dx,b-dy))drawStroke(c,s,dx,dy,l-dx-s.x,t-dy-s.y,r-dx-s.x,b-dy-s.y);}
    }
    @Override protected void onDetachedFromWindow(){if(raster!=null){raster.close();raster=null;}if(tiles!=null)tiles.clear();super.onDetachedFromWindow();}
    private float screenX(double x){return (float)((x-ink.view.x)*ink.view.scale+getWidth()/2.0);}private float screenY(double y){return (float)((y-ink.view.y)*ink.view.scale+getHeight()/2.0);}
    private void drawGrid(android.graphics.Canvas c){
        if(gridPoints==null||gridX!=ink.view.x||gridY!=ink.view.y||gridScale!=ink.view.scale||gridWidth!=getWidth()||gridHeight!=getHeight()){
            gridX=ink.view.x;gridY=ink.view.y;gridScale=ink.view.scale;gridWidth=getWidth();gridHeight=getHeight();
            double step=dp(32);while(step*ink.view.scale<dp(22))step*=2;while(step*ink.view.scale>dp(60))step/=2;
            double pixels=step*ink.view.scale;float x0=(float)((getWidth()/2.0-((ink.view.x%step)+step)%step*ink.view.scale)%pixels),y0=(float)((getHeight()/2.0-((ink.view.y%step)+step)%step*ink.view.scale)%pixels);
            gridPoints=new float[((int)Math.ceil(getWidth()/pixels)+2)*((int)Math.ceil(getHeight()/pixels)+2)*2];gridCount=0;
            for(float x=x0;x<getWidth();x+=pixels)for(float y=y0;y<getHeight();y+=pixels){gridPoints[gridCount++]=x;gridPoints[gridCount++]=y;}
        }
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(dp(1.6f));paint.setColor(0xFFB9C4BC);c.drawPoints(gridPoints,0,gridCount,paint);
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
    private void drawStroke(android.graphics.Canvas c,Ink.Stroke s,double dx,double dy,double l,double t,double r,double b){
        c.save();c.translate(screenX(s.x+dx),screenY(s.y+dy));c.scale((float)ink.view.scale,(float)ink.view.scale);
        setStrokePaint(s.color, s.width);
        if(s.points.length==3){paint.setStyle(Paint.Style.FILL);c.drawCircle(s.points[0],s.points[1],s.width/2,paint);}
        else {StrokeData data=s.data();drawSections(c,s,data,data.root,l,t,r,b);}c.restore();
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
        penId=e.getPointerId(i);fingers=false;lastPen=e.getEventTime();startX=wx(e.getX(i));startY=wy(e.getY(i));dragX=dragY=0;
        erasing=temporaryEraser||mode==ERASE||e.getToolType(i)==MotionEvent.TOOL_TYPE_ERASER;
        if(erasing){ink.begin();erasePoint=false;erase(startX,startY,e.getPressure(i));return;}
        selectionContact = selectionBox != null && selectionBox.contains(startX, startY);
        if (selectionContact) return;
        discardSelection();
        if (mode == SELECT) {
            marquee = true;
            dragX = startX;
            dragY = startY;
            return;
        }
        liveStroke.clear();
        strokeX = startX;
        strokeY = startY;
        strokeColor = color;
        strokeWidth = width;
        liveStroke.add(0, 0, ink.view.scale, strokeWidth);
    }
    private boolean movePen(MotionEvent e,int i){
        lastX=e.getX(i);lastY=e.getY(i);lastPressure=e.getPressure(i);
        lastPen=e.getEventTime();double x=wx(e.getX(i)),y=wy(e.getY(i));
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
            dragX = dx;
            dragY = dy;
        }
        else if(marquee){dragX=x;dragY=y;}
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
        boolean tapSelection = selectionContact && !dragging
                && selectionBox.contains(wx(lastX), wy(lastY));
        boolean movedSelection = dragging;
        if (erasing) ink.commit();
        else if (dragging) {
            ink.move(dragX, dragY);
            selectionBox = selectionBox.translated(dragX, dragY);
        } else if (marquee) {
            if (startX != dragX && startY != dragY
                    && Math.hypot(dragX - startX, dragY - startY) * ink.view.scale >= dp(4)) {
                selectionBox = SelectionBox.between(startX, startY, dragX, dragY);
                ink.select(selectionBox.left, selectionBox.top, selectionBox.right, selectionBox.bottom);
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
        selectionContact = dragging = marquee = erasing = false;
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

    @Override public boolean onHoverEvent(MotionEvent e){
        if(e.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS||e.getToolType(0)==MotionEvent.TOOL_TYPE_ERASER){
            nearPen=e.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT;lastPen=e.getEventTime();
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
        if(action==MotionEvent.ACTION_CANCEL){fingerTap=false;ignoredPenId=-1;cancelPen();if(fingers){ink.view.x=gestureX;ink.view.y=gestureY;ink.view.scale=gestureScale;}fingers=false;refresh();return true;}
        if (action == MotionEvent.ACTION_DOWN) {
            fingerTap = e.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER;
            fingerDownX = e.getX(index);
            fingerDownY = e.getY(index);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN
                || Math.hypot(e.getX(index) - fingerDownX, e.getY(index) - fingerDownY) > dp(8)) {
            fingerTap = false;
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
        if(Build.VERSION.SDK_INT>=33&&action==MotionEvent.ACTION_POINTER_UP&&(e.getFlags()&MotionEvent.FLAG_CANCELED)!=0){if(fingers){ink.view.x=gestureX;ink.view.y=gestureY;ink.view.scale=gestureScale;}fingers=false;refresh();return true;}
        if(action==MotionEvent.ACTION_UP){
            if (fingers) postInvalidateOnAnimation();
            fingers=false;
            fingerTap=false;
            return true;
        }
        int count=0;float cx=0,cy=0,distance=0,firstX=0,firstY=0;
        for(int i=0;i<e.getPointerCount();i++){if(action==MotionEvent.ACTION_POINTER_UP&&i==index)continue;if(e.getToolType(i)!=MotionEvent.TOOL_TYPE_FINGER)continue;if(count==0){firstX=e.getX(i);firstY=e.getY(i);}else if(count==1)distance=(float)Math.hypot(e.getX(i)-firstX,e.getY(i)-firstY);cx+=e.getX(i);cy+=e.getY(i);count++;if(count==2)break;}
        if(count==0||((nearPen||e.getEventTime()-lastPen<250)&&count==1)){fingers=false;return true;}cx/=count;cy/=count;
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

    void overview() {
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
