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
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final class RenderState {
        final Path path;final Ink.Cut cut;final float[] points;final boolean filled;
        RenderState(Path path,Ink.Stroke stroke){this.path=path;cut=stroke.cuts;points=stroke.points;filled=cut!=null;}
    }
    private int penId=-1;private boolean erasing,dragging,marquee,nearPen;
    private long lastPen;
    private boolean temporaryEraser;
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
    public void tool(int value){cancelPen();mode=value;if(mode!=SELECT)ink.selected.clear();refresh();}
    public int displayMode(){return temporaryEraser?ERASE:mode;}
    public void temporaryEraser(boolean held){
        if(temporaryEraser==held)return;
        int active=penId;float x=lastX,y=lastY,p=lastPressure;int tool=lastTool;
        if(active!=-1)finishPen();temporaryEraser=held;
        if(!held)mode=PEN;ink.selected.clear();
        if(active!=-1)resumePen(active,x,y,p,tool);refresh();
    }
    public void setPenColor(int next){
        if(color==next)return;int active=penId;float x=lastX,y=lastY,p=lastPressure;int tool=lastTool;
        boolean split=active!=-1&&!erasing;if(split)finishPen();color=next;
        if(split)resumePen(active,x,y,p,tool);refresh();
    }
    private void resumePen(int id,float x,float y,float pressure,int tool){
        MotionEvent.PointerProperties prop=new MotionEvent.PointerProperties();prop.id=id;prop.toolType=tool;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.x=x;coords.y=y;coords.pressure=pressure;
        long time=android.os.SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,1,new MotionEvent.PointerProperties[]{prop},new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);beginPen(e,0);e.recycle();
    }
    public void setEraserSize(float size){eraserSize=size;cursorRadius=size/2;postInvalidateOnAnimation();}
    public void undo(){if(penId!=-1)finishPen();ink.undo();refresh();}
    public void refresh(){postInvalidateOnAnimation();changed.run();}
    @Override protected void onDraw(android.graphics.Canvas c){
        super.onDraw(c);drawGrid(c);
        double l=wx(0),t=wy(0),r=wx(getWidth()),b=wy(getHeight());
        for(Ink.Stroke s:ink.strokes){double dx=ink.selected.contains(s.id)?dragX:0,dy=ink.selected.contains(s.id)?dragY:0;if(s.intersects(l-dx,t-dy,r-dx,b-dy))drawStroke(c,s,dx,dy);}
        if(!samples.isEmpty()&&!erasing&&!dragging&&!marquee)drawStroke(c,activeStroke(),0,0);
        double[] bounds=ink.selectionBounds();
        if(marquee)bounds=new double[]{Math.min(startX,dragX),Math.min(startY,dragY),Math.max(startX,dragX),Math.max(startY,dragY)};
        else if(bounds!=null){bounds[0]+=dragX;bounds[2]+=dragX;bounds[1]+=dragY;bounds[3]+=dragY;}
        if(mode==SELECT&&bounds!=null){
            float x1=screenX(bounds[0]),y1=screenY(bounds[1]),x2=screenX(bounds[2]),y2=screenY(bounds[3]);
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
    private float screenX(double x){return (float)((x-ink.view.x)*ink.view.scale+getWidth()/2.0);}private float screenY(double y){return (float)((y-ink.view.y)*ink.view.scale+getHeight()/2.0);}
    private void drawGrid(android.graphics.Canvas c){
        double step=dp(32);while(step*ink.view.scale<dp(22))step*=2;while(step*ink.view.scale>dp(60))step/=2;
        double pixels=step*ink.view.scale;float x0=(float)((getWidth()/2.0-((ink.view.x%step)+step)%step*ink.view.scale)%pixels),y0=(float)((getHeight()/2.0-((ink.view.y%step)+step)%step*ink.view.scale)%pixels);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xFFB9C4BC);
        for(float x=x0;x<getWidth();x+=pixels)for(float y=y0;y<getHeight();y+=pixels)c.drawCircle(x,y,dp(.8f),paint);
    }
    private Path centerline(Ink.Stroke s){
        float[] points=s.pathPoints();
        Path path=new Path();path.moveTo(points[0],points[1]);
        for(int i=3;i<points.length;i+=3)path.lineTo(points[i],points[i+1]);
        return path;
    }
    private Path cutOutline(Ink.Stroke s,Path center,RenderState previous){
        Path outline=new Path();
        if(previous!=null&&previous.filled&&previous.points==s.points)outline.set(previous.path);
        else {
        if(s.pathPoints().length==3)outline.addCircle(s.points[0],s.points[1],s.width/2,Path.Direction.CW);
        else paint.getFillPath(center,outline);
            if(previous!=null&&previous.filled)outline.op(previous.path,Path.Op.INTERSECT);
        }
        Path mask=new Path();
        for(Ink.Cut cut=s.cuts;cut!=null&&(previous==null||cut!=previous.cut);cut=cut.previous){
            if(Math.max(cut.ax,cut.bx)+cut.radius<s.left-s.x||Math.min(cut.ax,cut.bx)-cut.radius>s.right-s.x||Math.max(cut.ay,cut.by)+cut.radius<s.top-s.y||Math.min(cut.ay,cut.by)-cut.radius>s.bottom-s.y)continue;
            float ax=(float)cut.ax,ay=(float)cut.ay,bx=(float)cut.bx,by=(float)cut.by,r=(float)cut.radius;
            mask.addCircle(ax,ay,r,Path.Direction.CW);mask.addCircle(bx,by,r,Path.Direction.CW);
            double length=Math.hypot(bx-ax,by-ay);
            if(length>1e-6){float nx=(float)(-(by-ay)/length*r),ny=(float)((bx-ax)/length*r);
                mask.moveTo(ax-nx,ay-ny);mask.lineTo(bx-nx,by-ny);mask.lineTo(bx+nx,by+ny);mask.lineTo(ax+nx,ay+ny);mask.close();}
        }
        outline.op(mask,Path.Op.DIFFERENCE);return outline;
    }
    private void drawStroke(android.graphics.Canvas c,Ink.Stroke s,double dx,double dy){
        c.save();c.translate(screenX(s.x+dx),screenY(s.y+dy));c.scale((float)ink.view.scale,(float)ink.view.scale);
        paint.setColor(s.color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(s.width);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        RenderState state=s.renderState instanceof RenderState?(RenderState)s.renderState:null;
        if(state==null){RenderState previous=s.previousRender instanceof RenderState?(RenderState)s.previousRender:null;
            Path path=centerline(s);if(s.cuts!=null)path=cutOutline(s,path,previous);state=new RenderState(path,s);
            if(s.id!=0){s.renderState=state;s.previousRender=null;}}
        Path path=state.path;
        if(s.cuts!=null)paint.setStyle(Paint.Style.FILL);c.drawPath(path,paint);
        if(s.cuts==null&&s.points.length==3){paint.setStyle(Paint.Style.FILL);c.drawCircle(s.points[0],s.points[1],s.width/2,paint);}
        c.restore();
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
        if(mode==SELECT){double[] box=ink.selectionBounds();dragging=box!=null&&startX>=box[0]&&startX<=box[2]&&startY>=box[1]&&startY<=box[3];marquee=!dragging;if(marquee){ink.selected.clear();dragX=startX;dragY=startY;}return;}
        samples.clear();strokeX=startX;strokeY=startY;strokeColor=color;strokeWidth=width;append(e.getX(i),e.getY(i),e.getPressure(i));
    }
    private void movePen(MotionEvent e,int i){
        lastX=e.getX(i);lastY=e.getY(i);lastPressure=e.getPressure(i);
        lastPen=e.getEventTime();double x=wx(e.getX(i)),y=wy(e.getY(i));
        if(erasing){for(int h=0;h<e.getHistorySize();h++)erase(wx(e.getHistoricalX(i,h)),wy(e.getHistoricalY(i,h)),e.getHistoricalPressure(i,h));erase(x,y,e.getPressure(i));}
        else if(dragging){dragX=x-startX;dragY=y-startY;}
        else if(marquee){dragX=x;dragY=y;}
        else {for(int h=0;h<e.getHistorySize();h++)append(e.getHistoricalX(i,h),e.getHistoricalY(i,h),e.getHistoricalPressure(i,h));append(e.getX(i),e.getY(i),e.getPressure(i));}
    }
    private void erase(double x,double y,float pressure){
        cursorRadius=(float)(eraserSize/2*Eraser.pressureScale(pressure,eraserSensitivity));double radius=cursorRadius/ink.view.scale;
        ink.erase(erasePoint?eraseX:x,erasePoint?eraseY:y,x,y,erasePoint?Math.max(radius,eraseRadius):radius);
        erasePoint=true;eraseX=x;eraseY=y;eraseRadius=radius;
    }
    private void finishPen(){
        if(erasing)ink.commit();else if(dragging)ink.move(dragX,dragY);else if(marquee)ink.select(Math.min(startX,dragX),Math.min(startY,dragY),Math.max(startX,dragX),Math.max(startY,dragY));
        else if(!samples.isEmpty()){Ink.Stroke s=activeStroke();ink.add(new Ink.Stroke(ink.nextId(),s.color,s.width,s.x,s.y,s.points));}
        samples.clear();erasePoint=false;penId=-1;dragging=marquee=erasing=false;dragX=dragY=0;refresh();
    }
    private void cancelPen(){if(erasing)ink.cancel();samples.clear();erasePoint=false;penId=-1;dragging=marquee=erasing=false;dragX=dragY=0;}
    @Override public boolean onHoverEvent(MotionEvent e){
        if(e.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS||e.getToolType(0)==MotionEvent.TOOL_TYPE_ERASER){
            nearPen=e.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT;lastPen=e.getEventTime();
            lastX=e.getX();lastY=e.getY();cursorKnown=nearPen;cursorRadius=eraserSize/2;postInvalidateOnAnimation();return true;
        }return super.onHoverEvent(e);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        getParent().requestDisallowInterceptTouchEvent(true);int action=e.getActionMasked(),index=e.getActionIndex();
        if(action==MotionEvent.ACTION_CANCEL){cancelPen();if(fingers){ink.view.x=gestureX;ink.view.y=gestureY;ink.view.scale=gestureScale;}fingers=false;refresh();return true;}
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){int tool=e.getToolType(index);if(tool==MotionEvent.TOOL_TYPE_STYLUS||tool==MotionEvent.TOOL_TYPE_ERASER){beginPen(e,index);postInvalidateOnAnimation();return true;}}
        if(penId!=-1){int i=e.findPointerIndex(penId);if(i<0){cancelPen();return true;}if((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)&&e.getPointerId(index)==penId){
                if(Build.VERSION.SDK_INT>=33&&(e.getFlags()&MotionEvent.FLAG_CANCELED)!=0){cancelPen();refresh();}else{movePen(e,i);finishPen();}
            }else if(action==MotionEvent.ACTION_MOVE){movePen(e,i);postInvalidateOnAnimation();}return true;}
        if(Build.VERSION.SDK_INT>=33&&action==MotionEvent.ACTION_POINTER_UP&&(e.getFlags()&MotionEvent.FLAG_CANCELED)!=0){if(fingers){ink.view.x=gestureX;ink.view.y=gestureY;ink.view.scale=gestureScale;}fingers=false;refresh();return true;}
        if(action==MotionEvent.ACTION_UP){fingers=false;performClick();return true;}
        int count=0;float cx=0,cy=0,distance=0,firstX=0,firstY=0;
        for(int i=0;i<e.getPointerCount();i++){if(action==MotionEvent.ACTION_POINTER_UP&&i==index)continue;if(e.getToolType(i)!=MotionEvent.TOOL_TYPE_FINGER)continue;if(count==0){firstX=e.getX(i);firstY=e.getY(i);}else if(count==1)distance=(float)Math.hypot(e.getX(i)-firstX,e.getY(i)-firstY);cx+=e.getX(i);cy+=e.getY(i);count++;if(count==2)break;}
        if(count==0||((nearPen||e.getEventTime()-lastPen<250)&&count==1)){fingers=false;return true;}cx/=count;cy/=count;
        if(!fingers||count!=fingerCount){if(!fingers){gestureX=ink.view.x;gestureY=ink.view.y;gestureScale=ink.view.scale;}fingers=true;fingerCount=count;prevX=cx;prevY=cy;prevDistance=distance;return true;}
        if(action==MotionEvent.ACTION_MOVE){double anchorX=wx(prevX),anchorY=wy(prevY);if(count==2&&prevDistance>1)ink.view.zoom(distance/prevDistance,prevX,prevY,getWidth(),getHeight());ink.view.x=anchorX-(cx-getWidth()/2.0)/ink.view.scale;ink.view.y=anchorY-(cy-getHeight()/2.0)/ink.view.scale;prevX=cx;prevY=cy;prevDistance=distance;refresh();}return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    public void overview(){
        if(ink.strokes.isEmpty()){ink.view.x=ink.view.y=0;ink.view.scale=1;refresh();return;}
        double l=Double.POSITIVE_INFINITY,t=l,r=-l,b=-l;for(Ink.Stroke s:ink.strokes){l=Math.min(l,s.left);r=Math.max(r,s.right);t=Math.min(t,s.top);b=Math.max(b,s.bottom);}
        ink.view.x=(l+r)/2;ink.view.y=(t+b)/2;ink.view.scale=Math.max(.0001,Math.min(8,Math.min(getWidth()*.75/Math.max(50,r-l),getHeight()*.6/Math.max(50,b-t))));refresh();
    }
}
