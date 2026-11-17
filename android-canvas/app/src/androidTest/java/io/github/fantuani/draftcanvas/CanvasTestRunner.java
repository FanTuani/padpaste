package io.github.fantuani.draftcanvas;

import android.app.*;
import android.os.*;
import android.content.Intent;
import android.view.*;

/** Exercises real View dispatch with distinct synthetic pen and finger devices. */
public final class CanvasTestRunner extends Instrumentation {
    private long time;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void event(CanvasView view,int tool,int action,float... xy){
        int n=xy.length/2;MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[n];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[n];
        for(int i=0;i<n;i++){properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=tool;coords[i]=new MotionEvent.PointerCoords();coords[i].x=xy[i*2];coords[i].y=xy[i*2+1];coords[i].pressure=.65f;coords[i].size=.1f;}
        MotionEvent e=MotionEvent.obtain(time,time+=16,action,n,properties,coords,0,0,1,1,0,0,tool==MotionEvent.TOOL_TYPE_FINGER?InputDevice.SOURCE_TOUCHSCREEN:InputDevice.SOURCE_STYLUS,0);view.dispatchTouchEvent(e);e.recycle();
    }
    private void pen(CanvasView v,int action,float x,float y){event(v,MotionEvent.TOOL_TYPE_STYLUS,action,x,y);}
    @Override public void onStart(){Bundle result=new Bundle();try{
        MainActivity activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        runOnMainSync(()->{
            CanvasView v=activity.canvas;Ink d=v.ink;time=SystemClock.uptimeMillis();
            pen(v,MotionEvent.ACTION_DOWN,440,520);pen(v,MotionEvent.ACTION_MOVE,520,550);pen(v,MotionEvent.ACTION_UP,600,580);check(d.strokes.size()==1,"stylus stroke missing");
            pen(v,MotionEvent.ACTION_DOWN,700,400);pen(v,MotionEvent.ACTION_MOVE,800,500);pen(v,MotionEvent.ACTION_CANCEL,800,500);check(d.strokes.size()==1,"canceled pen was retained");
            time+=1000;double x=d.view.x;event(v,MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_DOWN,400,700);event(v,MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_MOVE,450,700);event(v,MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_UP,450,700);check(d.strokes.size()==1&&d.view.x<x,"finger must pan without ink");
            d.view.x=d.view.y=0;d.view.scale=1;v.tool(CanvasView.SELECT);pen(v,MotionEvent.ACTION_DOWN,400,480);pen(v,MotionEvent.ACTION_MOVE,640,620);pen(v,MotionEvent.ACTION_UP,640,620);check(d.selected.size()==1,"rectangle selection missed stroke");
            double original=d.strokes.get(0).x;pen(v,MotionEvent.ACTION_DOWN,520,550);pen(v,MotionEvent.ACTION_MOVE,560,600);pen(v,MotionEvent.ACTION_UP,560,600);check(Math.abs(d.strokes.get(0).x-original-40)<.01,"selected stroke did not move");
            d.copy();d.paste(v.getWidth(),v.getHeight());check(d.strokes.size()==2,"paste did not clone ink");d.undo();check(d.strokes.size()==1,"undo paste failed");d.redo();check(d.strokes.size()==2,"redo paste failed");
            v.tool(CanvasView.PEN);d.view.x=d.view.y=0;d.view.scale=1;time+=1000;
            event(v,MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_DOWN,300,300);
            event(v,MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_POINTER_DOWN|(1<<8),300,300,500,300);
            event(v,MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_MOVE,250,300,550,300);
            check(Math.abs(d.view.scale-1.5)<.01,"two finger zoom failed");
            event(v,MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_POINTER_UP|(1<<8),250,300,550,300);event(v,MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_UP,250,300);
            v.refresh();
        });
        result.putString("stream","PASS: pen ink, canceled ink rejection, finger pan, rectangle select, move, copy/paste, undo/redo, two-finger zoom\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,result);}}
}
