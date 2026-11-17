package io.github.fantuani.draftcanvas;

import java.util.*;

/** Allocation-light clipping against the swept eraser circle. */
final class Eraser {
    private Eraser() {}
    static double pressureScale(float pressure){return pressureScale(pressure,1);}
    static double pressureScale(float pressure,float sensitivity){
        double p=Math.max(0,Math.min(1,pressure));
        return .5+1.5*Math.pow(p,1/Math.max(.25f,sensitivity));
    }
    static boolean intersects(float[] points,double ax,double ay,double bx,double by,double radius){
        if(points.length==3)return distance(points[0],points[1],ax,ay,bx,by)<=radius;
        Capsule capsule=new Capsule(ax,ay,bx,by,radius);
        for(int i=3;i<points.length;i+=3)if(capsule.hit(points[i-3],points[i-2],points[i],points[i+1]))return true;
        return false;
    }
    static List<float[]> cut(float[] points,double ax,double ay,double bx,double by,double radius){
        if(points.length==3)return distance(points[0],points[1],ax,ay,bx,by)>radius?null:new ArrayList<>();
        Capsule capsule=new Capsule(ax,ay,bx,by,radius);int first=3;
        while(first<points.length&&!capsule.hit(points[first-3],points[first-2],points[first],points[first+1]))first+=3;
        if(first==points.length)return null;
        List<float[]> pieces=new ArrayList<>();Floats current=new Floats(points.length);
        current.put(points,first);
        for(int i=first;i<points.length;i+=3){
            if(!capsule.hit(points[i-3],points[i-2],points[i],points[i+1])){current.append(points,i-3,0);current.append(points,i-3,1);continue;}
            if(capsule.lo>1e-8){current.append(points,i-3,0);current.append(points,i-3,capsule.lo);}
            current.flush(pieces);
            if(capsule.hi<1-1e-8){current.append(points,i-3,capsule.hi);current.append(points,i-3,1);}
        }
        current.flush(pieces);return pieces;
    }
    private static final class Floats {
        float[] values;int size;
        Floats(int capacity){values=new float[Math.max(12,capacity)];}
        void put(float[] p,int count){System.arraycopy(p,0,values,0,count);size=count;}
        void append(float[] p,int i,double t){
            float x=(float)(p[i]+(p[i+3]-p[i])*t),y=(float)(p[i+1]+(p[i+4]-p[i+1])*t);
            if(size>=3&&Math.abs(x-values[size-3])+Math.abs(y-values[size-2])<1e-6)return;
            if(size+3>values.length)values=Arrays.copyOf(values,values.length*2);
            values[size++]=x;values[size++]=y;values[size++]=1;
        }
        void flush(List<float[]> pieces){if(size>=6)pieces.add(Arrays.copyOf(values,size));size=0;}
    }
    private static final class Capsule {
        final double ax,ay,bx,by,r,len,vx,vy,left,top,right,bottom;
        double lo,hi,rlo,rhi;
        Capsule(double ax,double ay,double bx,double by,double radius){this.ax=ax;this.ay=ay;this.bx=bx;this.by=by;r=radius;len=Math.hypot(bx-ax,by-ay);vx=len>1e-9?(bx-ax)/len:1;vy=len>1e-9?(by-ay)/len:0;left=Math.min(ax,bx)-r;right=Math.max(ax,bx)+r;top=Math.min(ay,by)-r;bottom=Math.max(ay,by)+r;}
        boolean hit(double x,double y,double nx,double ny){
            if(Math.max(x,nx)<left||Math.min(x,nx)>right||Math.max(y,ny)<top||Math.min(y,ny)>bottom)return false;
            double dx=nx-x,dy=ny-y;lo=Double.POSITIVE_INFINITY;hi=Double.NEGATIVE_INFINITY;
            circle(x-ax,y-ay,dx,dy);circle(x-bx,y-by,dx,dy);
            if(len>1e-9){rlo=0;rhi=1;if(slab((x-ax)*vx+(y-ay)*vy,dx*vx+dy*vy,0,len)&&slab(-(x-ax)*vy+(y-ay)*vx,-dx*vy+dy*vx,-r,r))merge(rlo,rhi);}
            return hi-lo>1e-9;
        }
        void circle(double x,double y,double dx,double dy){
            double a=dx*dx+dy*dy,c=x*x+y*y-r*r;if(a<1e-18){if(c<0)merge(0,1);return;}
            double b=x*dx+y*dy,disc=b*b-a*c;if(disc<=0)return;double root=Math.sqrt(disc);merge(Math.max(0,(-b-root)/a),Math.min(1,(-b+root)/a));
        }
        void merge(double a,double b){if(b>a){lo=Math.min(lo,a);hi=Math.max(hi,b);}}
        boolean slab(double position,double delta,double a,double b){if(Math.abs(delta)<1e-12)return position>=a&&position<=b;double x=(a-position)/delta,y=(b-position)/delta;rlo=Math.max(rlo,Math.min(x,y));rhi=Math.min(rhi,Math.max(x,y));return rhi>rlo;}
    }
    static double distance(double x,double y,double ax,double ay,double bx,double by){double dx=bx-ax,dy=by-ay,den=dx*dx+dy*dy,t=den==0?0:Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/den));return Math.hypot(x-ax-t*dx,y-ay-t*dy);}
}
