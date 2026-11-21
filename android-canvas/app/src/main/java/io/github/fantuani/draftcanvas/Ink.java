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
    final ArrayList<QuestionImage> questions = new ArrayList<>();
    public final Set<Long> selected=new HashSet<>();
    private record Edit(List<Stroke> strokes, List<QuestionImage> questions) {}
    private final ArrayDeque<Edit> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private Edit transaction;
    private List<Stroke> clipboard=Collections.emptyList();
    private List<QuestionImage> questionClipboard=Collections.emptyList();
    long revision;
    private long nextId=1;
    public long nextId(){return nextId++;}
    boolean hasOpenEdit(){return transaction!=null;}
    private Edit snapshot() { return new Edit(new ArrayList<>(strokes), new ArrayList<>(questions)); }
    private void restore(Edit edit) {
        strokes.clear();
        strokes.addAll(edit.strokes);
        questions.clear();
        questions.addAll(edit.questions);
        revision++;
    }
    public void begin(){if(transaction==null)transaction=snapshot();}
    public void commit(){if(transaction!=null&&(!transaction.strokes.equals(strokes)||!transaction.questions.equals(questions))){undo.addLast(transaction);while(undo.size()>100)undo.removeFirst();redo.clear();revision++;}transaction=null;}
    public void cancel(){if(transaction!=null){restore(transaction);transaction=null;}}
    public void add(Stroke s){begin();strokes.add(s);commit();}
    public boolean canUndo(){return !undo.isEmpty();}public boolean canRedo(){return !redo.isEmpty();}
    public void undo(){if(!undo.isEmpty()){redo.addLast(snapshot());restore(undo.removeLast());selected.clear();}}
    public void redo(){if(!redo.isEmpty()){undo.addLast(snapshot());restore(redo.removeLast());selected.clear();}}
    public void select(double l,double t,double r,double b){selected.clear();for(Stroke s:strokes)if(s.crossesRect(l,t,r,b))selected.add(s.id);
        for (QuestionImage image : questions) if (image.x >= l && image.y >= t && image.right() <= r && image.bottom() <= b) selected.add(image.id);
    }
    void select(Lasso lasso) {
        selected.clear();
        for (Stroke stroke : strokes) if (stroke.crossesLasso(lasso)) selected.add(stroke.id);
        for (QuestionImage image : questions) {
            if (lasso.contains(image.x, image.y) && lasso.contains(image.right(), image.y)
                    && lasso.contains(image.x, image.bottom()) && lasso.contains(image.right(), image.bottom())) {
                selected.add(image.id);
            }
        }
    }
    public double[] selectionBounds(){double l=Double.POSITIVE_INFINITY,t=l,r=-l,b=-l;for(Stroke s:strokes)if(selected.contains(s.id)){l=Math.min(l,s.left);r=Math.max(r,s.right);t=Math.min(t,s.top);b=Math.max(b,s.bottom);}
        for(QuestionImage image:questions)if(selected.contains(image.id)){l=Math.min(l,image.x);r=Math.max(r,image.right());t=Math.min(t,image.y);b=Math.max(b,image.bottom());}
        return selected.isEmpty()?null:new double[]{l,t,r,b};}
    public void move(double dx,double dy){if(selected.isEmpty()||(dx==0&&dy==0))return;begin();for(int i=0;i<strokes.size();i++){Stroke s=strokes.get(i);if(selected.contains(s.id))strokes.set(i,s.offset(s.id,dx,dy));}
        for(int i=0;i<questions.size();i++){QuestionImage image=questions.get(i);if(selected.contains(image.id))questions.set(i,image.offset(image.id,dx,dy));}commit();}
    void resizeSelection(double anchorX, double anchorY, double factor) {
        if (selected.isEmpty() || factor == 1) return;
        begin();
        for (int i = 0; i < strokes.size(); i++) {
            Stroke stroke = strokes.get(i);
            if (selected.contains(stroke.id)) strokes.set(i, stroke.scaled(anchorX, anchorY, factor));
        }
        for (int i = 0; i < questions.size(); i++) {
            QuestionImage image = questions.get(i);
            if (selected.contains(image.id)) questions.set(i, image.scaled(anchorX, anchorY, factor));
        }
        commit();
    }
    public void copy(){
        List<Stroke> copied=new ArrayList<>();for(Stroke s:strokes)if(selected.contains(s.id))copied.add(s);
        List<QuestionImage> images=new ArrayList<>();for(QuestionImage image:questions)if(selected.contains(image.id))images.add(image);
        if(!copied.isEmpty()||!images.isEmpty()){clipboard=copied;questionClipboard=images;}
    }
    public boolean canPaste(){return !clipboard.isEmpty()||!questionClipboard.isEmpty();}
    boolean canPasteQuestions() { return clipboard.isEmpty() && !questionClipboard.isEmpty(); }

    void copyQuestion(QuestionImage image) {
        clipboard = Collections.emptyList();
        questionClipboard = Collections.singletonList(image);
    }

    void deleteQuestion(QuestionImage image) {
        begin();
        questions.remove(image);
        selected.remove(image.id);
        commit();
    }

    public void paste(double screenWidth,double screenHeight){
        if(!canPaste())return;double l=Double.POSITIVE_INFINITY,t=l,r=-l,b=-l;
        for(Stroke s:clipboard){l=Math.min(l,s.left);r=Math.max(r,s.right);t=Math.min(t,s.top);b=Math.max(b,s.bottom);}
        for(QuestionImage image:questionClipboard){l=Math.min(l,image.x);r=Math.max(r,image.right());t=Math.min(t,image.y);b=Math.max(b,image.bottom());}
        double dx=24/view.scale,dy=24/view.scale;
        if(Math.abs((l+r)/2-view.x)>screenWidth/view.scale/2||Math.abs((t+b)/2-view.y)>screenHeight/view.scale/2){dx=view.x-(l+r)/2;dy=view.y-(t+b)/2;}
        begin();selected.clear();List<Stroke> pasted=new ArrayList<>();for(Stroke s:clipboard){Stroke p=s.offset(nextId(),dx,dy);strokes.add(p);selected.add(p.id);pasted.add(p);}clipboard=pasted;
        List<QuestionImage> images=new ArrayList<>();
        for(QuestionImage image:questionClipboard){QuestionImage p=image.offset(nextId(),dx,dy);questions.add(p);selected.add(p.id);images.add(p);}
        questionClipboard=images;commit();
    }
    public void deleteSelected(){if(selected.isEmpty())return;begin();strokes.removeIf(s->selected.contains(s.id));questions.removeIf(image->selected.contains(image.id));selected.clear();commit();}
    private static final double[] EMPTY_SECTIONS = new double[0];
    double erasePadding;
    public boolean erase(double ax, double ay, double bx, double by, double radius) {
        int count = strokes.size(), retained = 0;
        boolean changed = false;
        erasePadding = 0;
        double left = Math.min(ax, bx) - radius, top = Math.min(ay, by) - radius;
        double right = Math.max(ax, bx) + radius, bottom = Math.max(ay, by) + radius;
        for (int i = 0; i < count; i++) {
            Stroke stroke = strokes.get(i);
            Stroke replacement = stroke;
            if (stroke.intersects(left, top, right, bottom)) {
                double[] sections = erasedSections(stroke, ax, ay, bx, by, radius);
                if (sections != stroke.sections) {
                    changed = true;
                    erasePadding = Math.max(erasePadding, stroke.maxWidth);
                    if (!selected.isEmpty()) selected.remove(stroke.id);
                    replacement = sections.length == 0 ? null : new Stroke(nextId(), stroke, sections);
                }
            }
            if (replacement != null) {
                if (retained != i || replacement != stroke) strokes.set(retained, replacement);
                retained++;
            }
        }
        if (retained < count) strokes.subList(retained, count).clear();
        if (changed) revision++;
        return changed;
    }

    private double[] erasedSections(Stroke stroke, double ax, double ay, double bx, double by, double radius) {
        if (stroke.pathPoints().length == 3) {
            double reach = radius + stroke.maxWidth / 2;
            return Eraser.distanceSquared(stroke.points[0], stroke.points[1], ax - stroke.x,
                    ay - stroke.y, bx - stroke.x, by - stroke.y) <= reach * reach
                    ? EMPTY_SECTIONS : stroke.sections;
        }
        return stroke.data().erase(stroke.sections, ax - stroke.x, ay - stroke.y, bx - stroke.x, by - stroke.y,
                radius, stroke.width / 2, stroke.variableWidth, stroke.maxWidth / 2);
    }

    boolean hasContent() { return !strokes.isEmpty() || !questions.isEmpty(); }
    public void clear(){if(!hasContent())return;begin();strokes.clear();questions.clear();selected.clear();commit();}

    QuestionImage previewQuestion(int pixelsWide, int pixelsHigh,
                                 int screenWidth, int screenHeight, double padding, long previous) {
        double width = Math.min(screenWidth * .48, screenHeight * .62 * pixelsWide / pixelsHigh) / view.scale;
        double height = width * pixelsHigh / pixelsWide;
        double left = view.worldX(padding, screenWidth), top = view.worldY(padding, screenHeight);
        double right = view.worldX(screenWidth - padding, screenWidth);
        double bottom = view.worldY(screenHeight - padding, screenHeight);
        double gap = padding / view.scale;
        QuestionImage last = question(previous);
        double x = last == null ? left : last.x;
        double y = last == null ? top : last.bottom() + (screenHeight * .32 + padding) / view.scale;
        if (last == null && occupied(x, y, x + width + gap, y + height + gap)) {
            double rightSlot = right - width;
            double bottomSlot = bottom - height;
            if (!occupied(x, bottomSlot, x + width + gap, bottomSlot + height + gap)) y = bottomSlot;
            else if (!occupied(rightSlot, y, rightSlot + width + gap, y + height + gap)) x = rightSlot;
            else { x = right + gap; y = top; }
        }
        // Walk down past occupied rows rather than covering any ink or earlier problem.
        boolean moved;
        do {
            moved = false;
            for (Stroke stroke : strokes) {
                if (stroke.intersects(x, y, x + width + gap, y + height + gap)) {
                    y = stroke.bottom + gap;
                    moved = true;
                }
            }
            for (QuestionImage image : questions) {
                if (image.intersects(x, y, x + width + gap, y + height + gap)) {
                    y = image.bottom() + gap;
                    moved = true;
                }
            }
        } while (moved);
        return new QuestionImage(0, null, x, y, width, height);
    }

    QuestionImage importQuestion(String asset, QuestionImage placement) {
        QuestionImage image = new QuestionImage(nextId(), asset, placement.x, placement.y,
                placement.width, placement.height);
        begin();
        questions.add(image);
        commit();
        return image;
    }

    QuestionImage question(long id) {
        for (QuestionImage image : questions) if (image.id == id) return image;
        return null;
    }

    void replaceQuestion(QuestionImage image) {
        for (int i = 0; i < questions.size(); i++) {
            if (questions.get(i).id != image.id) continue;
            begin();
            questions.set(i, image);
            commit();
            return;
        }
    }

    private boolean occupied(double left, double top, double right, double bottom) {
        for (Stroke stroke : strokes) if (stroke.intersects(left, top, right, bottom)) return true;
        for (QuestionImage image : questions) if (image.intersects(left, top, right, bottom)) return true;
        return false;
    }
}
