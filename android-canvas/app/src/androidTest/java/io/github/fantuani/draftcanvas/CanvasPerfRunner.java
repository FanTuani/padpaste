package io.github.fantuani.draftcanvas;

import android.app.*;
import android.os.*;
import android.graphics.*;
import java.util.*;

/** Headless dense-page CPU benchmark; never launches or focuses a test Activity. */
@android.annotation.TargetApi(29)
public final class CanvasPerfRunner extends Instrumentation {
    static void run(Instrumentation runner,Bundle options){Bundle result=new Bundle();try{runner.runOnMainSync(()->{
        Ink d=new Ink();CanvasView view=new CanvasView(runner.getTargetContext(),d);view.layout(0,0,2880,1740);d.view.x=1440;d.view.y=870;
        boolean dense=options!=null&&"true".equals(options.getString("dense"));int count=dense?500:1800;
        for(int n=0;n<count;n++){
            int samples=dense?240:60;float[] p=new float[samples*3];
            for(int i=0;i<samples;i++){double a=i*(dense?.13:.14);p[i*3]=(float)((dense?300:17)*Math.cos(a)+i*(dense?.3:.25));p[i*3+1]=(float)((dense?210:14)*Math.sin(a));p[i*3+2]=1;}
            d.strokes.add(new Ink.Stroke(d.nextId(),0xff243137,5,dense?1100+(n%23)*2:70+(n%60)*45,dense?850+(n%17)*2:330+(n/60)*38,p));
        }
        Bitmap bitmap=Bitmap.createBitmap(2880,1740,Bitmap.Config.ARGB_8888);Canvas target=new Canvas(bitmap);view.draw(target);
        GpuTarget hardware=new GpuTarget(2880,1740);android.graphics.RenderNode node=hardware.node;
        Canvas warm=node.beginRecording(2880,1740);view.draw(warm);node.endRecording();hardware.present().close();
        int frames=dense?24:80,completed=0;long started=System.nanoTime();
        long[] model=new long[frames],draw=new long[frames],gpu=new long[frames];d.begin();
        for(int i=0;i<frames;i++){
            double x=dense?850+i*25:180+i*31;long t=System.nanoTime();d.erase(x-(dense?25:31),850,x,850,85);model[i]=System.nanoTime()-t;
            view.invalidateErasedArea((float)(x-(dense?25:31)-85),765,(float)(x+85),935);
            t=System.nanoTime();Canvas record=node.beginRecording(2880,1740);view.draw(record);node.endRecording();draw[i]=System.nanoTime()-t;
            t=System.nanoTime();android.media.Image image=hardware.present();image.close();gpu[i]=System.nanoTime()-t;completed++;if(System.nanoTime()-started>15_000_000_000L)break;
        }
        d.commit();bitmap.recycle();hardware.close();Arrays.sort(model,0,completed);Arrays.sort(draw,0,completed);Arrays.sort(gpu,0,completed);int mid=completed/2,high=Math.min(completed-1,(int)(completed*.95)),last=completed-1;
        result.putString("stream",String.format(Locale.ROOT,"strokes=%d overlapping=%s frames=%d model p50=%.2f p95=%.2f max=%.2f ms; record p50=%.2f p95=%.2f max=%.2f ms; GPU present p50=%.2f p95=%.2f max=%.2f ms; remaining=%d\n",count,dense,completed,model[mid]/1e6,model[high]/1e6,model[last]/1e6,draw[mid]/1e6,draw[high]/1e6,draw[last]/1e6,gpu[mid]/1e6,gpu[high]/1e6,gpu[last]/1e6,d.strokes.size()));
    });runner.finish(Activity.RESULT_OK,result);}catch(Throwable e){result.putString("stream","FAIL: "+e);runner.finish(Activity.RESULT_CANCELED,result);}}
}
