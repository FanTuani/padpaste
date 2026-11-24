package io.github.fantuani.draftcanvas;

import android.app.*;
import android.os.*;
import android.graphics.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Headless real-cache benchmark with main-loop turns between samples and a fully warmed initial page. */
@android.annotation.TargetApi(29)
public final class CanvasPerfRunner extends Instrumentation {
    static void run(Instrumentation runner,Bundle options){
        Bundle result=new Bundle();AtomicReference<CanvasView> holder=new AtomicReference<>();GpuTarget hardware=null;
        try{
            boolean dense=options!=null&&"true".equals(options.getString("dense"));int count=dense?500:1800;
            runner.runOnMainSync(()->{
                Ink d=new Ink();CanvasView v=new CanvasView(runner.getTargetContext(),d);v.testAsync=true;v.layout(0,0,2880,1740);d.view.x=1440;d.view.y=870;
                for(int n=0;n<count;n++){int samples=dense?240:60;float[] p=new float[samples*3];for(int i=0;i<samples;i++){double a=i*(dense?.13:.14);p[i*3]=(float)((dense?300:17)*Math.cos(a)+i*(dense?.3:.25));p[i*3+1]=(float)((dense?210:14)*Math.sin(a));p[i*3+2]=1;}d.strokes.add(new Ink.Stroke(d.nextId(),0xff243137,5,dense?1100+(n%23)*2:70+(n%60)*45,dense?850+(n%17)*2:330+(n/60)*38,p));}
                holder.set(v);
            });
            CanvasView view=holder.get();AtomicReference<GpuTarget> gpuHolder=new AtomicReference<>();runner.runOnMainSync(()->gpuHolder.set(new GpuTarget(2880,1740)));hardware=gpuHolder.get();GpuTarget target=hardware;
            long deadline=SystemClock.uptimeMillis()+30000;boolean[] ready={false};
            while(!ready[0]){runner.runOnMainSync(()->{Canvas c=target.node.beginRecording(2880,1740);view.draw(c);target.node.endRecording();ready[0]=view.rasterReady();});runner.runOnMainSync(()->target.present().close());if(SystemClock.uptimeMillis()>deadline)throw new AssertionError("Raster cache warmup timed out");Thread.sleep(10);}
            int frames=dense?24:80;long[] model=new long[frames],draw=new long[frames],gpu=new long[frames];runner.runOnMainSync(()->view.ink.begin());
            for(int i=0;i<frames;i++){
                int index=i;double x=dense?850+i*25:180+i*31;
                runner.runOnMainSync(()->{long t=System.nanoTime();view.eraseWorld(x-(dense?25:31),850,x,850,85);model[index]=System.nanoTime()-t;t=System.nanoTime();Canvas c=target.node.beginRecording(2880,1740);view.draw(c);target.node.endRecording();draw[index]=System.nanoTime()-t;});
                long t=System.nanoTime();runner.runOnMainSync(()->target.present().close());gpu[i]=System.nanoTime()-t;Thread.sleep(8);
            }
            runner.runOnMainSync(()->view.ink.commit());int remaining=view.ink.strokes.size();Arrays.sort(model);Arrays.sort(draw);Arrays.sort(gpu);int mid=frames/2,high=Math.min(frames-1,(int)(frames*.95)),last=frames-1;
            result.putString("stream",String.format(Locale.ROOT,"async strokes=%d overlapping=%s frames=%d model p50=%.2f p95=%.2f max=%.2f ms; record p50=%.2f p95=%.2f max=%.2f ms; GPU present p50=%.2f p95=%.2f max=%.2f ms; remaining=%d\n",count,dense,frames,model[mid]/1e6,model[high]/1e6,model[last]/1e6,draw[mid]/1e6,draw[high]/1e6,draw[last]/1e6,gpu[mid]/1e6,gpu[high]/1e6,gpu[last]/1e6,remaining));
            runner.finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream","FAIL: "+e);runner.finish(Activity.RESULT_CANCELED,result);}
        finally{if(hardware!=null){GpuTarget target=hardware;runner.runOnMainSync(target::close);}if(holder.get()!=null)runner.runOnMainSync(()->holder.get().closeRaster());}
    }
}
