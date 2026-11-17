package io.github.fantuani.draftcanvas;

import android.graphics.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Software rasterizes immutable model snapshots off the UI thread; input is replayed over stale tiles immediately. */
final class AsyncInkTiles {
    private static final int SIZE=256;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);task.run();},"draft-raster"));
    private final Runnable invalidate;private final Paint bitmapPaint=new Paint(Paint.FILTER_BITMAP_FLAG),overlayPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Ink.Stroke,Path> paths=new LinkedHashMap<Ink.Stroke,Path>(1024,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<Ink.Stroke,Path> entry){return size()>1024;}};
    private final ArrayList<Op> ops=new ArrayList<>();private Phase phase,previous;private Ink document;
    private boolean busy,closed;private long sequence,epoch,seenRevision=-1;private int priority;
    private Bitmap pattern;private BitmapShader paper;private final Matrix shaderMatrix=new Matrix();
    AsyncInkTiles(Runnable invalidate){this.invalidate=invalidate;}
    static final class Camera {
        final int width,height;final double x,y,scale;final float density;final float[] grid;final int gridCount;
        Camera(int w,int h,double x,double y,double scale,float density,float[] grid,int count){width=w;height=h;this.x=x;this.y=y;this.scale=scale;this.density=density;this.grid=grid;gridCount=count;}
        double wx(double sx){return x+(sx-width/2.0)/scale;}double wy(double sy){return y+(sy-height/2.0)/scale;}
        float sx(double wx){return (float)((wx-x)*scale+width/2.0);}float sy(double wy){return (float)((wy-y)*scale+height/2.0);}
        boolean same(Camera b){return width==b.width&&height==b.height&&x==b.x&&y==b.y&&scale==b.scale;}
    }
    private static final class Tile {
        final int left,top,right,bottom;Bitmap bitmap;long sequence=-1,dirty;
        Tile(int l,int t,int r,int b,long dirty){left=l;top=t;right=r;bottom=b;this.dirty=dirty;}
    }
    private static final class Phase {
        final Camera camera;final Tile[] tiles;
        Phase(Camera camera,long seq){this.camera=camera;int cols=(camera.width+SIZE-1)/SIZE,rows=(camera.height+SIZE-1)/SIZE;tiles=new Tile[cols*rows];for(int i=0;i<tiles.length;i++){int l=i%cols*SIZE,t=i/cols*SIZE;tiles[i]=new Tile(l,t,Math.min(l+SIZE,camera.width),Math.min(t+SIZE,camera.height),seq);}}
    }
    private static final class Op {
        final long seq;final double ax,ay,bx,by,radius,left,top,right,bottom;final Ink.Stroke stroke;Path path;
        Op(long seq,double ax,double ay,double bx,double by,double radius){this.seq=seq;this.ax=ax;this.ay=ay;this.bx=bx;this.by=by;this.radius=radius;stroke=null;left=Math.min(ax,bx)-radius;right=Math.max(ax,bx)+radius;top=Math.min(ay,by)-radius;bottom=Math.max(ay,by)+radius;}
        Op(long seq,Ink.Stroke s){this.seq=seq;stroke=s;ax=ay=bx=by=radius=0;left=s.left;top=s.top;right=s.right;bottom=s.bottom;}
        boolean intersects(double l,double t,double r,double b){return right>=l&&left<=r&&bottom>=t&&top<=b;}
    }
    void noteRevision(Ink ink){seenRevision=ink.revision;}
    void modelChanged(Ink ink){document=ink;if(seenRevision==ink.revision)return;seenRevision=ink.revision;epoch++;sequence++;ops.clear();if(ink.strokes.isEmpty()){phase=previous=null;}else dirtyAll();}
    void stroke(Ink ink,Ink.Stroke stroke){document=ink;seenRevision=ink.revision;Op op=new Op(++sequence,stroke);ops.add(op);dirty(op.left,op.top,op.right,op.bottom);}
    void erase(Ink ink,double ax,double ay,double bx,double by,double radius,double padding){document=ink;seenRevision=ink.revision;Op op=new Op(++sequence,ax,ay,bx,by,radius);ops.add(op);dirty(op.left-padding,op.top-padding,op.right+padding,op.bottom+padding);}
    private void dirtyAll(){if(phase!=null)for(Tile tile:phase.tiles)tile.dirty=sequence;}
    private void dirty(double l,double t,double r,double b){
        if(phase==null)return;Camera c=phase.camera;
        for(int i=0;i<phase.tiles.length;i++){Tile tile=phase.tiles[i];if(r>=c.wx(tile.left)&&l<=c.wx(tile.right)&&b>=c.wy(tile.top)&&t<=c.wy(tile.bottom)){tile.dirty=sequence;if(!busy)priority=i;}}
    }
    void draw(Canvas canvas,Ink ink,Camera camera){
        document=ink;if(seenRevision<0)seenRevision=ink.revision;
        if(phase==null||!phase.camera.same(camera)){if(phase!=null&&(hasAllTiles(phase)||previous==null))previous=phase;phase=new Phase(camera,sequence);makePaper(camera);}
        if(previous!=null)drawPhase(canvas,previous,camera);
        drawPhase(canvas,phase,camera);pump();
    }
    private void makePaper(Camera camera){
        double step=camera.density*32;while(step*camera.scale<camera.density*22)step*=2;while(step*camera.scale>camera.density*60)step/=2;
        pattern=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(pattern);c.drawColor(0xfffafaf7);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(0xffb9c4bc);float r=(float)(camera.density*.8/camera.scale*128/step);
        for(int x:new int[]{0,128})for(int y:new int[]{0,128})c.drawCircle(x,y,r,p);
        paper=new BitmapShader(pattern,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT);
        shaderMatrix.setScale((float)(step/128),(float)(step/128));shaderMatrix.postTranslate((float)(-((camera.x%step)+step)%step),(float)(-((camera.y%step)+step)%step));paper.setLocalMatrix(shaderMatrix);
    }
    private void drawPhase(Canvas canvas,Phase source,Camera current){
        Camera old=source.camera;float ratio=(float)(current.scale/old.scale),tx=current.sx(old.x)-(old.width/2f)*ratio,ty=current.sy(old.y)-(old.height/2f)*ratio;
        for(Tile tile:source.tiles){if(tile.bitmap==null&&(source!=phase||previous!=null))continue;float l=tx+tile.left*ratio,t=ty+tile.top*ratio,r=tx+tile.right*ratio,b=ty+tile.bottom*ratio;if(r<0||l>current.width||b<0||t>current.height)continue;
            canvas.save();canvas.clipRect(l,t,r,b);if(tile.bitmap!=null)canvas.drawBitmap(tile.bitmap,null,new RectF(l,t,r,b),bitmapPaint);
            double wl=old.wx(tile.left),wt=old.wy(tile.top),wr=old.wx(tile.right),wb=old.wy(tile.bottom);
            canvas.translate(current.width/2f,current.height/2f);canvas.scale((float)current.scale,(float)current.scale);
            for(Op op:ops){if(op.seq<=tile.sequence||!op.intersects(wl,wt,wr,wb))continue;
                if(op.stroke==null){overlayPaint.setShader(paper);overlayPaint.setStyle(Paint.Style.STROKE);overlayPaint.setStrokeCap(Paint.Cap.ROUND);overlayPaint.setStrokeWidth((float)(op.radius*2));canvas.drawLine((float)(op.ax-current.x),(float)(op.ay-current.y),(float)(op.bx-current.x),(float)(op.by-current.y),overlayPaint);}
                else {Ink.Stroke s=op.stroke;if(op.path==null)op.path=strokePath(s);overlayPaint.setShader(null);overlayPaint.setColor(s.color);overlayPaint.setStyle(Paint.Style.STROKE);overlayPaint.setStrokeWidth(s.width);overlayPaint.setStrokeCap(Paint.Cap.ROUND);overlayPaint.setStrokeJoin(Paint.Join.ROUND);canvas.save();canvas.translate((float)(s.x-current.x),(float)(s.y-current.y));if(s.points.length==3){overlayPaint.setStyle(Paint.Style.FILL);canvas.drawCircle(s.points[0],s.points[1],s.width/2,overlayPaint);}else canvas.drawPath(op.path,overlayPaint);canvas.restore();}
            }canvas.restore();
        }
    }
    private void pump(){
        if(closed||busy||phase==null||document==null)return;Phase target=phase;Tile tile=null;
        for(int n=0;n<target.tiles.length;n++){int i=(priority+n)%target.tiles.length;Tile candidate=target.tiles[i];if(candidate.bitmap==null||candidate.sequence<candidate.dirty){tile=candidate;priority=(i+1)%target.tiles.length;break;}}
        if(tile==null){previous=null;pruneOps();return;}busy=true;
        Tile selected=tile;long seq=sequence,generation=epoch;Ink.Stroke[] strokes=document.strokes.toArray(new Ink.Stroke[0]);
        worker.execute(()->{
            Bitmap bitmap=null;Throwable failure=null;
            try{bitmap=raster(target.camera,selected,strokes);}catch(Throwable e){failure=e;}
            Bitmap result=bitmap;Throwable error=failure;
            main.post(()->{busy=false;if(closed){if(result!=null)result.recycle();return;}
                if(error==null&&phase==target&&epoch==generation){selected.bitmap=result;selected.sequence=seq;invalidate.run();pruneOps();}
                else {if(result!=null)result.recycle();if(error!=null)android.util.Log.e("DraftRaster","Tile render failed",error);}
                if(error==null)pump();
            });
        });
    }
    private void pruneOps(){
        if(ops.isEmpty()||phase==null)return;
        Iterator<Op> iterator=ops.iterator();while(iterator.hasNext()){Op op=iterator.next();boolean needed=false;
            for(Tile tile:phase.tiles){Camera c=phase.camera;if(op.intersects(c.wx(tile.left),c.wy(tile.top),c.wx(tile.right),c.wy(tile.bottom))&&(tile.bitmap==null||tile.sequence<op.seq)){needed=true;break;}}
            if(!needed&&previous!=null)for(Tile tile:previous.tiles){Camera c=previous.camera;if(tile.bitmap!=null&&tile.sequence<op.seq&&op.intersects(c.wx(tile.left),c.wy(tile.top),c.wx(tile.right),c.wy(tile.bottom))){needed=true;break;}}
            if(!needed)iterator.remove();
        }
    }
    static Path strokePath(Ink.Stroke stroke){
        StrokeData data=stroke.data();Path path=new Path();if(data.segments<1)return path;
        double[] ranges=stroke.sections;int count=ranges==null?2:ranges.length;
        for(int k=0;k<count;k+=2){double a=ranges==null?0:ranges[k],b=ranges==null?data.segments:ranges[k+1];path.moveTo(data.coordinate(a,0),data.coordinate(a,1));for(int i=(int)Math.floor(a)+1;i<b;i++)path.lineTo(data.points[i*3],data.points[i*3+1]);path.lineTo(data.coordinate(b,0),data.coordinate(b,1));}
        return path;
    }
    private Bitmap raster(Camera camera,Tile tile,Ink.Stroke[] strokes){
        Bitmap bitmap=Bitmap.createBitmap(tile.right-tile.left,tile.bottom-tile.top,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(0xfffafaf7);canvas.translate(-tile.left,-tile.top);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(0xffb9c4bc);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(camera.density*1.6f);canvas.drawPoints(camera.grid,0,camera.gridCount,paint);
        double l=camera.wx(tile.left),t=camera.wy(tile.top),r=camera.wx(tile.right),b=camera.wy(tile.bottom);
        for(Ink.Stroke s:strokes){if(!s.intersects(l,t,r,b))continue;canvas.save();canvas.translate(camera.sx(s.x),camera.sy(s.y));canvas.scale((float)camera.scale,(float)camera.scale);paint.setShader(null);paint.setColor(s.color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(s.width);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);if(s.points.length==3){paint.setStyle(Paint.Style.FILL);canvas.drawCircle(s.points[0],s.points[1],s.width/2,paint);}else {Path path=paths.get(s);if(path==null){path=strokePath(s);paths.put(s,path);}canvas.drawPath(path,paint);}canvas.restore();}return bitmap;
    }
    private boolean hasAllTiles(Phase p){for(Tile tile:p.tiles)if(tile.bitmap==null)return false;return true;}
    boolean ready(){if(phase==null||busy)return false;for(Tile tile:phase.tiles)if(tile.bitmap==null||tile.sequence<tile.dirty)return false;return true;}
    void close(){closed=true;worker.shutdownNow();phase=previous=null;ops.clear();}
}
