package io.github.fantuani.draftcanvas;

import android.app.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import java.util.concurrent.atomic.AtomicReference;

/** Tests stale-tile preview, edit ordering, undo and camera transforms through the production async cache. */
@android.annotation.TargetApi(29)
final class AsyncRasterCheck {
    static void run(Instrumentation runner){
        Bundle result=new Bundle();AtomicReference<CanvasView> holder=new AtomicReference<>();GpuTarget hardware=null;
        try{
            runner.runOnMainSync(()->{CanvasView v=new CanvasView(runner.getTargetContext(),new Ink());v.testAsync=true;android.widget.FrameLayout root=new android.widget.FrameLayout(runner.getTargetContext());root.addView(v,new android.widget.FrameLayout.LayoutParams(-1,-1));root.measure(View.MeasureSpec.makeMeasureSpec(2880,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1740,View.MeasureSpec.EXACTLY));root.layout(0,0,2880,1740);v.ink.view.x=1440;v.ink.view.y=870;v.ink.add(new Ink.Stroke(v.ink.nextId(),0xff243137,5,450,1100,new float[]{0,0,1,200,0,1}));holder.set(v);});
            CanvasView view=holder.get();hardware=new GpuTarget(2880,1740);GpuTarget target=hardware;waitReady(runner,view,target);
            runner.runOnMainSync(()->{view.ink.begin();view.eraseWorld(512,1080,512,1120,40);view.ink.commit();record(view,target);});
            Bitmap image=target.copy();check(image.getPixel(512,1100)!=0xff243137,"immediate erase preview missing");check(image.getPixel(460,1100)==0xff243137,"preview erased outside brush");image.recycle();
            runner.runOnMainSync(()->{view.setPenColor(0xffcf4941);pen(view,MotionEvent.ACTION_DOWN,480,1100);pen(view,MotionEvent.ACTION_MOVE,512,1100);pen(view,MotionEvent.ACTION_UP,544,1100);record(view,target);});
            image=target.copy();check(image.getPixel(512,1100)==0xffcf4941,"fresh ink lost above pending erasure");image.recycle();waitReady(runner,view,target);
            image=target.copy();check(image.getPixel(512,1100)==0xffcf4941,"background tile replaced fresher ink");image.recycle();
            runner.runOnMainSync(view::undo);waitReady(runner,view,target);image=target.copy();check(image.getPixel(512,1100)!=0xffcf4941&&image.getPixel(512,1100)!=0xff243137,"undo fresh ink did not preserve earlier erasure");image.recycle();
            runner.runOnMainSync(view::undo);waitReady(runner,view,target);image=target.copy();check(image.getPixel(512,1100)==0xff243137,"async erase undo missing");image.recycle();
            runner.runOnMainSync(()->{view.ink.view.x+=100;view.refresh();});waitReady(runner,view,target);image=target.copy();check(image.getPixel(360,1100)==0xff243137&&image.getPixel(600,1100)!=0xff243137,"async camera transform incorrect");image.recycle();
            runner.runOnMainSync(view::closeRaster);result.putString("stream","PASS: async immediate erase, fresh ink order, stale tile replacement, pen undo, erase undo, camera transform\n");runner.finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream","FAIL: "+e);runner.finish(Activity.RESULT_CANCELED,result);}
        finally{if(hardware!=null)hardware.close();if(holder.get()!=null)runner.runOnMainSync(holder.get()::closeRaster);}
    }
    private static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);}
    private static void record(CanvasView view,GpuTarget target){Canvas c=target.node.beginRecording(view.getWidth(),view.getHeight());view.draw(c);target.node.endRecording();}
    private static void waitReady(Instrumentation runner,CanvasView view,GpuTarget target)throws Exception{long deadline=SystemClock.uptimeMillis()+15000;boolean[] ready={false};do{runner.runOnMainSync(()->{record(view,target);ready[0]=view.rasterReady();});target.present().close();if(SystemClock.uptimeMillis()>deadline)throw new AssertionError("async tiles timed out");Thread.sleep(8);}while(!ready[0]);}
    private static void pen(CanvasView view,int action,float x,float y){MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;p.toolType=MotionEvent.TOOL_TYPE_STYLUS;MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=x;c.y=y;c.pressure=1;long t=SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(t,t,action,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);view.dispatchTouchEvent(e);e.recycle();}
}
