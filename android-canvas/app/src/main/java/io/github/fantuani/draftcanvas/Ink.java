package io.github.fantuani.draftcanvas;

import java.util.*;

/** Session-only immutable vector strokes. History shares point arrays, never pixel bitmaps. */
public final class Ink {
    public static final class Stroke {
        public final long id;public final int color;public final float width;
        public final double x,y;public final float[] points; // local x,y,pressure triplets
        public final double left,top,right,bottom;
        private final boolean flattened;private float[] geometry;
        public Stroke(long id,int color,float width,double x,double y,float[] points){
            this(id,color,width,x,y,points,false);
        }
        Stroke(long id,int color,float width,double x,double y,float[] points,boolean flattened){
            this.flattened=flattened;this.id=id;this.color=color;this.width=width;this.x=x;this.y=y;this.points=points;
            double l=points[0],t=points[1],r=l,b=t;
            for(int i=0;i<points.length;i+=3){l=Math.min(l,points[i]);r=Math.max(r,points[i]);t=Math.min(t,points[i+1]);b=Math.max(b,points[i+1]);}
            left=x+l-width;right=x+r+width;top=y+t-width;bottom=y+b+width;
        }
        public float[] pathPoints(){if(flattened)return points;if(geometry==null)geometry=StrokeGeometry.smooth(points,width);return geometry;}
        public Stroke offset(long id,double dx,double dy){return new Stroke(id,color,width,x+dx,y+dy,points,flattened);}
        public boolean intersects(double l,double t,double r,double b){return right>=l&&left<=r&&bottom>=t&&top<=b;}
        public boolean crossesRect(double l,double t,double r,double b){
            float[] points=pathPoints();
            l-=width/2;t-=width/2;r+=width/2;b+=width/2;if(!intersects(l,t,r,b))return false;
            double px=x+points[0],py=y+points[1];if(px>=l&&px<=r&&py>=t&&py<=b)return true;
            for(int i=3;i<points.length;i+=3){double nx=x+points[i],ny=y+points[i+1],dx=nx-px,dy=ny-py,low=0,high=1;
                if(dx==0){if(px<l||px>r){px=nx;py=ny;continue;}}else{double a=(l-px)/dx,c=(r-px)/dx;low=Math.max(low,Math.min(a,c));high=Math.min(high,Math.max(a,c));}
                if(dy==0){if(py<t||py>b){px=nx;py=ny;continue;}}else{double a=(t-py)/dy,c=(b-py)/dy;low=Math.max(low,Math.min(a,c));high=Math.min(high,Math.max(a,c));}
                if(low<=high)return true;px=nx;py=ny;
            }return false;
        }
        public boolean hit(double wx,double wy,double tolerance){
            float[] points=pathPoints();
            if(!intersects(wx-tolerance,wy-tolerance,wx+tolerance,wy+tolerance))return false;
            double px=points[0]+x,py=points[1]+y;
            if(Math.hypot(wx-px,wy-py)<=tolerance+width/2)return true;
            for(int i=3;i<points.length;i+=3){double nx=points[i]+x,ny=points[i+1]+y;
                double vx=nx-px,vy=ny-py,den=vx*vx+vy*vy;
                double u=den==0?0:Math.max(0,Math.min(1,((wx-px)*vx+(wy-py)*vy)/den));
                if(Math.hypot(wx-px-u*vx,wy-py-u*vy)<=tolerance+width/2)return true;px=nx;py=ny;
            }return false;
        }
    }
    public final Viewport view=new Viewport();
    public final ArrayList<Stroke> strokes=new ArrayList<>();
    public final Set<Long> selected=new HashSet<>();
    private final ArrayDeque<List<Stroke>> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private List<Stroke> transaction;
    private List<Stroke> clipboard=Collections.emptyList();
    private long nextId=1;
    public long nextId(){return nextId++;}
    public void begin(){if(transaction==null)transaction=new ArrayList<>(strokes);}
    public void commit(){if(transaction!=null&&!transaction.equals(strokes)){undo.addLast(transaction);while(undo.size()>100)undo.removeFirst();redo.clear();}transaction=null;}
    public void cancel(){if(transaction!=null){strokes.clear();strokes.addAll(transaction);transaction=null;}}
    public void add(Stroke s){begin();strokes.add(s);commit();}
    public boolean canUndo(){return !undo.isEmpty();}public boolean canRedo(){return !redo.isEmpty();}
    public void undo(){if(!undo.isEmpty()){redo.addLast(new ArrayList<>(strokes));strokes.clear();strokes.addAll(undo.removeLast());selected.clear();}}
    public void redo(){if(!redo.isEmpty()){undo.addLast(new ArrayList<>(strokes));strokes.clear();strokes.addAll(redo.removeLast());selected.clear();}}
    public void select(double l,double t,double r,double b){selected.clear();for(Stroke s:strokes)if(s.crossesRect(l,t,r,b))selected.add(s.id);}
    public double[] selectionBounds(){double l=Double.POSITIVE_INFINITY,t=l,r=-l,b=-l;for(Stroke s:strokes)if(selected.contains(s.id)){l=Math.min(l,s.left);r=Math.max(r,s.right);t=Math.min(t,s.top);b=Math.max(b,s.bottom);}return selected.isEmpty()?null:new double[]{l,t,r,b};}
    public void move(double dx,double dy){if(selected.isEmpty()||(dx==0&&dy==0))return;begin();for(int i=0;i<strokes.size();i++){Stroke s=strokes.get(i);if(selected.contains(s.id))strokes.set(i,s.offset(s.id,dx,dy));}commit();}
    public void copy(){List<Stroke> copied=new ArrayList<>();for(Stroke s:strokes)if(selected.contains(s.id))copied.add(s);if(!copied.isEmpty())clipboard=copied;}
    public boolean canPaste(){return !clipboard.isEmpty();}
    public void paste(double screenWidth,double screenHeight){
        if(clipboard.isEmpty())return;double l=Double.POSITIVE_INFINITY,t=l,r=-l,b=-l;
        for(Stroke s:clipboard){l=Math.min(l,s.left);r=Math.max(r,s.right);t=Math.min(t,s.top);b=Math.max(b,s.bottom);}
        double dx=24/view.scale,dy=24/view.scale;
        if(Math.abs((l+r)/2-view.x)>screenWidth/view.scale/2||Math.abs((t+b)/2-view.y)>screenHeight/view.scale/2){dx=view.x-(l+r)/2;dy=view.y-(t+b)/2;}
        begin();selected.clear();List<Stroke> pasted=new ArrayList<>();for(Stroke s:clipboard){Stroke p=s.offset(nextId(),dx,dy);strokes.add(p);selected.add(p.id);pasted.add(p);}clipboard=pasted;commit();
    }
    public void deleteSelected(){if(selected.isEmpty())return;begin();strokes.removeIf(s->selected.contains(s.id));selected.clear();commit();}
    public void erase(double ax,double ay,double bx,double by,double radius){
        List<Stroke> next=new ArrayList<>();boolean changed=false;
        for(Stroke s:strokes){
            if(!s.intersects(Math.min(ax,bx)-radius,Math.min(ay,by)-radius,Math.max(ax,bx)+radius,Math.max(ay,by)+radius)){next.add(s);continue;}
            List<float[]> pieces=Eraser.cut(s.pathPoints(),ax-s.x,ay-s.y,bx-s.x,by-s.y,radius+s.width/2);
            if(pieces==null){next.add(s);continue;}changed=true;selected.remove(s.id);
            for(float[] p:pieces)next.add(new Stroke(nextId(),s.color,s.width,s.x,s.y,p,true));
        }
        if(changed){strokes.clear();strokes.addAll(next);}
    }
    public void clear(){if(strokes.isEmpty())return;begin();strokes.clear();selected.clear();commit();}
}
