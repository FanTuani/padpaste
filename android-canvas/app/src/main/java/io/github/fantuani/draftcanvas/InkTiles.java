package io.github.fantuani.draftcanvas;

import android.annotation.TargetApi;
import android.graphics.*;

/** Viewport-only GPU layers. Camera changes rerecord vectors, so zoom never scales old pixels. */
@TargetApi(29)
final class InkTiles {
    interface Drawer { void draw(Canvas canvas,int left,int top,int right,int bottom); }
    private static final int SIZE=256;
    private RenderNode[] nodes;private boolean[] dirty;private int columns,rows,width,height;
    private double x=Double.NaN,y,scale;
    void invalidateAll(){if(dirty!=null)java.util.Arrays.fill(dirty,true);}
    void invalidate(float left,float top,float right,float bottom){
        if(dirty==null)return;
        for(int row=Math.max(0,(int)Math.floor(top/SIZE));row<rows&&row*SIZE<=bottom;row++)
            for(int col=Math.max(0,(int)Math.floor(left/SIZE));col<columns&&col*SIZE<=right;col++)dirty[row*columns+col]=true;
    }
    void draw(Canvas canvas,int w,int h,double cameraX,double cameraY,double zoom,Drawer drawer){
        if(nodes==null||width!=w||height!=h){
            clear();width=w;height=h;columns=(w+SIZE-1)/SIZE;rows=(h+SIZE-1)/SIZE;nodes=new RenderNode[rows*columns];dirty=new boolean[nodes.length];
            for(int i=0;i<nodes.length;i++){RenderNode node=new RenderNode("ink-tile");int left=i%columns*SIZE,top=i/columns*SIZE;node.setPosition(left,top,Math.min(w,left+SIZE),Math.min(h,top+SIZE));node.setClipToBounds(true);node.setUseCompositingLayer(true,null);nodes[i]=node;dirty[i]=true;}
        }
        if(x!=cameraX||y!=cameraY||scale!=zoom){x=cameraX;y=cameraY;scale=zoom;invalidateAll();}
        for(int i=0;i<nodes.length;i++){
            RenderNode node=nodes[i];int left=i%columns*SIZE,top=i/columns*SIZE,right=Math.min(w,left+SIZE),bottom=Math.min(h,top+SIZE);
            if(dirty[i]){Canvas record=node.beginRecording(right-left,bottom-top);try{record.translate(-left,-top);drawer.draw(record,left,top,right,bottom);}finally{node.endRecording();}dirty[i]=false;}
            canvas.drawRenderNode(node);
        }
    }
    void clear(){if(nodes!=null)for(RenderNode node:nodes)node.discardDisplayList();nodes=null;dirty=null;x=Double.NaN;}
}
