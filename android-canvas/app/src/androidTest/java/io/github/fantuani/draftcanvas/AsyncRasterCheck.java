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
            CanvasView view=holder.get();AtomicReference<GpuTarget> gpuHolder=new AtomicReference<>();runner.runOnMainSync(()->gpuHolder.set(new GpuTarget(2880,1740)));hardware=gpuHolder.get();GpuTarget target=hardware;waitReady(runner,view,target);
            runner.runOnMainSync(()->{view.ink.begin();view.eraseWorld(512,1080,512,1120,40);view.ink.commit();record(view,target);});
            Bitmap image=copy(runner,target);check(image.getPixel(512,1100)!=0xff243137,"immediate erase preview missing");check(image.getPixel(460,1100)==0xff243137,"preview erased outside brush");image.recycle();
            runner.runOnMainSync(()->{view.setPenColor(0xffcf4941);pen(view,MotionEvent.ACTION_DOWN,480,1100);pen(view,MotionEvent.ACTION_MOVE,512,1100);pen(view,MotionEvent.ACTION_UP,544,1100);record(view,target);});
            image=copy(runner,target);check(image.getPixel(512,1100)==0xffcf4941,"fresh ink lost above pending erasure");image.recycle();waitReady(runner,view,target);
            image=copy(runner,target);check(image.getPixel(512,1100)==0xffcf4941,"background tile replaced fresher ink");image.recycle();
            runner.runOnMainSync(view::undo);waitReady(runner,view,target);image=copy(runner,target);check(image.getPixel(512,1100)!=0xffcf4941&&image.getPixel(512,1100)!=0xff243137,"undo fresh ink did not preserve earlier erasure");image.recycle();
            runner.runOnMainSync(view::undo);waitReady(runner,view,target);image=copy(runner,target);check(image.getPixel(512,1100)==0xff243137,"async erase undo missing");image.recycle();
            runner.runOnMainSync(()->{view.ink.view.x+=100;view.refresh();});waitReady(runner,view,target);image=copy(runner,target);check(image.getPixel(360,1100)==0xff243137&&image.getPixel(600,1100)!=0xff243137,"async camera transform incorrect");image.recycle();
            runner.runOnMainSync(() -> {
                view.ink.view.x = 1440;
                view.ink.add(new Ink.Stroke(view.ink.nextId(), 0xffcf4941, 30, 3100, 850,
                        new float[]{0,0,1}));
                view.ink.add(new Ink.Stroke(view.ink.nextId(), 0xff3169ca, 30, 1450, 1950,
                        new float[]{0,0,1}));
                view.refresh();
            });
            waitReady(runner, view, target);
            // Only two offscreen dots need bitmaps; let that prefetched work finish before the drag.
            Thread.sleep(270);
            runner.runOnMainSync(() -> {
                finger(view, MotionEvent.ACTION_DOWN, 2500, 850);
                finger(view, MotionEvent.ACTION_MOVE, 1700, 850);
                record(view, target);
            });
            image = copy(runner, target);
            check(image.getPixel(2300,850) == 0xffcf4941, "offscreen ink missing before horizontal lift");
            image.recycle();
            runner.runOnMainSync(() -> {
                finger(view, MotionEvent.ACTION_MOVE, 650, 850);
                record(view, target);
            });
            image = copy(runner, target);
            check(image.getPixel(1250,850) == 0xffcf4941, "ink disappeared later in the same drag");
            image.recycle();
            runner.runOnMainSync(() -> {
                finger(view, MotionEvent.ACTION_UP, 650, 850);
                view.ink.view.x = 1440;
                finger(view, MotionEvent.ACTION_DOWN, 1450, 1450);
                finger(view, MotionEvent.ACTION_MOVE, 1450, 950);
                record(view, target);
            });
            image = copy(runner, target);
            check(image.getPixel(1450,1450) == 0xff3169ca, "offscreen ink missing before vertical lift");
            image.recycle();
            runner.runOnMainSync(() -> {
                finger(view, MotionEvent.ACTION_UP, 1450, 950);
                view.ink.add(new Ink.Stroke(view.ink.nextId(), 0xff3169ca, 15, 8000, 9000,
                        new float[]{0,0,1}));
                view.ink.view.x = 8000;
                view.ink.view.y = 9000;
                view.ink.view.scale = 4;
                view.refresh();
                record(view, target);
            });
            image = copy(runner, target);
            check(image.getPixel(1448,878) == 0xfffafaf7, "unloaded region did not retain the paper color");
            image.recycle();
            waitReady(runner, view, target);
            image = copy(runner, target);
            check(image.getPixel(1440,870) == 0xff3169ca, "unloaded region did not resolve to the rendered ink");
            image.recycle();
            checkSelectionMove(runner, view, target);
            Thread.sleep(260); // Let the palm-rejection interval after the test pen gesture expire.
            checkDoubleTapZoom(runner, view, target, .5, 1);
            checkDoubleTapZoom(runner, view, target, 1, 2);
            checkDoubleTapZoom(runner, view, target, 2, 1);
            checkZoomPrewarm(runner);
            checkFastZoomFallback(runner);
            runner.runOnMainSync(view::closeRaster);result.putString("stream","PASS: async immediate erase, fresh ink order, stale tile replacement, pen undo, erase undo, camera transform, horizontal and vertical drag preview, normal paper while zoom tiles load, selection release before and after tile rebuild\n");runner.finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream","FAIL: "+e);runner.finish(Activity.RESULT_CANCELED,result);}
        finally{if(hardware!=null){GpuTarget target=hardware;runner.runOnMainSync(target::close);}if(holder.get()!=null)runner.runOnMainSync(holder.get()::closeRaster);}
    }

    private static void checkFastZoomFallback(Instrumentation runner) throws Exception {
        Ink ink = new Ink();
        int red = 0xffcf4941, blue = 0xff3169ca;
        ink.add(new Ink.Stroke(ink.nextId(), red, 60, 250, 250, new float[]{0,0,1}));
        ink.add(new Ink.Stroke(ink.nextId(), blue, 60, 780, 250, new float[]{0,0,1}));
        AsyncInkTiles cache = new AsyncInkTiles(() -> {});
        Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        try {
            AsyncInkTiles.Camera first = new AsyncInkTiles.Camera(512, 512, 250, 250, 1, 2);
            AsyncInkTiles.Camera middle = new AsyncInkTiles.Camera(512, 512, 650, 250, .6, 2);
            AsyncInkTiles.Camera last = new AsyncInkTiles.Camera(512, 512, 650, 250, .22, 2);
            waitForInk(runner, cache, canvas, image, ink, first, 256, red);
            waitForInk(runner, cache, canvas, image, ink, middle, 334, blue);
            runner.runOnMainSync(() -> {
                image.eraseColor(Color.WHITE);
                cache.draw(canvas, ink, last, true, false, (c, l, t, r, b) -> {});
                check(image.getPixel(168, 256) == red, "rapid zoom lost previously visible ink");
                check(image.getPixel(285, 256) == blue, "rapid zoom lost newly revealed ink");

                image.eraseColor(Color.WHITE);
                cache.draw(canvas, ink, last, false, false, (c, l, t, r, b) -> {});
                check(image.getPixel(168, 256) == red && image.getPixel(285, 256) == blue,
                        "pinch release must preserve both regions before fresh tiles arrive");
            });
        } finally {
            runner.runOnMainSync(cache::close);
            image.recycle();
        }
    }

    private static void waitForInk(Instrumentation runner, AsyncInkTiles cache, Canvas canvas, Bitmap image,
                                   Ink ink, AsyncInkTiles.Camera camera, int x, int color) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 4000;
        do {
            runner.runOnMainSync(() -> {
                image.eraseColor(Color.WHITE);
                cache.draw(canvas, ink, camera, true, false, (c, l, t, r, b) -> {});
            });
            if (image.getPixel(x, 256) == color) return;
            Thread.sleep(8);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("timed out waiting for ink at " + camera.scale);
    }

    private static void checkZoomPrewarm(Instrumentation runner) throws Exception {
        Ink ink = new Ink();
        int expectedInk = 0xff3169ca;
        int previewInk = 0xffcf4941;
        ink.add(new Ink.Stroke(ink.nextId(), expectedInk, 30, 250, 250, new float[]{0,0,1}));
        AsyncInkTiles cache = new AsyncInkTiles(() -> {});
        AsyncInkTiles.Camera target = new AsyncInkTiles.Camera(512, 512, 250, 250, 1, 2);
        AsyncInkTiles.Camera animated = new AsyncInkTiles.Camera(512, 512, 250, 250, .75, 2);
        Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        try {
            runner.runOnMainSync(() -> cache.prepareZoom(ink, target));
            long deadline = SystemClock.uptimeMillis() + 4000;
            do {
                runner.runOnMainSync(() -> {
                    image.eraseColor(Color.WHITE);
                    cache.drawZoom(canvas, animated, () -> canvas.drawColor(previewInk));
                });
                if (image.getPixel(256, 256) == expectedInk) break;
                Thread.sleep(8);
            } while (SystemClock.uptimeMillis() < deadline);
            check(image.getPixel(256, 256) == expectedInk,
                    "the final-resolution tile must be available before finishing the animation");
            runner.runOnMainSync(() -> {
                cache.endZoom(true);
                image.eraseColor(Color.WHITE);
                cache.draw(canvas, ink, target, false, false, (c, l, t, r, b) -> {});
            });
            check(image.getPixel(256, 256) == expectedInk, "final view must reuse the prefetched tile");
        } finally {
            runner.runOnMainSync(cache::close);
            image.recycle();
        }
    }

    private static void checkDoubleTapZoom(Instrumentation runner, CanvasView view, GpuTarget target,
                                           double scale, double expectedScale)
            throws Exception {
        float tapX = 1000, tapY = 700;
        double[] center = new double[2];
        final int zoomInk = 0xff8d399e;
        runner.runOnMainSync(() -> {
            Viewport viewport = view.ink.view;
            viewport.scale = scale;
            center[0] = viewport.worldX(tapX, view.getWidth());
            center[1] = viewport.worldY(tapY, view.getHeight());
            view.ink.add(new Ink.Stroke(view.ink.nextId(), zoomInk, 30,
                    center[0], center[1], new float[]{0,0,1}));
            view.refresh();
            finger(view, MotionEvent.ACTION_DOWN, tapX, tapY);
            finger(view, MotionEvent.ACTION_UP, tapX, tapY);
            check(viewport.scale == scale, "a single tap changed the zoom");
            finger(view, MotionEvent.ACTION_DOWN, tapX, tapY);
            finger(view, MotionEvent.ACTION_UP, tapX, tapY);
        });
        int[] pixel = new int[2];
        runner.runOnMainSync(() -> {
            Viewport viewport = view.ink.view;
            pixel[0] = (int) Math.round((center[0] - viewport.x) * viewport.scale + view.getWidth() / 2.0);
            pixel[1] = (int) Math.round((center[1] - viewport.y) * viewport.scale + view.getHeight() / 2.0);
            record(view, target);
        });
        Bitmap frame = copy(runner, target);
        try {
            check(frame.getPixel(pixel[0], pixel[1]) == zoomInk, "zoom preview lost the ink under the tap");
        } finally {
            frame.recycle();
        }
        long deadline = SystemClock.uptimeMillis() + 2000;
        boolean[] finished = {false};
        do {
            runner.runOnMainSync(() -> finished[0] = view.ink.view.scale == expectedScale);
            if (finished[0]) break;
            Thread.sleep(16);
        } while (SystemClock.uptimeMillis() < deadline);
        runner.runOnMainSync(() -> {
            check(finished[0], "double tap did not finish at " + Math.round(expectedScale * 100) + "%");
            check(Math.abs(view.ink.view.x - center[0]) < .001
                    && Math.abs(view.ink.view.y - center[1]) < .001, "double tap did not center its world point");
        });
    }

    private static void checkSelectionMove(Instrumentation runner, CanvasView view, GpuTarget target)
            throws Exception {
        runner.runOnMainSync(() -> {
            view.ink.clear();
            view.ink.view.x = 1440;
            view.ink.view.y = 870;
            view.ink.view.scale = 1;
            view.ink.add(new Ink.Stroke(view.ink.nextId(), 0xffcf4941, 30, 315, 410, new float[]{0,0,1}));
            view.ink.add(new Ink.Stroke(view.ink.nextId(), 0xff3169ca, 30, 470, 410, new float[]{0,0,1}));
            view.refresh();
        });
        waitReady(runner, view, target);
        runner.runOnMainSync(() -> {
            view.armSelection();
            pen(view, MotionEvent.ACTION_DOWN, 280, 380);
            pen(view, MotionEvent.ACTION_MOVE, 360, 450);
            pen(view, MotionEvent.ACTION_UP, 360, 450);
            pen(view, MotionEvent.ACTION_DOWN, 315, 410);
            pen(view, MotionEvent.ACTION_MOVE, 815, 410);
            record(view, target);
        });
        checkMovedPixels(runner, target, "during drag");
        runner.runOnMainSync(() -> {
            pen(view, MotionEvent.ACTION_UP, 815, 410);
            // Record in the same UI turn, before the tile worker can publish a new bitmap.
            record(view, target);
        });
        checkMovedPixels(runner, target, "on the first frame after pen-up");
        waitReady(runner, view, target);
        checkMovedPixels(runner, target, "after tile rebuild");
    }

    private static void checkMovedPixels(Instrumentation runner, GpuTarget target, String stage) {
        Bitmap image = copy(runner, target);
        try {
            int origin = image.getPixel(315, 410);
            int moved = image.getPixel(815, 410);
            check(Color.red(origin) - Color.blue(origin) < 40, "old ink reappeared " + stage);
            check(Color.red(moved) - Color.blue(moved) > 80, "moved ink disappeared " + stage);
            check(image.getPixel(470, 410) == 0xff3169ca, "other ink changed " + stage);
        } finally {
            image.recycle();
        }
    }
    private static Bitmap copy(Instrumentation runner,GpuTarget target){AtomicReference<Bitmap> result=new AtomicReference<>();runner.runOnMainSync(()->result.set(target.copy()));return result.get();}
    private static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);}
    private static void record(CanvasView view,GpuTarget target){Canvas c=target.node.beginRecording(view.getWidth(),view.getHeight());view.draw(c);target.node.endRecording();}
    private static void waitReady(Instrumentation runner,CanvasView view,GpuTarget target)throws Exception{long deadline=SystemClock.uptimeMillis()+15000;boolean[] ready={false};do{runner.runOnMainSync(()->{record(view,target);ready[0]=view.rasterReady();});runner.runOnMainSync(()->target.present().close());if(SystemClock.uptimeMillis()>deadline)throw new AssertionError("async tiles timed out");Thread.sleep(8);}while(!ready[0]);}
    private static void finger(CanvasView view, int action, float x, float y) {
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0;
        pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = x;
        coordinates.y = y;
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 1,
                new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{coordinates},
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }
    private static void pen(CanvasView view,int action,float x,float y){MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;p.toolType=MotionEvent.TOOL_TYPE_STYLUS;MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=x;c.y=y;c.pressure=1;long t=SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(t,t,action,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);view.dispatchTouchEvent(e);e.recycle();}
}
