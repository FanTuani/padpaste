package io.github.fantuani.draftcanvas;

import java.util.*;

/** Clips a flattened ink centerline against the swept circle, retaining independent fragments. */
final class Eraser {
    private Eraser() {}
    static double pressureScale(float pressure){return .5+1.5*Math.max(0,Math.min(1,pressure));}
    static List<float[]> cut(float[] points,double ax,double ay,double bx,double by,double radius){
        List<float[]> pieces=new ArrayList<>();
        if(points.length==3){if(distance(points[0],points[1],ax,ay,bx,by)>radius)return null;return pieces;}
        List<Float> current=new ArrayList<>();boolean changed=false;
        for(int i=3;i<points.length;i+=3){
            double x=points[i-3],y=points[i-2],dx=points[i]-x,dy=points[i+1]-y;
            double[] interval=interval(x,y,dx,dy,ax,ay,bx,by,radius);
            if(interval==null){append(current,points,i-3,0);append(current,points,i-3,1);continue;}
            changed=true;double lo=interval[0],hi=interval[1];
            if(lo>1e-8){append(current,points,i-3,0);append(current,points,i-3,lo);}
            flush(current,pieces);
            if(hi<1-1e-8){append(current,points,i-3,hi);append(current,points,i-3,1);}
        }
        flush(current,pieces);return changed?pieces:null;
    }
    private static void append(List<Float> out,float[] p,int i,double t){
        float x=(float)(p[i]+(p[i+3]-p[i])*t),y=(float)(p[i+1]+(p[i+4]-p[i+1])*t);
        if(out.size()>=3&&Math.hypot(x-out.get(out.size()-3),y-out.get(out.size()-2))<1e-6)return;
        out.add(x);out.add(y);out.add(1f);
    }
    private static void flush(List<Float> current,List<float[]> pieces){
        if(current.size()>=6){float[] p=new float[current.size()];for(int i=0;i<p.length;i++)p[i]=current.get(i);pieces.add(p);}current.clear();
    }
    private static double[] interval(double x,double y,double dx,double dy,double ax,double ay,double bx,double by,double r){
        double[] result={Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY};
        circle(result,x-ax,y-ay,dx,dy,r);circle(result,x-bx,y-by,dx,dy,r);
        double vx=bx-ax,vy=by-ay,len=Math.hypot(vx,vy);
        if(len>1e-9){
            vx/=len;vy/=len;double[] rect={0,1};
            if(slab(rect,(x-ax)*vx+(y-ay)*vy,dx*vx+dy*vy,0,len)
                &&slab(rect,-(x-ax)*vy+(y-ay)*vx,-dx*vy+dy*vx,-r,r))merge(result,rect[0],rect[1]);
        }
        return result[1]-result[0]>1e-9?result:null;
    }
    private static void circle(double[] result,double x,double y,double dx,double dy,double r){
        double a=dx*dx+dy*dy,c=x*x+y*y-r*r;
        if(a<1e-18){if(c<0)merge(result,0,1);return;}
        double b=x*dx+y*dy,discriminant=b*b-a*c;if(discriminant<=0)return;
        double root=Math.sqrt(discriminant);merge(result,Math.max(0,(-b-root)/a),Math.min(1,(-b+root)/a));
    }
    private static void merge(double[] result,double lo,double hi){if(hi>lo){result[0]=Math.min(result[0],lo);result[1]=Math.max(result[1],hi);}}
    private static boolean slab(double[] range,double position,double delta,double lo,double hi){
        if(Math.abs(delta)<1e-12)return position>=lo&&position<=hi;
        double a=(lo-position)/delta,b=(hi-position)/delta;range[0]=Math.max(range[0],Math.min(a,b));range[1]=Math.min(range[1],Math.max(a,b));return range[1]>range[0];
    }
    private static double distance(double x,double y,double ax,double ay,double bx,double by){double dx=bx-ax,dy=by-ay,den=dx*dx+dy*dy,t=den==0?0:Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/den));return Math.hypot(x-ax-t*dx,y-ay-t*dy);}
}
