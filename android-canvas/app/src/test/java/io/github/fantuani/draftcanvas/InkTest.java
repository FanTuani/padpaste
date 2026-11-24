package io.github.fantuani.draftcanvas;
import org.junit.Test;
import static org.junit.Assert.*;
public class InkTest {
    @Test public void importedQuestionsAvoidExistingInkAndUseNormalHistory() {
        Ink ink = new Ink();
        ink.add(new Ink.Stroke(ink.nextId(), 0xff000000, 4, -475, -245, new float[]{0,0,1,160,0,1}));
        QuestionImage placement = ink.previewQuestion(600, 400, 1000, 600, 30, 0);
        assertTrue(ink.questions.isEmpty());
        ink.view.pan(120, 80);
        QuestionImage first = ink.importQuestion("a", placement);
        assertEquals(placement.x, first.x, 0);
        assertEquals(placement.y, first.y, 0);
        Ink.Stroke existing = ink.strokes.get(0);
        assertFalse(first.intersects(existing.left, existing.top, existing.right, existing.bottom));
        QuestionImage next = ink.importQuestion("b", ink.previewQuestion(600, 400, 1000, 600, 30, first.id));
        assertEquals(first.x, next.x, 0);
        assertTrue(next.y > first.bottom());
        ink.select(next.x - 1, next.y - 1, next.right() + 1, next.bottom() + 1);
        ink.move(5, 9);
        assertEquals(next.x + 5, ink.question(next.id).x, 0);
        ink.undo();
        assertSame(next, ink.question(next.id));
        ink.undo();
        assertNull(ink.question(next.id));
        ink.redo();
        assertSame(next, ink.question(next.id));
    }
    private Ink.Stroke stroke(Ink d){return new Ink.Stroke(d.nextId(),0xff000000,2,100,200,new float[]{0,0,.5f,20,30,.7f});}
    @Test public void anchoredPasteCentersMixedContentAndUndoesTogether() {
        Ink ink = new Ink();
        ink.pasteAt(10, 20);
        assertFalse(ink.canUndo());
        Ink.Stroke stroke = stroke(ink);
        ink.add(stroke);
        QuestionImage image = ink.importQuestion("asset", new QuestionImage(0, null, 10, 50, 80, 40));
        ink.selected.add(stroke.id);
        ink.selected.add(image.id);
        ink.copy();

        ink.pasteAt(1075.5, -342.25);
        double[] bounds = ink.selectionBounds();
        assertEquals(1075.5, (bounds[0] + bounds[2]) / 2, .0001);
        assertEquals(-342.25, (bounds[1] + bounds[3]) / 2, .0001);
        assertEquals(2, ink.selected.size());
        QuestionImage copy = ink.questions.get(1);
        assertEquals(stroke.x - image.x, ink.strokes.get(1).x - copy.x, .0001);
        assertEquals(stroke.y - image.y, ink.strokes.get(1).y - copy.y, .0001);
        ink.undo();
        assertEquals(1, ink.questions.size());
        assertEquals(1, ink.strokes.size());
        assertTrue(ink.canPaste());
    }
    @Test public void zoomKeepsFocusAndWorksAtDistantCoordinates(){Viewport v=new Viewport();v.x=1e11;v.y=-1e11;double x=v.worldX(231,1000),y=v.worldY(178,700);v.zoom(9.3,231,178,1000,700);assertEquals(x,v.worldX(231,1000),.001);assertEquals(y,v.worldY(178,700),.001);}
    @Test public void selectionMoveCopyPasteUndoIsIndependent(){Ink d=new Ink();d.add(stroke(d));d.select(90,190,140,240);d.copy();d.move(80,-20);assertEquals(180,d.strokes.get(0).x,0);d.paste(1000,700);assertEquals(2,d.strokes.size());assertEquals(124,d.strokes.get(1).x,0);d.undo();assertEquals(1,d.strokes.size());d.undo();assertEquals(100,d.strokes.get(0).x,0);d.redo();assertEquals(180,d.strokes.get(0).x,0);}
    @Test public void canceledEraseRestoresStrokeAndCommittedEraseUndoes(){Ink d=new Ink();d.add(stroke(d));d.begin();d.strokes.clear();d.cancel();assertEquals(1,d.strokes.size());d.begin();d.strokes.clear();d.commit();d.undo();assertEquals(1,d.strokes.size());}
    @Test public void penHistorySurvivesInterleavedSnapshotUndoRedo() {
        Ink ink = new Ink();
        Ink.Stroke first = stroke(ink);
        ink.add(first);
        ink.selected.add(first.id);
        ink.move(17, -9);
        Ink.Stroke moved = ink.strokes.get(0);
        Ink.Stroke last = stroke(ink);
        ink.add(last);
        ink.undo();
        assertEquals(1, ink.strokes.size());
        assertSame(moved, ink.strokes.get(0));
        ink.undo();
        assertSame(first, ink.strokes.get(0));
        ink.undo();
        assertTrue(ink.strokes.isEmpty());
        ink.redo();
        ink.redo();
        ink.redo();
        assertSame(moved, ink.strokes.get(0));
        assertSame(last, ink.strokes.get(1));
        ink.undo();
        ink.add(stroke(ink));
        assertFalse(ink.canRedo());
    }

    @Test public void penHistoryKeepsOneHundredEditsAndCommitsAnOpenTransaction() {
        Ink ink = new Ink();
        for (int i = 0; i < 103; i++) ink.add(stroke(ink));
        for (int i = 0; i < 100; i++) ink.undo();
        assertFalse(ink.canUndo());
        assertEquals(3, ink.strokes.size());
        Ink.Stroke first = ink.strokes.get(0);
        ink.begin();
        ink.strokes.clear();
        ink.add(stroke(ink));
        assertEquals(1, ink.strokes.size());
        ink.undo();
        assertEquals(3, ink.strokes.size());
        assertSame(first, ink.strokes.get(0));
    }

    @Test public void replayDuringEraseFinishesTheEditBeforeUsingHistory() {
        Ink ink = new Ink();
        Ink.Stroke dot = new Ink.Stroke(ink.nextId(), 0, 2, 20, 20, new float[]{0,0,1});
        ink.add(dot);
        ink.add(stroke(ink));
        ink.undo(); // A redo is available until the next edit.
        ink.begin();
        assertTrue(ink.erase(20, 20, 20, 20, 4));
        ink.redo();
        assertFalse(ink.hasOpenEdit());
        assertTrue(ink.strokes.isEmpty());
        assertFalse(ink.canRedo());
        ink.undo();
        assertSame(dot, ink.strokes.get(0));
        assertTrue(ink.erase(20, 20, 20, 20, 4)); // Standalone model edit.
        ink.undo();
        assertSame(dot, ink.strokes.get(0));
        ink.undo();
        assertTrue(ink.strokes.isEmpty());
    }

    @Test public void rectangleRejectsEmptyPartOfStrokeBounds(){Ink d=new Ink();d.add(stroke(d));d.select(99,227,103,231);assertTrue(d.selected.isEmpty());d.select(108,211,113,219);assertEquals(1,d.selected.size());}
    @Test public void hitTestDoesNotEraseWholeBoundingBox(){Ink d=new Ink();Ink.Stroke s=stroke(d);assertTrue(s.hit(110,215,2));assertFalse(s.hit(100,230,2));}

    @Test public void concaveLassoSelectsInkAlongItsActualBoundary() {
        Ink ink = new Ink();
        Ink.Stroke inside = new Ink.Stroke(ink.nextId(), 0xff000000, 2, 15, 80, new float[]{0,0,1});
        Ink.Stroke notch = new Ink.Stroke(ink.nextId(), 0xff000000, 2, 80, 80, new float[]{0,0,1});
        Ink.Stroke crossing = new Ink.Stroke(ink.nextId(), 0xff000000, 2, -15, 50,
                new float[]{0,0,1,55,0,1}, true);
        ink.add(inside);
        ink.add(notch);
        ink.add(crossing);
        Lasso.Trace trace = new Lasso.Trace();
        double[] path = {0,0, 100,0, 100,25, 25,25, 25,100, 0,100};
        for (int i = 0; i < path.length; i += 2) trace.add(path[i], path[i + 1], 1);
        Lasso lasso = trace.finish(4);
        assertNotNull(lasso);
        ink.select(lasso);
        assertTrue(ink.selected.contains(inside.id));
        assertTrue(ink.selected.contains(crossing.id));
        assertFalse(ink.selected.contains(notch.id));
        Lasso resized = lasso.transform(2, 30, 40);
        assertTrue(resized.contains(60, 200));
        assertFalse(resized.contains(190, 200));
    }
}
