package io.github.fantuani.draftcanvas;

import java.util.*;

/** Immutable vector strokes. History shares point arrays, never pixel bitmaps. */
public final class Ink {
    public static final class Stroke {
        public final long id;public final int color;public final float width;
        public final double x,y;public final float[] points; // local x, y, and width multiplier for variable-width ink
        public final double left,top,right,bottom;
        final boolean variableWidth;
        final float maxWidth;
        private final boolean flattened;private float[] geometry;private StrokeData data;final double[] sections;
        Object renderState,previousRender;
        public Stroke(long id,int color,float width,double x,double y,float[] points){
            this(id,color,width,x,y,points,false);
        }
        Stroke(long id,int color,float width,double x,double y,float[] points,boolean flattened){
            this(id,color,width,x,y,points,flattened,null);
        }
        Stroke(long id,int color,float width,double x,double y,float[] points,boolean flattened,double[] sections){
            this(id, color, width, x, y, points, flattened, sections, false);
        }
        Stroke(long id,int color,float width,double x,double y,float[] points,boolean flattened,
               double[] sections,boolean variableWidth){
            this.sections=sections;this.flattened=flattened;this.id=id;this.color=color;this.width=width;this.x=x;this.y=y;this.points=points;
            this.variableWidth = variableWidth;
            float highest = variableWidth ? points[2] : 1f;
            double l=points[0],t=points[1],r=l,b=t;
            for(int i=0;i<points.length;i+=3){l=Math.min(l,points[i]);r=Math.max(r,points[i]);t=Math.min(t,points[i+1]);b=Math.max(b,points[i+1]);
                if (variableWidth) highest = Math.max(highest, points[i + 2]);}
            maxWidth = width * highest;
            left=x+l-maxWidth;right=x+r+maxWidth;top=y+t-maxWidth;bottom=y+b+maxWidth;
        }
        private Stroke(long id,Stroke source,double[] sections){
            this.id=id;color=source.color;width=source.width;x=source.x;y=source.y;points=source.points;flattened=source.flattened;geometry=source.geometry;data=source.data;
            variableWidth = source.variableWidth;
            maxWidth = source.maxWidth;
            left=source.left;top=source.top;right=source.right;bottom=source.bottom;this.sections=sections;previousRender=source.renderState!=null?source.renderState:source.previousRender;
        }
        StrokeData data(){if(data==null)data=new StrokeData(pathPoints());return data;}
        public float[] pathPoints(){if(flattened)return points;if(geometry==null)geometry=StrokeGeometry.smooth(points,width,variableWidth);return geometry;}
        public Stroke offset(long id,double dx,double dy){Stroke copy=new Stroke(id,color,width,x+dx,y+dy,points,flattened,sections,variableWidth);copy.geometry=geometry;copy.data=data;copy.renderState=renderState;copy.previousRender=previousRender;return copy;}
        boolean hasFlattenedGeometry() { return flattened || sections != null; }

        Stroke scaled(double anchorX, double anchorY, double factor) {
            float[] scaled = pathPoints().clone();
            for (int i = 0; i < scaled.length; i += 3) {
                scaled[i] *= factor;
                scaled[i + 1] *= factor;
            }
            return new Stroke(id, color, (float) (width * factor), anchorX + (x - anchorX) * factor,
                    anchorY + (y - anchorY) * factor, scaled, true, sections, variableWidth);
        }
        public boolean intersects(double l,double t,double r,double b){return right>=l&&left<=r&&bottom>=t&&top<=b;}
        public boolean crossesRect(double l,double t,double r,double b){
            float[] points=pathPoints();
            l-=maxWidth/2;t-=maxWidth/2;r+=maxWidth/2;b+=maxWidth/2;if(!intersects(l,t,r,b))return false;
            double px=x+points[0],py=y+points[1];if(px>=l&&px<=r&&py>=t&&py<=b&&StrokeData.visible(sections,0,0))return true;
            for(int i=3;i<points.length;i+=3){double nx=x+points[i],ny=y+points[i+1],dx=nx-px,dy=ny-py,low=0,high=1;
                if(dx==0){if(px<l||px>r){px=nx;py=ny;continue;}}else{double a=(l-px)/dx,c=(r-px)/dx;low=Math.max(low,Math.min(a,c));high=Math.min(high,Math.max(a,c));}
                if(dy==0){if(py<t||py>b){px=nx;py=ny;continue;}}else{double a=(t-py)/dy,c=(b-py)/dy;low=Math.max(low,Math.min(a,c));high=Math.min(high,Math.max(a,c));}
                if(low<=high&&StrokeData.visible(sections,i/3.0-1+low,i/3.0-1+high))return true;px=nx;py=ny;
            }return false;
        }
        public boolean hit(double wx,double wy,double tolerance){
            float[] points=pathPoints();
            if(!intersects(wx-tolerance,wy-tolerance,wx+tolerance,wy+tolerance))return false;
            double px=points[0]+x,py=points[1]+y;
            if(Math.hypot(wx-px,wy-py)<=tolerance+width*(variableWidth?points[2]:1)/2&&StrokeData.visible(sections,0,0))return true;
            for(int i=3;i<points.length;i+=3){double nx=points[i]+x,ny=points[i+1]+y;
                double vx=nx-px,vy=ny-py,den=vx*vx+vy*vy;
                double u=den==0?0:Math.max(0,Math.min(1,((wx-px)*vx+(wy-py)*vy)/den));
                double radius = width / 2 * (variableWidth ? points[i - 1] + u * (points[i + 2] - points[i - 1]) : 1);
                if(Math.hypot(wx-px-u*vx,wy-py-u*vy)<=tolerance+radius&&StrokeData.visible(sections,i/3.0-1+u,i/3.0-1+u))return true;px=nx;py=ny;
            }return false;
        }

        boolean crossesLasso(Lasso lasso) {
            if (!intersects(lasso.left, lasso.top, lasso.right, lasso.bottom)) return false;
            StrokeData geometry = data();
            int count = sections == null ? 2 : sections.length;
            for (int range = 0; range < count; range += 2) {
                double start = sections == null ? 0 : sections[range];
                double end = sections == null ? geometry.segments : sections[range + 1];
                do {
                    double next = Math.min(end, Math.floor(start) + 1);
                    double radius = width / 2 * (variableWidth
                            ? Math.max(geometry.coordinate(start, 2), geometry.coordinate(next, 2)) : 1);
                    if (lasso.intersectsStrokeSegment(x + geometry.coordinate(start, 0),
                            y + geometry.coordinate(start, 1), x + geometry.coordinate(next, 0),
                            y + geometry.coordinate(next, 1), radius)) return true;
                    start = next;
                } while (start < end);
            }
            return false;
        }
    }
    public final Viewport view=new Viewport();
    public final ArrayList<Stroke> strokes=new ArrayList<>();
    public final Set<Long> selected=new HashSet<>();
    private final ArrayDeque<List<Stroke>> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private List<Stroke> transaction;
    private List<Stroke> clipboard=Collections.emptyList();
    long revision;
    private long nextId=1;
    public long nextId(){return nextId++;}
    boolean hasOpenEdit(){return transaction!=null;}
    public void begin(){if(transaction==null)transaction=new ArrayList<>(strokes);}
    public void commit(){if(transaction!=null&&!transaction.equals(strokes)){undo.addLast(transaction);while(undo.size()>100)undo.removeFirst();redo.clear();revision++;}transaction=null;}
    public void cancel(){if(transaction!=null){strokes.clear();strokes.addAll(transaction);revision++;transaction=null;}}
    public void add(Stroke s){begin();strokes.add(s);commit();}
    public boolean canUndo(){return !undo.isEmpty();}public boolean canRedo(){return !redo.isEmpty();}
    public void undo(){if(!undo.isEmpty()){redo.addLast(new ArrayList<>(strokes));strokes.clear();strokes.addAll(undo.removeLast());selected.clear();revision++;}}
    public void redo(){if(!redo.isEmpty()){undo.addLast(new ArrayList<>(strokes));strokes.clear();strokes.addAll(redo.removeLast());selected.clear();revision++;}}
    public void select(double l,double t,double r,double b){selected.clear();for(Stroke s:strokes)if(s.crossesRect(l,t,r,b))selected.add(s.id);}
    void select(Lasso lasso) {
        selected.clear();
        for (Stroke stroke : strokes) if (stroke.crossesLasso(lasso)) selected.add(stroke.id);
    }
    public double[] selectionBounds(){double l=Double.POSITIVE_INFINITY,t=l,r=-l,b=-l;for(Stroke s:strokes)if(selected.contains(s.id)){l=Math.min(l,s.left);r=Math.max(r,s.right);t=Math.min(t,s.top);b=Math.max(b,s.bottom);}return selected.isEmpty()?null:new double[]{l,t,r,b};}
    public void move(double dx,double dy){if(selected.isEmpty()||(dx==0&&dy==0))return;begin();for(int i=0;i<strokes.size();i++){Stroke s=strokes.get(i);if(selected.contains(s.id))strokes.set(i,s.offset(s.id,dx,dy));}commit();}
    void resizeSelection(double anchorX, double anchorY, double factor) {
        if (selected.isEmpty() || factor == 1) return;
        begin();
        for (int i = 0; i < strokes.size(); i++) {
            Stroke stroke = strokes.get(i);
            if (selected.contains(stroke.id)) strokes.set(i, stroke.scaled(anchorX, anchorY, factor));
        }
        commit();
    }
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
    double erasePadding;
    public boolean erase(double ax,double ay,double bx,double by,double radius){
        List<Stroke> next=null;int index=0;erasePadding=0;
        double left=Math.min(ax,bx)-radius,top=Math.min(ay,by)-radius,right=Math.max(ax,bx)+radius,bottom=Math.max(ay,by)+radius;
        for(Stroke s:strokes){
            if(!s.intersects(left,top,right,bottom)){if(next!=null)next.add(s);index++;continue;}
            double[] sections;
            if(s.pathPoints().length==3){if(Eraser.distanceSquared(s.points[0],s.points[1],ax-s.x,ay-s.y,bx-s.x,by-s.y)>(radius+s.maxWidth/2)*(radius+s.maxWidth/2)){if(next!=null)next.add(s);index++;continue;}sections=new double[0];}
            else sections=s.data().erase(s.sections,ax-s.x,ay-s.y,bx-s.x,by-s.y,radius,
                    s.width / 2, s.variableWidth, s.maxWidth / 2);
            if(sections==s.sections){if(next!=null)next.add(s);index++;continue;}
            if(next==null){next=new ArrayList<>(strokes.size());next.addAll(strokes.subList(0,index));}
            erasePadding=Math.max(erasePadding,s.maxWidth);selected.remove(s.id);if(sections.length>0)next.add(new Stroke(nextId(),s,sections));index++;
        }
        if(next==null)return false;strokes.clear();strokes.addAll(next);revision++;return true;
    }
    public void clear(){if(strokes.isEmpty())return;begin();strokes.clear();selected.clear();commit();}
}
