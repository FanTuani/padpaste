package io.github.fantuani.draftcanvas;

import java.util.Arrays;

/** Immutable centerline BVH with visible intervals in segment-index coordinates. */
final class StrokeData {
    private static final ThreadLocal<Sweep> SWEEP = ThreadLocal.withInitial(Sweep::new);
    final float[] points;final Node root;final int segments;
    StrokeData(float[] points){this.points=points;segments=points.length/3-1;root=build(0,segments);}
    static final class Node {
        final int start,end;final float left,top,right,bottom;final Node a,b;Object renderPath;
        Node(int start,int end,float left,float top,float right,float bottom,Node a,Node b){this.start=start;this.end=end;this.left=left;this.top=top;this.right=right;this.bottom=bottom;this.a=a;this.b=b;}
    }
    private Node build(int start,int end){
        if(end-start<=16){float l=points[start*3],t=points[start*3+1],r=l,b=t;for(int i=start+1;i<=end;i++){l=Math.min(l,points[i*3]);r=Math.max(r,points[i*3]);t=Math.min(t,points[i*3+1]);b=Math.max(b,points[i*3+1]);}return new Node(start,end,l,t,r,b,null,null);}
        int mid=(start+end)/2;Node a=build(start,mid),b=build(mid,end);return new Node(start,end,Math.min(a.left,b.left),Math.min(a.top,b.top),Math.max(a.right,b.right),Math.max(a.bottom,b.bottom),a,b);
    }
    static int first(double[] ranges,double t){int lo=0,hi=ranges.length/2;while(lo<hi){int mid=(lo+hi)/2;if(ranges[mid*2+1]<=t)lo=mid+1;else hi=mid;}return lo*2;}
    static boolean visible(double[] ranges,double a,double b){if(ranges==null)return true;int i=first(ranges,a);return i<ranges.length&&ranges[i]<=b;}
    double[] erase(double[] previous,double ax,double ay,double bx,double by,double radius){
        return erase(previous, ax, ay, bx, by, radius, 0, false, 0);
    }
    double[] erase(double[] previous,double ax,double ay,double bx,double by,double radius,
                   float baseRadius,boolean variableWidth,float maxRadius){
        Sweep sweep = SWEEP.get();
        Intervals hits = sweep.hits;
        hits.size = 0;
        Capsule capsule = sweep.capsule;
        capsule.set(ax, ay, bx, by, radius + maxRadius);
        query(root, previous, capsule, hits, radius, variableWidth ? baseRadius : 0);
        if (hits.size == 0) return previous;
        double[] visible=previous==null?new double[]{0,segments}:previous;
        Intervals result = sweep.result;
        result.size = 0;
        boolean changed=false;int h=0;
        for(int i=0;i<visible.length;i+=2){double cursor=visible[i],end=visible[i+1];while(h<hits.size&&hits.values[h+1]<=cursor)h+=2;int j=h;
            while(j<hits.size&&hits.values[j]<end){double a=Math.max(cursor,hits.values[j]),b=Math.min(end,hits.values[j+1]);if(b>a+1e-9){changed=true;if(a>cursor+1e-9)result.add(cursor,a);cursor=Math.max(cursor,b);}if(cursor>=end-1e-9)break;j+=2;}
            if(cursor<end-1e-9)result.add(cursor,end);
        }return changed?Arrays.copyOf(result.values,result.size):previous;
    }
    private void query(Node node,double[] ranges,Capsule c,Intervals hits,double radius,float baseRadius){
        if(node.right<c.left||node.left>c.right||node.bottom<c.top||node.top>c.bottom||!visible(ranges,node.start,node.end))return;
        // The capsule is convex. A bounding box contained by it needs no per-segment math.
        if (node.end - node.start > 3 && c.includes(node.left, node.top, radius * radius)
                && c.includes(node.right, node.top, radius * radius)
                && c.includes(node.right, node.bottom, radius * radius)
                && c.includes(node.left, node.bottom, radius * radius)) {
            hits.add(node.start, node.end);
            return;
        }
        if(node.a!=null){query(node.a,ranges,c,hits,radius,baseRadius);query(node.b,ranges,c,hits,radius,baseRadius);return;}
        if (ranges == null) {
            querySegments(node.start, node.end, c, hits, radius, baseRadius);
            return;
        }
        // A previous sweep often leaves only a small portion of a leaf visible.
        int cursor = node.start;
        for (int i = first(ranges, cursor); i < ranges.length && ranges[i] < node.end; i += 2) {
            int start = Math.max(cursor, (int) Math.floor(ranges[i]));
            int end = Math.min(node.end, (int) Math.ceil(ranges[i + 1]));
            querySegments(start, end, c, hits, radius, baseRadius);
            cursor = Math.max(cursor, end);
        }
    }

    private void querySegments(int start, int end, Capsule capsule, Intervals hits, double radius, float baseRadius) {
        for (int i = start; i < end; i++) {
            int p = i * 3;
            boolean hit = baseRadius == 0
                    ? capsule.hit(points[p], points[p + 1], points[p + 3], points[p + 4])
                    : capsule.hitVariable(points[p], points[p + 1], points[p + 3], points[p + 4],
                            radius + baseRadius * points[p + 2],
                            baseRadius * (points[p + 5] - points[p + 2]));
            if (hit) hits.add(i + capsule.lo, i + capsule.hi);
        }
    }

    float coordinate(double t,int axis){int i=Math.min(segments-1,(int)t);if(i<0)return points[axis];double f=t-i;return (float)(points[i*3+axis]+(points[(i+1)*3+axis]-points[i*3+axis])*f);}
    private static final class Intervals {
        double[] values=new double[16];int size;
        void add(double a,double b){if(b-a<1e-9)return;if(size>=2&&a<=values[size-1]+1e-9){values[size-1]=Math.max(values[size-1],b);return;}if(size+2>values.length)values=Arrays.copyOf(values,values.length*2);values[size++]=a;values[size++]=b;}
    }
    private static final class Sweep {
        final Intervals hits = new Intervals(), result = new Intervals();
        final Capsule capsule = new Capsule();
    }

    private static final class Capsule {
        double ax,ay,bx,by,r,rSquared,len,vx,vy,left,top,right,bottom;double lo,hi,rlo,rhi;
        void set(double ax,double ay,double bx,double by,double r){this.ax=ax;this.ay=ay;this.bx=bx;this.by=by;this.r=r;rSquared=r*r;len=Math.hypot(bx-ax,by-ay);vx=len>1e-9?(bx-ax)/len:1;vy=len>1e-9?(by-ay)/len:0;left=Math.min(ax,bx)-r;right=Math.max(ax,bx)+r;top=Math.min(ay,by)-r;bottom=Math.max(ay,by)+r;}
        private boolean includes(double x, double y, double radiusSquared) {
            double along = Math.max(0, Math.min(len, x * vx + y * vy));
            double px = x - along * vx, py = y - along * vy;
            return px * px + py * py < radiusSquared;
        }

        boolean hit(double x,double y,double nx,double ny) {
            if (Math.max(x,nx)<left||Math.min(x,nx)>right||Math.max(y,ny)<top||Math.min(y,ny)>bottom) return false;
            double dx=nx-x,dy=ny-y;
            lo=Double.POSITIVE_INFINITY;hi=Double.NEGATIVE_INFINITY;
            circle(x-ax,y-ay,dx,dy);
            if (len>1e-9) {
                circle(x-bx,y-by,dx,dy);
                rlo=0;rhi=1;
                if(slab((x-ax)*vx+(y-ay)*vy,dx*vx+dy*vy,0,len)&&slab(-(x-ax)*vy+(y-ay)*vx,-dx*vy+dy*vx,-r,r))merge(rlo,rhi);
            }
            return hi-lo>1e-9;
        }
        boolean hitVariable(double x,double y,double nx,double ny,double radius,double change) {
            if (Math.max(x,nx)<left || Math.min(x,nx)>right || Math.max(y,ny)<top || Math.min(y,ny)>bottom) return false;
            double dx=nx-x, dy=ny-y, px=x-ax, py=y-ay;
            lo=Double.POSITIVE_INFINITY;
            hi=Double.NEGATIVE_INFINITY;
            rlo=0;
            rhi=1;
            if (len<=1e-9) {
                cover(px,py,dx,dy,radius,change);
            } else {
                double along=px*vx+py*vy, alongRate=dx*vx+dy*vy;
                if (slab(along,alongRate,Double.NEGATIVE_INFINITY,0)) cover(px,py,dx,dy,radius,change);
                rlo=0;
                rhi=1;
                if (slab(along,alongRate,0,len)) cover(-px*vy+py*vx,0,-dx*vy+dy*vx,0,radius,change);
                rlo=0;
                rhi=1;
                if (slab(along,alongRate,len,Double.POSITIVE_INFINITY)) cover(x-bx,y-by,dx,dy,radius,change);
            }
            return hi-lo>1e-9;
        }
        // Solve squared distance <= squared radius over the current projection interval.
        void cover(double x,double y,double dx,double dy,double radius,double change) {
            double a=dx*dx+dy*dy-change*change;
            double b=x*dx+y*dy-radius*change;
            double c=x*x+y*y-radius*radius;
            if (Math.abs(a)<1e-12) {
                if (Math.abs(b)<1e-12) {
                    if (c<0) merge(rlo,rhi);
                } else {
                    double edge=-c/(2*b);
                    if (b>0) merge(rlo,Math.min(rhi,edge));
                    else merge(Math.max(rlo,edge),rhi);
                }
                return;
            }
            double discriminant=b*b-a*c;
            if (discriminant<=0) {
                if (a<0) merge(rlo,rhi);
                return;
            }
            double root=Math.sqrt(discriminant);
            double first=(-b-root)/a, last=(-b+root)/a;
            if (a>0) merge(Math.max(rlo,first),Math.min(rhi,last));
            else {
                merge(rlo,Math.min(rhi,last));
                merge(Math.max(rlo,first),rhi);
            }
        }
        void circle(double x,double y,double dx,double dy){double a=dx*dx+dy*dy,c=x*x+y*y-rSquared;if(a<1e-18){if(c<0)merge(0,1);return;}double b=x*dx+y*dy,d=b*b-a*c;if(d<=0)return;double root=Math.sqrt(d);merge(Math.max(0,(-b-root)/a),Math.min(1,(-b+root)/a));}
        void merge(double a,double b){if(b>a){lo=Math.min(lo,a);hi=Math.max(hi,b);}}
        boolean slab(double p,double d,double a,double b){if(Math.abs(d)<1e-12)return p>=a&&p<=b;double x=(a-p)/d,y=(b-p)/d;rlo=Math.max(rlo,Math.min(x,y));rhi=Math.min(rhi,Math.max(x,y));return rhi>rlo;}
    }
}
