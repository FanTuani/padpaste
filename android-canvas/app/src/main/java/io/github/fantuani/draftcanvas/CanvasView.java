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
    Runnable marqueeFinished = () -> {};
    private boolean fingerTap;
    private float fingerDownX, fingerDownY;
    private long fingerDownTime;
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
    private boolean selectionHeld, hoverBox, selectionUsedContact, hoverKnown;
    private int ignoredPenId = -1;
    private PointF completedSelection;
    private float hoverX, hoverY, boxX, boxY, boxEndX, boxEndY;
    private float lastX,lastY,lastPressure;
    private int lastTool;
    private boolean cursorKnown,erasePoint;private float cursorRadius;
    private double eraseX,eraseY,eraseRadius;
    private double startX,startY,dragX,dragY;
    private final ArrayList<Float> samples=new ArrayList<>();private double strokeX,strokeY;
    private int strokeColor;private float strokeWidth;
    private boolean fingers;private int fingerCount;private float prevX,prevY,prevDistance;
    private double gestureX,gestureY,gestureScale;
    public CanvasView(Context c,Ink document){super(c);ink=document;width=dp(2.3f);eraserSize=dp(24);setBackgroundColor(0xFFFAFAF7);setFocusable(true);setContentDescription("草稿画布：手写笔书写，手指移动，双指缩放");if(Build.VERSION.SDK_INT>=26)setDefaultFocusHighlightEnabled(false);}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    private double wx(float x){return ink.view.worldX(x,getWidth());}private double wy(float y){return ink.view.worldY(y,getHeight());}
    public void tool(int value){cancelPen();mode=value;if(mode!=SELECT)ink.selected.clear();refreshOverlay();}
    public int displayMode(){return temporaryEraser?ERASE:mode;}
    public void temporaryEraser(boolean held){
        if(temporaryEraser==held)return;
        int active=penId;float x=lastX,y=lastY,p=lastPressure;int tool=lastTool;
        if(active!=-1)finishPen();temporaryEraser=held;
        if(!held)mode=PEN;ink.selected.clear();
        if(active!=-1)resumePen(active,x,y,p,tool);refreshOverlay();
    }
    PointF menuAnchor() {
        if (hoverKnown) return new PointF(hoverX, hoverY);
        if (cursorKnown) return new PointF(lastX, lastY);
        return new PointF(getWidth() / 2f, getHeight() / 2f);
    }

    PointF touchAnchor() {
        if (getWidth() == 0 || getHeight() == 0) return menuAnchor();
        return new PointF(fingerDownX, fingerDownY);
    }

    void finishContactForMenu() {
        if (penId == -1) return;
        ignoredPenId = penId;
        finishPen();
    }

    void beginSelectionHold() {
        int pointer = penId;
        if (pointer != -1) finishPen();
        ink.selected.clear();
        completedSelection = null;
        selectionHeld = true;
        selectionUsedContact = pointer != -1;
        mode = SELECT;
        if (selectionUsedContact) {
            resumePen(pointer, lastX, lastY, lastPressure, lastTool);
        } else if (nearPen) {
            updateHoverBox(hoverX, hoverY);
        }
        refreshOverlay();
    }

    PointF endSelectionHold(boolean canceled) {
        if (!selectionHeld) return null;
        if (penId != -1) {
            ignoredPenId = penId;
            if (canceled) cancelPen();
            else finishPen();
        } else if (!canceled && !selectionUsedContact && hoverBox
                && Math.hypot(boxEndX - boxX, boxEndY - boxY) >= dp(4)) {
            selectRectangle(wx(boxX), wy(boxY), wx(boxEndX), wy(boxEndY));
            completedSelection = new PointF(boxEndX, boxEndY);
        }
        PointF anchor = canceled ? null : completedSelection;
        if (anchor == null) ink.selected.clear();
        completedSelection = null;
        selectionHeld = hoverBox = selectionUsedContact = false;
        mode = PEN;
        refreshOverlay();
        return anchor;
    }

    private void selectRectangle(double x1, double y1, double x2, double y2) {
        ink.select(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
    }

    private void updateHoverBox(float x, float y) {
        if (!hoverBox) {
            boxX = x;
            boxY = y;
            hoverBox = true;
        }
        boxEndX = x;
        boxEndY = y;
    }

    void clearSelection() {
        ink.selected.clear();
        refreshOverlay();
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
    public void undo(){if(penId!=-1)finishPen();ink.undo();refresh();}
    private void refreshOverlay(){postInvalidateOnAnimation();changed.run();}
    public void refresh(){if(tiles!=null)tiles.invalidateAll();if(raster!=null)raster.modelChanged(ink);postInvalidateOnAnimation();changed.run();}
    @Override protected void onDraw(android.graphics.Canvas c){
        super.onDraw(c);drawGrid(c);
        if(c.isHardwareAccelerated()&&(isAttachedToWindow()||testAsync)&&!dragging){
            if(raster==null)raster=new AsyncInkTiles(this::postInvalidateOnAnimation);
            raster.draw(c,ink,new AsyncInkTiles.Camera(getWidth(),getHeight(),ink.view.x,ink.view.y,ink.view.scale,getResources().getDisplayMetrics().density,gridPoints,gridCount));
        }else if(Build.VERSION.SDK_INT>=29&&c.isHardwareAccelerated()&&!fingers&&dragX==0&&dragY==0)
            tiles.draw(c,getWidth(),getHeight(),ink.view.x,ink.view.y,ink.view.scale,this::drawInk);
        else drawInk(c,0,0,getWidth(),getHeight());
        if(!samples.isEmpty()&&!erasing&&!dragging&&!marquee)drawStroke(c,activeStroke(),0,0,wx(0)-strokeX,wy(0)-strokeY,wx(getWidth())-strokeX,wy(getHeight())-strokeY);
        double[] bounds=ink.selectionBounds();
        if(marquee)bounds=new double[]{Math.min(startX,dragX),Math.min(startY,dragY),Math.max(startX,dragX),Math.max(startY,dragY)};
        else if(bounds!=null){bounds[0]+=dragX;bounds[2]+=dragX;bounds[1]+=dragY;bounds[3]+=dragY;}
        if (hoverBox) {
            bounds = new double[]{wx(Math.min(boxX, boxEndX)), wy(Math.min(boxY, boxEndY)),
                    wx(Math.max(boxX, boxEndX)), wy(Math.max(boxY, boxEndY))};
        }
        if(bounds!=null){
            float x1=screenX(bounds[0]),y1=screenY(bounds[1]),x2=screenX(bounds[2]),y2=screenY(bounds[3]);
            paint.setStyle(Paint.Style.FILL);paint.setColor(0x11167B70);c.drawRect(x1,y1,x2,y2,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setColor(0xFF167B70);paint.setStrokeWidth(dp(1.2f));paint.setPathEffect(new DashPathEffect(new float[]{dp(5),dp(4)},0));c.drawRect(x1,y1,x2,y2,paint);paint.setPathEffect(null);
            if(!marquee&&!hoverBox){paint.setStyle(Paint.Style.FILL);for(float x:new float[]{x1,x2})for(float y:new float[]{y1,y2}){paint.setColor(Color.WHITE);c.drawCircle(x,y,dp(4),paint);paint.setStyle(Paint.Style.STROKE);paint.setColor(0xFF167B70);c.drawCircle(x,y,dp(4),paint);paint.setStyle(Paint.Style.FILL);}}
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
    private Path centerline(Ink.Stroke s){
        float[] points=s.pathPoints();
        Path path=new Path();path.moveTo(points[0],points[1]);
        for(int i=3;i<points.length;i+=3)path.lineTo(points[i],points[i+1]);
        return path;
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
        paint.setColor(s.color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(s.width);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        if(s.points.length==3){paint.setStyle(Paint.Style.FILL);c.drawCircle(s.points[0],s.points[1],s.width/2,paint);}
        else if(s.id==0)c.drawPath(centerline(s),paint);
        else {StrokeData data=s.data();drawSections(c,s,data,data.root,l,t,r,b);}c.restore();
    }
    private Ink.Stroke activeStroke(){float[] p=new float[samples.size()];for(int i=0;i<p.length;i++)p[i]=samples.get(i);return new Ink.Stroke(0,strokeColor,strokeWidth,strokeX,strokeY,p);}
    private void append(float x,float y,float pressure){
        float lx=(float)(wx(x)-strokeX),ly=(float)(wy(y)-strokeY);int n=samples.size();if(n>=3&&Math.hypot(lx-samples.get(n-3),ly-samples.get(n-2))*ink.view.scale<.25)return;
        samples.add(lx);samples.add(ly);samples.add(1f);
    }
    private void beginPen(MotionEvent e,int i){
        lastX=e.getX(i);lastY=e.getY(i);lastPressure=e.getPressure(i);lastTool=e.getToolType(i);cursorKnown=true;
        penId=e.getPointerId(i);fingers=false;lastPen=e.getEventTime();startX=wx(e.getX(i));startY=wy(e.getY(i));dragX=dragY=0;
        erasing=temporaryEraser||mode==ERASE||e.getToolType(i)==MotionEvent.TOOL_TYPE_ERASER;
        if(erasing){ink.begin();erasePoint=false;erase(startX,startY,e.getPressure(i));return;}
        if (selectionHeld) {
            selectionUsedContact = true;
            completedSelection = null;
            hoverBox = false;
        }
        double[] box = ink.selectionBounds();
        dragging = !selectionHeld && box != null && startX >= box[0] && startX <= box[2]
                && startY >= box[1] && startY <= box[3];
        if (dragging) return;
        ink.selected.clear();
        if (mode == SELECT) {
            marquee = true;
            dragX = startX;
            dragY = startY;
            return;
        }
        samples.clear();strokeX=startX;strokeY=startY;strokeColor=color;strokeWidth=width;append(e.getX(i),e.getY(i),e.getPressure(i));
    }
    private void movePen(MotionEvent e,int i){
        lastX=e.getX(i);lastY=e.getY(i);lastPressure=e.getPressure(i);
        lastPen=e.getEventTime();double x=wx(e.getX(i)),y=wy(e.getY(i));
        if(erasing){
            boolean straight=erasePoint;float maxPressure=e.getPressure(i);double tolerance=dp(.4f)/ink.view.scale;
            for(int h=0;h<e.getHistorySize();h++){maxPressure=Math.max(maxPressure,e.getHistoricalPressure(i,h));if(!erasePoint||Eraser.distanceSquared(wx(e.getHistoricalX(i,h)),wy(e.getHistoricalY(i,h)),eraseX,eraseY,x,y)>tolerance*tolerance)straight=false;}
            if(straight)erase(x,y,maxPressure);else {for(int h=0;h<e.getHistorySize();h++)erase(wx(e.getHistoricalX(i,h)),wy(e.getHistoricalY(i,h)),e.getHistoricalPressure(i,h));erase(x,y,e.getPressure(i));}
            cursorRadius=(float)(eraserSize/2*Eraser.pressureScale(e.getPressure(i),eraserSensitivity));
        }
        else if(dragging){dragX=x-startX;dragY=y-startY;}
        else if(marquee){dragX=x;dragY=y;}
        else {for(int h=0;h<e.getHistorySize();h++)append(e.getHistoricalX(i,h),e.getHistoricalY(i,h),e.getHistoricalPressure(i,h));append(e.getX(i),e.getY(i),e.getPressure(i));}
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
        boolean selectedBox = marquee
                && (!selectionHeld || Math.hypot(dragX - startX, dragY - startY) * ink.view.scale >= dp(4));
        if(erasing)ink.commit();else if(dragging)ink.move(dragX,dragY);else if(marquee){
            if (selectedBox) {
                selectRectangle(startX, startY, dragX, dragY);
                if (selectionHeld) completedSelection = new PointF(lastX, lastY);
            }
        }
        else if(!samples.isEmpty()){Ink.Stroke s=activeStroke();Ink.Stroke committed=new Ink.Stroke(ink.nextId(),s.color,s.width,s.x,s.y,s.points);ink.add(committed);if(raster!=null)raster.stroke(ink,committed);added=true;invalidateErasedArea(screenX(s.left),screenY(s.top),screenX(s.right),screenY(s.bottom));}
        samples.clear();erasePoint=false;penId=-1;dragging=marquee=erasing=false;dragX=dragY=0;if(erased||added){if(raster!=null)raster.noteRevision(ink);postInvalidateOnAnimation();changed.run();}else refresh();
        if (selectedBox) marqueeFinished.run();
    }
    private void cancelPen(){if(erasing){ink.cancel();if(tiles!=null)tiles.invalidateAll();if(raster!=null)raster.modelChanged(ink);}samples.clear();erasePoint=false;penId=-1;dragging=marquee=erasing=false;dragX=dragY=0;}
    @Override public boolean onHoverEvent(MotionEvent e){
        if(e.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS||e.getToolType(0)==MotionEvent.TOOL_TYPE_ERASER){
            nearPen=e.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT;lastPen=e.getEventTime();
            if (nearPen) {
                hoverKnown = true;
                hoverX = lastX = e.getX();
                hoverY = lastY = e.getY();
                if (selectionHeld && !selectionUsedContact && penId == -1) updateHoverBox(hoverX, hoverY);
                cursorKnown = true;
            }
            cursorRadius=eraserSize/2;postInvalidateOnAnimation();return true;
        }return super.onHoverEvent(e);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        getParent().requestDisallowInterceptTouchEvent(true);int action=e.getActionMasked(),index=e.getActionIndex();
        if(action==MotionEvent.ACTION_CANCEL){fingerTap=false;ignoredPenId=-1;cancelPen();if(fingers){ink.view.x=gestureX;ink.view.y=gestureY;ink.view.scale=gestureScale;}fingers=false;refresh();return true;}
        if (action == MotionEvent.ACTION_DOWN) {
            fingerTap = e.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER;
            fingerDownX = e.getX(index);
            fingerDownY = e.getY(index);
            fingerDownTime = e.getEventTime();
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
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){int tool=e.getToolType(index);if(tool==MotionEvent.TOOL_TYPE_STYLUS||tool==MotionEvent.TOOL_TYPE_ERASER){beginPen(e,index);postInvalidateOnAnimation();return true;}}
        if(penId!=-1){int i=e.findPointerIndex(penId);if(i<0){cancelPen();return true;}if((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)&&e.getPointerId(index)==penId){
                if(Build.VERSION.SDK_INT>=33&&(e.getFlags()&MotionEvent.FLAG_CANCELED)!=0){cancelPen();refresh();}else{movePen(e,i);finishPen();}
            }else if(action==MotionEvent.ACTION_MOVE){movePen(e,i);postInvalidateOnAnimation();}return true;}
        if(Build.VERSION.SDK_INT>=33&&action==MotionEvent.ACTION_POINTER_UP&&(e.getFlags()&MotionEvent.FLAG_CANCELED)!=0){if(fingers){ink.view.x=gestureX;ink.view.y=gestureY;ink.view.scale=gestureScale;}fingers=false;refresh();return true;}
        if(action==MotionEvent.ACTION_UP){
            fingers=false;
            if (fingerTap && !nearPen && e.getEventTime() - fingerDownTime < 250) performClick();
            fingerTap=false;
            return true;
        }
        if (selectionHeld) return true;
        int count=0;float cx=0,cy=0,distance=0,firstX=0,firstY=0;
        for(int i=0;i<e.getPointerCount();i++){if(action==MotionEvent.ACTION_POINTER_UP&&i==index)continue;if(e.getToolType(i)!=MotionEvent.TOOL_TYPE_FINGER)continue;if(count==0){firstX=e.getX(i);firstY=e.getY(i);}else if(count==1)distance=(float)Math.hypot(e.getX(i)-firstX,e.getY(i)-firstY);cx+=e.getX(i);cy+=e.getY(i);count++;if(count==2)break;}
        if(count==0||((nearPen||e.getEventTime()-lastPen<250)&&count==1)){fingers=false;return true;}cx/=count;cy/=count;
        if(!fingers||count!=fingerCount){if(!fingers){gestureX=ink.view.x;gestureY=ink.view.y;gestureScale=ink.view.scale;}fingers=true;fingerCount=count;prevX=cx;prevY=cy;prevDistance=distance;return true;}
        if(action==MotionEvent.ACTION_MOVE && !fingerTap){double anchorX=wx(prevX),anchorY=wy(prevY);if(count==2&&prevDistance>1)ink.view.zoom(distance/prevDistance,prevX,prevY,getWidth(),getHeight());ink.view.x=anchorX-(cx-getWidth()/2.0)/ink.view.scale;ink.view.y=anchorY-(cy-getHeight()/2.0)/ink.view.scale;prevX=cx;prevY=cy;prevDistance=distance;refresh();}return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}

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
