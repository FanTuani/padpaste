package io.github.fantuani.draftcanvas;

import android.app.*;
import android.os.*;
import android.graphics.*;
import java.util.*;

/** Headless dense-page CPU benchmark; never launches or focuses a test Activity. */
public final class CanvasPerfRunner extends Instrumentation {
    static void run(Instrumentation runner){Bundle result=new Bundle();try{runner.runOnMainSync(()->{
        Ink d=new Ink();CanvasView view=new CanvasView(runner.getTargetContext(),d);view.layout(0,0,2880,1740);d.view.x=1440;d.view.y=870;
        for(int n=0;n<1800;n++){float[] p=new float[180];for(int i=0;i<60;i++){double a=i*.14;p[i*3]=(float)(17*Math.cos(a)+i*.25);p[i*3+1]=(float)(14*Math.sin(a));p[i*3+2]=1;}d.strokes.add(new Ink.Stroke(d.nextId(),0xff243137,5,70+(n%60)*45,330+(n/60)*38,p));}
        Bitmap bitmap=Bitmap.createBitmap(2880,1740,Bitmap.Config.ARGB_8888);Canvas target=new Canvas(bitmap);view.draw(target);
        long[] model=new long[80],draw=new long[80];d.begin();
        for(int i=0;i<80;i++){double x=180+i*31;long t=System.nanoTime();d.erase(x-31,850,x,850,85);model[i]=System.nanoTime()-t;t=System.nanoTime();view.draw(target);draw[i]=System.nanoTime()-t;}
        d.commit();bitmap.recycle();Arrays.sort(model);Arrays.sort(draw);
        result.putString("stream",String.format(Locale.ROOT,"DENSE1800 frames=80 model p50=%.2f p95=%.2f max=%.2f ms; software draw p50=%.2f p95=%.2f max=%.2f ms; remaining=%d\n",model[40]/1e6,model[76]/1e6,model[79]/1e6,draw[40]/1e6,draw[76]/1e6,draw[79]/1e6,d.strokes.size()));
    });runner.finish(Activity.RESULT_OK,result);}catch(Throwable e){result.putString("stream","FAIL: "+e);runner.finish(Activity.RESULT_CANCELED,result);}}
}
