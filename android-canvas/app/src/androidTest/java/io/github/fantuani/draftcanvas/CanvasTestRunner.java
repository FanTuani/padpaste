package io.github.fantuani.draftcanvas;

import android.app.*;
import android.os.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.*;

/** Exercises real View dispatch with distinct synthetic pen and finger devices. */
public final class CanvasTestRunner extends Instrumentation {
    private long time;private float pressure=.65f;private boolean perf;private Bundle options;
    @Override public void onCreate(Bundle args){super.onCreate(args);options=args;perf=args!=null&&"true".equals(args.getString("perf"));start();}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void event(CanvasView view,int tool,int action,float... xy){
        int n=xy.length/2;MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[n];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[n];
        for(int i=0;i<n;i++){properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=tool;coords[i]=new MotionEvent.PointerCoords();coords[i].x=xy[i*2];coords[i].y=xy[i*2+1];coords[i].pressure=pressure;coords[i].size=.1f;}
        MotionEvent e=MotionEvent.obtain(time,time+=16,action,n,properties,coords,0,0,1,1,0,0,tool==MotionEvent.TOOL_TYPE_FINGER?InputDevice.SOURCE_TOUCHSCREEN:InputDevice.SOURCE_STYLUS,0);if(action==MotionEvent.ACTION_HOVER_ENTER||action==MotionEvent.ACTION_HOVER_EXIT||action==MotionEvent.ACTION_HOVER_MOVE)view.onHoverEvent(e);else view.dispatchTouchEvent(e);e.recycle();
    }
    private void pen(CanvasView v,int action,float x,float y){event(v,MotionEvent.TOOL_TYPE_STYLUS,action,x,y);}

    private void checkLiveTip(CanvasView view) {
        Bitmap bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888);
        float originalWidth = view.width;
        view.width = 8;
        try {
            Canvas frame = new Canvas(bitmap);
            frame.translate(-400, -480);
            pen(view, MotionEvent.ACTION_DOWN, 440, 520);
            view.draw(frame);
            check(bitmap.getPixel(40, 40) == view.color, "pen-down dot must appear before pen-up");
            pen(view, MotionEvent.ACTION_MOVE, 480, 580);
            pen(view, MotionEvent.ACTION_MOVE, 520, 560);
            pen(view, MotionEvent.ACTION_MOVE, 560, 540);
            view.draw(frame);
            check(bitmap.getPixel(160, 60) == view.color, "live smooth path must reach the latest sample");
            check(view.ink.strokes.isEmpty(), "live path must stay outside the committed ink");
            pen(view, MotionEvent.ACTION_CANCEL, 560, 540);
            view.draw(frame);
            check(bitmap.getPixel(160, 60) != view.color, "cancel must remove the live path");
        } finally {
            view.width = originalWidth;
            bitmap.recycle();
        }
    }

    private void beginPinch(CanvasView view, double startScale) {
        view.ink.view.scale = startScale;
        int finger = MotionEvent.TOOL_TYPE_FINGER;
        int secondPointer = 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        event(view, finger, MotionEvent.ACTION_DOWN, 300, 300);
        event(view, finger, MotionEvent.ACTION_POINTER_DOWN | secondPointer, 300, 300, 500, 300);
    }

    private void movePinch(CanvasView view, float radius) {
        event(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_MOVE, 410 - radius, 320, 410 + radius, 320);
    }

    private void movePinchSlowly(CanvasView view, int from, int to) {
        float step = to > from ? .5f : -.5f;
        for (int i = 1; i <= 2 * Math.abs(to - from); i++) movePinch(view, from + i * step);
    }

    private void endPinch(CanvasView view, float radius) {
        int finger = MotionEvent.TOOL_TYPE_FINGER;
        int secondPointer = 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        event(view, finger, MotionEvent.ACTION_POINTER_UP | secondPointer, 410 - radius, 320, 410 + radius, 320);
        event(view, finger, MotionEvent.ACTION_UP, 410 - radius, 320);
    }

    private void checkPinchSnap(CanvasView view, double startScale, int snapRadius, int holdRadius,
                                int releaseRadius, boolean gradual) {
        beginPinch(view, startScale);
        Viewport viewport = view.ink.view;
        double anchorX = viewport.worldX(400, view.getWidth());
        double anchorY = viewport.worldY(300, view.getHeight());
        if (gradual) movePinchSlowly(view, 100, snapRadius);
        else movePinch(view, snapRadius);
        check(viewport.scale == 1, "pinch must snap to exactly 100%");
        check(Math.abs(viewport.worldX(410, view.getWidth()) - anchorX) < .001
                && Math.abs(viewport.worldY(320, view.getHeight()) - anchorY) < .001,
                "snap must preserve the canvas point under the moving pinch center");
        movePinchSlowly(view, snapRadius, holdRadius);
        check(viewport.scale == 1, "small finger movement must keep the pinch snapped");
        if (gradual) movePinchSlowly(view, holdRadius, releaseRadius);
        else movePinch(view, releaseRadius);
        check(Math.abs(viewport.scale - startScale * releaseRadius / 100) < .001,
                "continued pinch must release the snap at the requested scale");
        endPinch(view, releaseRadius);
    }

    @android.annotation.TargetApi(29)
    private android.graphics.Bitmap snapshot(CanvasView v){
        try(GpuTarget target=new GpuTarget(v.getWidth(),v.getHeight())){
            android.graphics.Canvas c=target.node.beginRecording(v.getWidth(),v.getHeight());v.draw(c);target.node.endRecording();return target.copy();
        }
    }
    @Override public void onStart(){if(options!=null&&options.getString("draftTest")!=null){DraftPersistenceCheck.run(this,options.getString("draftTest"));return;}if(options!=null&&"true".equals(options.getString("asyncTest"))){AsyncRasterCheck.run(this);return;}if(perf){CanvasPerfRunner.run(this,options);return;}Bundle result=new Bundle();try{
        runOnMainSync(()->{
            CanvasView v=new CanvasView(getTargetContext(),new Ink());
            android.widget.FrameLayout root=new android.widget.FrameLayout(getTargetContext());root.addView(v,new android.widget.FrameLayout.LayoutParams(-1,-1));
            root.measure(View.MeasureSpec.makeMeasureSpec(2880,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1740,View.MeasureSpec.EXACTLY));root.layout(0,0,2880,1740);
            Ink d=v.ink;time=SystemClock.uptimeMillis();
            checkLiveTip(v);
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
            checkPinchSnap(v, .89, 105, 112, 124, false);
            checkPinchSnap(v, 1.12, 95, 90, 81, false);
            checkPinchSnap(v, .89, 105, 112, 124, true);
            checkPinchSnap(v, 1.12, 95, 90, 81, true);
            beginPinch(v, 1);
            movePinchSlowly(v, 100, 102);
            check(Math.abs(d.view.scale - 1.02) < .001, "a new pinch at 100% must be free to zoom away");
            endPinch(v, 102);
            d.strokes.clear();d.view.x=v.getWidth()/2.0;d.view.y=v.getHeight()/2.0;d.view.scale=1;
            int inkColor=0xff243137;d.add(new Ink.Stroke(d.nextId(),inkColor,20,500,800,new float[]{0,0,1,300,0,1}));
            d.begin();d.erase(600,807,600,807,5);d.commit();
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(v.getWidth(),v.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            v.draw(new android.graphics.Canvas(bitmap));
            check(bitmap.getPixel(600,807)!=inkColor,"circle failed to subtract thick ink edge");
            check(bitmap.getPixel(593,800)==inkColor&&bitmap.getPixel(593,808)!=inkColor,"erased endpoint must retain a rounded cap");
            check(bitmap.getPixel(550,800)==inkColor,"local eraser removed the rest of the stroke");
            d.begin();d.erase(700,807,700,807,5);d.commit();v.draw(new android.graphics.Canvas(bitmap));
            check(bitmap.getPixel(600,807)!=inkColor&&bitmap.getPixel(700,807)!=inkColor,"incremental render lost a previous or current cut");
            check(bitmap.getPixel(693,800)==inkColor&&bitmap.getPixel(693,808)!=inkColor,"second erasure did not retain rounded caps");
            d.undo();v.draw(new android.graphics.Canvas(bitmap));check(bitmap.getPixel(600,807)!=inkColor&&bitmap.getPixel(700,807)==inkColor,"undo did not preserve earlier cut");
            d.undo();v.draw(new android.graphics.Canvas(bitmap));check(bitmap.getPixel(600,807)==inkColor,"undo did not restore erased ink");bitmap.recycle();
            d.strokes.clear();v.tool(CanvasView.PEN);pressure=.1f;time+=1000;
            pen(v,MotionEvent.ACTION_DOWN,500,1000);pressure=1;pen(v,MotionEvent.ACTION_MOVE,650,1000);pen(v,MotionEvent.ACTION_UP,800,1000);
            check(d.strokes.size()==1,"pressure-varying pen stroke missing");for(int i=2;i<d.strokes.get(0).points.length;i+=3)check(d.strokes.get(0).points[i]==1,"writing must ignore pressure");
            v.setEraserSize(40);v.tool(CanvasView.ERASE);pressure=.1f;
            pen(v,MotionEvent.ACTION_DOWN,650,960);pen(v,MotionEvent.ACTION_MOVE,650,1040);pen(v,MotionEvent.ACTION_UP,650,1040);
            android.graphics.Bitmap erased=android.graphics.Bitmap.createBitmap(v.getWidth(),v.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);v.draw(new android.graphics.Canvas(erased));
            check(erased.getPixel(650,1000)!=inkColor,"swept eraser skipped ink between events");check(erased.getPixel(675,1000)==inkColor,"light eraser removed too much ink");erased.recycle();
            d.undo();pressure=1;pen(v,MotionEvent.ACTION_DOWN,650,960);pen(v,MotionEvent.ACTION_MOVE,650,1040);pen(v,MotionEvent.ACTION_UP,650,1040);
            erased=android.graphics.Bitmap.createBitmap(v.getWidth(),v.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);v.draw(new android.graphics.Canvas(erased));
            check(erased.getPixel(675,1000)!=inkColor,"firm pressure must enlarge erasure");check(erased.getPixel(750,1000)==inkColor,"firm eraser removed unrelated ink");erased.recycle();
            java.util.List<Ink.Stroke> before=new java.util.ArrayList<>(d.strokes);pen(v,MotionEvent.ACTION_DOWN,530,1000);pen(v,MotionEvent.ACTION_MOVE,550,1000);pen(v,MotionEvent.ACTION_CANCEL,550,1000);check(before.equals(d.strokes),"canceled partial erase did not restore exact fragments");
            if(Build.VERSION.SDK_INT>=29){
                d.strokes.clear();v.tool(CanvasView.PEN);d.add(new Ink.Stroke(d.nextId(),inkColor,5,450,1100,new float[]{0,0,1,200,0,1}));v.refresh();
                android.graphics.Bitmap image=snapshot(v);check(image.getPixel(512,1100)==inkColor,"GPU tile initial ink missing");image.recycle();
                v.tool(CanvasView.ERASE);image=snapshot(v);image.recycle();v.setEraserSize(40);pressure=1;
                pen(v,MotionEvent.ACTION_DOWN,512,1080);pen(v,MotionEvent.ACTION_MOVE,512,1120);pen(v,MotionEvent.ACTION_UP,512,1120);
                image=snapshot(v);check(image.getPixel(512,1100)!=inkColor,"tile boundary kept erased ink");check(image.getPixel(460,1100)==inkColor&&image.getPixel(560,1100)==inkColor,"tile update changed ink outside eraser");image.recycle();
                v.undo();image=snapshot(v);check(image.getPixel(512,1100)==inkColor,"GPU tile undo failed");image.recycle();
            }
            d.strokes.clear();
            v.tool(CanvasView.PEN);
            d.add(new Ink.Stroke(d.nextId(), inkColor, 5, 500, 700, new float[]{0,0,1,100,0,1}));
            pressure = .65f;
            event(v, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_HOVER_ENTER, 475, 675);
            event(v, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_HOVER_EXIT, 470, 670);
            check(v.menuAnchor().x == 475 && v.menuAnchor().y == 675, "menu lost the last hover position");
            int[] selectionTaps = {0};
            v.setOnClickListener(view -> selectionTaps[0]++);
            time += 1000;
            event(v, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 520, 700);
            event(v, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_UP, 520, 700);
            check(selectionTaps[0] == 0, "finger tap must not show a menu");

            v.armSelection();
            event(v, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_HOVER_ENTER, 470, 665);
            event(v, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_HOVER_MOVE, 620, 730);
            check(v.selectionArmed() && !v.hasSelectionBox() && d.selected.isEmpty(),
                    "hover must leave selection armed for the next contact");
            pen(v, MotionEvent.ACTION_DOWN, 520, 700);
            pen(v, MotionEvent.ACTION_UP, 521, 700);
            check(v.selectionArmed() && selectionTaps[0] == 0, "a tap must not create a rectangle");

            pen(v, MotionEvent.ACTION_DOWN, 480, 680);
            pen(v, MotionEvent.ACTION_MOVE, 620, 720);
            pen(v, MotionEvent.ACTION_UP, 620, 720);
            check(v.hasSelectionBox() && !v.selectionArmed() && d.selected.size() == 1
                    && d.strokes.size() == 1 && selectionTaps[0] == 0,
                    "pen-up must preserve the selection without opening a menu");
            pen(v, MotionEvent.ACTION_DOWN, 610, 715);
            pen(v, MotionEvent.ACTION_UP, 610, 715);
            check(selectionTaps[0] == 1 && v.selectionMenuAnchor().x == 610,
                    "a pen tap in the drawn box must open its menu at the tap");

            event(v, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_HOVER_EXIT, 610, 715);
            time += 1000;
            event(v, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 610, 715);
            event(v, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_UP, 610, 715);
            check(selectionTaps[0] == 1 && v.hasSelectionBox(), "finger tap must keep the box without opening its menu");
            pen(v, MotionEvent.ACTION_DOWN, 610, 715);
            pen(v, MotionEvent.ACTION_MOVE, 650, 745);
            pen(v, MotionEvent.ACTION_UP, 650, 745);
            check(d.strokes.get(0).x == 540 && d.strokes.get(0).y == 730 && selectionTaps[0] == 1,
                    "dragging must move selected ink without invoking the menu");
            pen(v, MotionEvent.ACTION_DOWN, 650, 745);
            pen(v, MotionEvent.ACTION_UP, 650, 745);
            check(selectionTaps[0] == 2, "the box must be tappable at its moved location");

            v.armSelection();
            pen(v, MotionEvent.ACTION_DOWN, 950, 930);
            pen(v, MotionEvent.ACTION_MOVE, 800, 800);
            pen(v, MotionEvent.ACTION_UP, 800, 800);
            check(v.hasSelectionBox() && d.selected.isEmpty() && selectionTaps[0] == 2,
                    "an empty box must remain available without auto-opening the menu");
            pen(v, MotionEvent.ACTION_DOWN, 850, 850);
            pen(v, MotionEvent.ACTION_UP, 850, 850);
            check(selectionTaps[0] == 3, "an empty box must be tappable for paste");
            v.clearSelection();
            v.overview();
            check(Math.abs(d.view.x - 590) < .1 && Math.abs(d.view.y - 730) < .1,
                    "overview must center existing ink");
            v.refresh();
        });
        result.putString("stream","PASS: pen ink, canceled ink rejection, finger pan, rectangle select, move, copy/paste, undo/redo, two-finger zoom and 100% snap, exact thick-edge erasure pixels, partial erase, eraser pressure, fixed-width ink, erase undo/cancel, GPU tile edge/undo pixels, hover anchor, armed selection, pen tap and drag, silent finger taps\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,result);}}
}
