package io.github.fantuani.draftcanvas;

import java.util.Arrays;

/** Spatial smoothing only: no event buffering or delayed stroke finalization. */
final class StrokeGeometry {
    private StrokeGeometry() {}
    static float[] smooth(float[] input,float width) {
        if(input.length<=3)return input;
        float spacing=Math.max(.65f,width*.25f);
        Floats anchors=new Floats(input.length);anchors.add(input[0],input[1],input[2]);
        // Reject subpixel direction noise before calculating the pressure outline normals.
        for(int i=3;i<input.length-3;i+=3){
            if(Math.hypot(input[i]-anchors.x(),input[i+1]-anchors.y())>=spacing)
                anchors.add(input[i],input[i+1],input[i+2]);
        }
        int end=input.length-3;
        if(Math.hypot(input[end]-anchors.x(),input[end+1]-anchors.y())>.001)
            anchors.add(input[end],input[end+1],input[end+2]);
        else anchors.values[anchors.size-1]=input[end+2];
        if(anchors.size<=3)return anchors.array();
        Floats out=new Floats(anchors.size*2);out.add(input[0],input[1],input[2]);
        float[] a=anchors.values;
        // Midpoint quadratic segments have matching tangents at each join.
        // Preserve the exact pen-down/up coordinates, including the live tip.
        for(int i=3;i<anchors.size;i+=3){
            float cx=a[i-3],cy=a[i-2],cp=a[i-1];
            float ex=(cx+a[i])*.5f,ey=(cy+a[i+1])*.5f,ep=(cp+a[i+2])*.5f;
            quadratic(out,cx,cy,cp,ex,ey,ep,spacing*.5f);
        }
        quadratic(out,a[anchors.size-3],a[anchors.size-2],a[anchors.size-1],
            a[anchors.size-3],a[anchors.size-2],a[anchors.size-1],spacing*.5f);
        return simplify(out.array(),Math.max(.08f,width*.025f));
    }
    /** Ramer-Douglas-Peucker with a 2.5% line-width error budget, after curve smoothing. */
    static float[] simplify(float[] points,float tolerance){
        int count=points.length/3;if(count<3)return points;
        boolean[] keep=new boolean[count];keep[0]=keep[count-1]=true;int[] stack=new int[count*2];int size=0;stack[size++]=0;stack[size++]=count-1;
        double limit=tolerance*tolerance;
        while(size>0){int end=stack[--size],start=stack[--size],farthest=-1;double best=limit;
            for(int i=start+1;i<end;i++){double distance=Eraser.distanceSquared(points[i*3],points[i*3+1],points[start*3],points[start*3+1],points[end*3],points[end*3+1]);if(distance>best){best=distance;farthest=i;}}
            if(farthest!=-1){keep[farthest]=true;stack[size++]=start;stack[size++]=farthest;stack[size++]=farthest;stack[size++]=end;}
        }
        int remaining=0;for(boolean value:keep)if(value)remaining++;if(remaining==count)return points;
        float[] result=new float[remaining*3];int index=0;for(int i=0;i<count;i++)if(keep[i]){System.arraycopy(points,i*3,result,index,3);index+=3;}return result;
    }
    private static void quadratic(Floats out,float cx,float cy,float cp,float ex,float ey,float ep,float step){
        float sx=out.x(),sy=out.y(),sp=out.p();
        int count=Math.max(1,Math.min(128,(int)Math.ceil((Math.hypot(cx-sx,cy-sy)+Math.hypot(ex-cx,ey-cy))/step)));
        for(int j=1;j<=count;j++){
            float t=(float)j/count;
            float x=curve(sx,cx,ex,t),y=curve(sy,cy,ey,t);
            if(Math.hypot(x-out.x(),y-out.y())>.001)out.add(x,y,curve(sp,cp,ep,t));
        }
    }
    private static float curve(float a,float b,float c,float t){
        float left=a+(b-a)*t,right=b+(c-b)*t;return left+(right-left)*t;
    }
    private static final class Floats {
        float[] values;int size;
        Floats(int capacity){values=new float[Math.max(12,capacity)];}
        void add(float x,float y,float p){if(size+3>values.length)values=Arrays.copyOf(values,values.length*2);values[size++]=x;values[size++]=y;values[size++]=p;}
        float x(){return values[size-3];}float y(){return values[size-2];}float p(){return values[size-1];}
        float[] array(){return Arrays.copyOf(values,size);}
    }
}
