package io.github.fantuani.draftcanvas;

import android.annotation.TargetApi;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.*;

/** Public HardwareRenderer/ImageReader path, kept outside the production app. */
@TargetApi(29)
final class GpuTarget implements AutoCloseable {
    final RenderNode node=new RenderNode("offscreen-ink");
    private final ImageReader reader;private final HardwareRenderer renderer=new HardwareRenderer();
    GpuTarget(int width,int height){reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2,HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);node.setPosition(0,0,width,height);renderer.setSurface(reader.getSurface());renderer.setContentRoot(node);renderer.setOpaque(false);}
    Image present(){renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();Image image=reader.acquireNextImage();if(image==null)throw new IllegalStateException("No rendered image");return image;}
    Bitmap copy(){try(Image image=present();HardwareBuffer buffer=image.getHardwareBuffer()){
        Bitmap gpu=Bitmap.wrapHardwareBuffer(buffer,null);if(gpu==null)throw new IllegalStateException("No GPU bitmap");try{return gpu.copy(Bitmap.Config.ARGB_8888,false);}finally{gpu.recycle();}
    }}
    public void close(){renderer.destroy();reader.close();node.discardDisplayList();}
}
