package io.github.fantuani.draftcanvas;

import android.app.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final String OPEN_SYNC = "io.github.fantuani.draftcanvas.OPEN_SYNC";
    private static final int INK = 0xFF243137, TEAL = 0xFF167B70;
    private static final String PEN_PRESSURE_THRESHOLD = "pen_pressure_threshold";
    private static final String PEN_SENSITIVITY = "pen_sensitivity";
    private static final float MAX_PEN_SENSITIVITY = 2f;
    CanvasView canvas;
    private FrameLayout canvasPage;
    private FrameLayout syncPage;
    private DraftStore draftStore;
    private SharedPreferences toolSizes;
    private FanMenu menu;
    private long menuQuestionId;
    private TextView zoom;
    private double shownScale = Double.NaN;
    private PenButtons penButtons;
    private QuestionInbox questions;
    private boolean resumed;
    private final Handler penHandler = new Handler(Looper.getMainLooper());
    private final Runnable lowerHold = () -> penButtons.hold(SystemClock.uptimeMillis());
    private final Runnable upperHold = () -> penButtons.holdUpper(SystemClock.uptimeMillis());

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 27) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFFFAFAF7));
        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.setMotionEventSplittingEnabled(false);
        root.setBackgroundColor(0xFFFAFAF7);
        canvasPage = new FrameLayout(this);
        canvasPage.setMotionEventSplittingEnabled(false);
        root.addView(canvasPage, new FrameLayout.LayoutParams(-1, -1));
        syncPage = new FrameLayout(this);
        syncPage.setId(R.id.sync_page);
        root.addView(syncPage, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        getFragmentManager().addOnBackStackChangedListener(this::updatePageVisibility);
        updatePageVisibility();

        draftStore = DraftStore.get(this);
        toolSizes = getSharedPreferences("tool_sizes", MODE_PRIVATE);
        draftStore.open(ink -> {
            if (!isDestroyed() && !isFinishing()) build(ink);
        });
        openSyncFromIntent(getIntent());
    }

    private void build(Ink ink) {
        FrameLayout root = canvasPage;
        canvas = new CanvasView(this, ink);
        float density = getResources().getDisplayMetrics().density;
        canvas.width = toolSizes.getFloat("pen_width_dp", 2.3f) * density;
        canvas.setPenPressureThreshold(toolSizes.getInt(PEN_PRESSURE_THRESHOLD, 0));
        canvas.penSensitivity = toolSizes.getFloat(PEN_SENSITIVITY, CanvasView.DEFAULT_PEN_SENSITIVITY);
        canvas.setEraserSize(toolSizes.getFloat("eraser_size_dp", 24f) * density);
        canvas.eraserSensitivity = toolSizes.getFloat("eraser_sensitivity", 2f);
        root.addView(canvas, new FrameLayout.LayoutParams(-1, -1));
        zoom = new TextView(this);
        zoom.setTextColor(0xFF7F8C8C);
        zoom.setTextSize(13);
        zoom.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        zoom.setMinWidth(dp(60));
        zoom.setMinHeight(dp(48));
        zoom.setOnClickListener(view -> {
            canvas.selectQuestion(null);
            canvas.resetZoom();
        });
        FrameLayout.LayoutParams label = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        label.setMarginStart(dp(16));
        label.bottomMargin = dp(1);
        root.addView(zoom, label);
        canvas.changed = () -> {
            updateZoom();
            draftStore.observe();
        };
        updateZoom();
        questions = new QuestionInbox(this, root, canvas, draftStore);
        if (resumed) questions.start();
        menu = new FanMenu(this, new FanMenu.Actions() {
            @Override public void color(int color) { canvas.setPenColor(color); }
            @Override public void command(int id) { menuCommand(id); }
            @Override public void penOutside(float x, float y) { canvas.dismissSelectionOutside(x, y); }
        });
        menu.setElevation(dp(6));
        root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        canvas.setOnClickListener(view -> {
            PointF anchor = canvas.selectionMenuAnchor();
            QuestionImage image = canvas.selectedQuestion();
            menuQuestionId = image != null ? image.id : 0;
            menu.showSelection(anchor.x, anchor.y, image != null || !ink.selected.isEmpty(),
                    image != null ? ink.canPasteQuestions() : ink.canPaste());
        });
        penButtons = new PenButtons(new PenButtons.Listener() {
            @Override public void eraser(boolean held) {
                if (held) menu.dismiss(false);
                canvas.temporaryEraser(held);
            }
            @Override public void undo() {
                menu.dismiss(false);
                canvas.undo();
            }
            @Override public void menu() {
                if (menu.isOpen()) {
                    menu.dismiss(true);
                    return;
                }
                if (canvas.selectionArmed()) canvas.clearSelection();
                PointF anchor = canvas.menuAnchor();
                canvas.finishContactForMenu();
                menuQuestionId = 0;
                menu.showTools(anchor.x, anchor.y, canvas.color, !ink.strokes.isEmpty());
            }
            @Override public void beginSelection() {
                menu.dismiss(false);
                canvas.armSelection();
            }
            @Override public void endSelection(boolean canceled) {
                if (canceled && canvas.selectionArmed()) canvas.clearSelection();
            }
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openSyncFromIntent(intent);
    }

    private void openSyncFromIntent(Intent intent) {
        if (!OPEN_SYNC.equals(intent.getAction()) && !intent.getBooleanExtra("open_sync", false)) return;
        intent.removeExtra("open_sync");
        intent.setAction(Intent.ACTION_MAIN);
        openSync();
    }

    private void openSync() {
        FragmentManager fragments = getFragmentManager();
        if (fragments.findFragmentById(R.id.sync_page) != null || fragments.isStateSaved()) return;
        resetPenButtons();
        if (canvas != null) canvas.settleForSave();
        fragments.beginTransaction().add(R.id.sync_page, new SyncFragment()).addToBackStack(null).commit();
    }

    private boolean syncVisible() {
        return getFragmentManager().findFragmentById(R.id.sync_page) != null;
    }

    private void updatePageVisibility() {
        boolean showSync = syncVisible();
        canvasPage.setVisibility(showSync ? View.INVISIBLE : View.VISIBLE);
        syncPage.setVisibility(showSync ? View.VISIBLE : View.GONE);
    }

    void receiveSettingChanged() {
        if (questions != null) questions.updateSubscription();
    }

    private void menuCommand(int id) {
        Ink ink = canvas.ink;
        long imageId = menuQuestionId;
        menuQuestionId = 0;
        if (imageId != 0) {
            QuestionImage image = canvas.selectedQuestion();
            if (image == null || image.id != imageId) return;
            switch (id) {
                case FanMenu.COPY:
                    ink.copyQuestion(image);
                    break;
                case FanMenu.PASTE:
                    canvas.pasteSelection();
                    break;
                case FanMenu.DELETE:
                    ink.deleteQuestion(image);
                    canvas.selectQuestion(null);
                    canvas.refresh();
                    break;
                case FanMenu.CANCEL:
                    canvas.selectQuestion(null);
                    break;
                default:
            }
            return;
        }
        switch (id) {
            case FanMenu.SELECT:
                canvas.armSelection();
                break;
            case FanMenu.SIZE:
                showWidths();
                break;
            case FanMenu.CLEAR:
                canvas.clearSelection();
                ink.clear();
                canvas.refresh();
                draftStore.flush();
                break;
            case FanMenu.OVERVIEW:
                canvas.overview();
                break;
            case FanMenu.SYNC:
                openSync();
                break;
            case FanMenu.COPY:
                ink.copy();
                break;
            case FanMenu.PASTE:
                canvas.pasteSelection();
                break;
            case FanMenu.DELETE:
                ink.deleteSelected();
                canvas.clearSelection();
                canvas.refresh();
                break;
            case FanMenu.CANCEL:
                canvas.clearSelection();
                break;
            default:
        }
    }

    private void updateZoom() {
        double scale = canvas.ink.view.scale;
        if (scale == shownScale) return;
        shownScale = scale;
        String percent = String.format(Locale.ROOT, scale < .1 ? "%.2f%%" : "%.0f%%", scale * 100);
        zoom.setText(percent);
        zoom.setContentDescription("缩放 " + percent + "，轻点恢复到 100%");
    }

    private TextView action(String title, Runnable clicked) {
        TextView text = new TextView(this);
        text.setText(title);
        text.setTextSize(14);
        text.setTextColor(INK);
        text.setGravity(Gravity.CENTER);
        text.setOnClickListener(view -> clicked.run());
        return text;
    }

    private boolean isPenKeyboard(KeyEvent event){
        InputDevice device=event.getDevice();if(device==null)return false;
        String name=device.getName().toLowerCase(Locale.ROOT);
        return name.contains("smart pen")||name.contains("stylus")||device.supportsSource(InputDevice.SOURCE_STYLUS);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(penButtons==null)return super.dispatchKeyEvent(event);
        int key=event.getKeyCode();boolean legacy=isPenKeyboard(event);
        boolean lower=legacy&&key==KeyEvent.KEYCODE_PAGE_DOWN,upper=legacy&&key==KeyEvent.KEYCODE_PAGE_UP;
        if(Build.VERSION.SDK_INT>=34){lower|=key==KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY;upper|=key==KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY;}
        if((lower||upper)&&(event.getAction()==KeyEvent.ACTION_DOWN||event.getAction()==KeyEvent.ACTION_UP)){
            if (syncVisible()) return true;
            boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
            if(lower){
                penButtons.lower(down,event.getEventTime(),event.isCanceled());
                penHandler.removeCallbacks(lowerHold);
                if(down&&!event.isCanceled())penHandler.postAtTime(lowerHold,penButtons.lowerDeadline());
            } else {
                penHandler.removeCallbacks(upperHold);
                if (key == KeyEvent.KEYCODE_PAGE_UP) {
                    // HyperOS owns the long press and may deliver a queued DOWN much later.
                    penButtons.menuOnly(down, event.isCanceled());
                } else {
                    penButtons.upper(down, event.getEventTime(), event.isCanceled());
                    if (down && !event.isCanceled()) penHandler.postAtTime(upperHold, penButtons.upperDeadline());
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private void resetPenButtons() {
        penHandler.removeCallbacks(lowerHold);
        penHandler.removeCallbacks(upperHold);
        if (penButtons != null) penButtons.reset();
        if (canvas != null && canvas.selectionArmed()) canvas.clearSelection();
        if (menu != null) menu.dismiss(false);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (questions != null) questions.start();
    }

    @Override protected void onPause() {
        resumed = false;
        if (questions != null) questions.stop();
        if (canvas != null) canvas.settleForSave();
        resetPenButtons();
        draftStore.flush();
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        draftStore.awaitWrites(450);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) resetPenButtons();
    }

    @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        if (canvas != null && !syncVisible() && event.isCtrlPressed() && event.getKeyCode() == KeyEvent.KEYCODE_Z
                && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.isShiftPressed()) {
                canvas.clearSelection();
                canvas.ink.redo();
                canvas.refresh();
            } else canvas.undo();
            return true;
        }
        return super.dispatchKeyShortcutEvent(event);
    }

    @Override public void onBackPressed() {
        if (syncVisible()) getFragmentManager().popBackStack();
        else if (menu == null) super.onBackPressed();
        else if (menu.isOpen()) menu.dismiss(true);
        else if (canvas.selectionArmed() || canvas.hasSelectionBox()) canvas.clearSelection();
        else super.onBackPressed();
    }

    private void showWidths(){
        float density=getResources().getDisplayMetrics().density;
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(24),dp(8),dp(24),dp(8));panel.setMinimumWidth(dp(340));
        View preview=new View(this){
            private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path sample = new Path();
            @Override protected void onDraw(android.graphics.Canvas c){
            float y=getHeight()/2f, start=getWidth()*.08f, end=getWidth()*.45f;
            float r1=canvas.width*StrokeGeometry.pressureWidth(.04f,canvas.penSensitivity)/2;
            float r2=canvas.width*StrokeGeometry.pressureWidth(.85f,canvas.penSensitivity)/2;
            p.setColor(canvas.color);p.setStyle(Paint.Style.FILL);
            sample.rewind();
            sample.addCircle(start,y,r1,Path.Direction.CCW);
            PressurePath.quadratic(sample,start,y,r1,(start+end)/2,y,(r1+r2)/2,end,y,r2,end-start);
            c.drawPath(sample,p);
            p.setStyle(Paint.Style.FILL);p.setColor(0x10167B70);c.drawCircle(getWidth()*.75f,y,canvas.eraserSize/2,p);p.setStyle(Paint.Style.STROKE);p.setColor(TEAL);p.setStrokeWidth(dp(1));c.drawCircle(getWidth()*.75f,y,canvas.eraserSize/2,p);
        }};
        panel.addView(preview,new LinearLayout.LayoutParams(-1,dp(110)));
        panel.addView(sizeControl("笔粗细",.3f,10,.1f,canvas.width/density,"%.1f",value->{canvas.width=value*density;toolSizes.edit().putFloat("pen_width_dp",value).apply();preview.invalidate();}));
        panel.addView(sizeControl("书写压感灵敏度", 0, MAX_PEN_SENSITIVITY, .1f, canvas.penSensitivity,
                "%.1f", value -> {
                    canvas.penSensitivity = value;
                    toolSizes.edit().putFloat(PEN_SENSITIVITY, value).apply();
                    preview.invalidate();
                }));
        panel.addView(sizeControl("书写力度阈值", 0, CanvasView.MAX_PEN_PRESSURE_THRESHOLD, 1,
                toolSizes.getInt(PEN_PRESSURE_THRESHOLD, 0), "%.0f", value -> {
                    int level = Math.round(value);
                    canvas.setPenPressureThreshold(level);
                    toolSizes.edit().putInt(PEN_PRESSURE_THRESHOLD, level).apply();
                }));
        panel.addView(sizeControl("橡皮大小",6,96,1,canvas.eraserSize/density,"%.1f",value->{canvas.setEraserSize(value*density);toolSizes.edit().putFloat("eraser_size_dp",value).apply();preview.invalidate();}));
        panel.addView(sizeControl("橡皮压感灵敏度",.5f,4,.1f,canvas.eraserSensitivity,"%.1f",value->{canvas.eraserSensitivity=value;toolSizes.edit().putFloat("eraser_sensitivity",value).apply();}));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        new AlertDialog.Builder(this).setTitle("笔与橡皮").setView(scroll).setPositiveButton("完成",null).show();
    }
    private View sizeControl(String title,float min,float max,float step,float current,String format,java.util.function.Consumer<Float> change){
        LinearLayout group=new LinearLayout(this);group.setOrientation(LinearLayout.VERTICAL);
        TextView label=new TextView(this);label.setTextColor(INK);label.setTextSize(16);label.setText(title+"  "+String.format(Locale.ROOT,format,current));group.addView(label);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        SeekBar slider=new SeekBar(this);slider.setMax(Math.round((max-min)/step));slider.setProgress(Math.round((current-min)/step));slider.setContentDescription(title);slider.setProgressTintList(android.content.res.ColorStateList.valueOf(TEAL));slider.setThumbTintList(android.content.res.ColorStateList.valueOf(TEAL));
        TextView minus=action("−",()->slider.setProgress(slider.getProgress()-1)),plus=action("+",()->slider.setProgress(slider.getProgress()+1));
        row.addView(minus,new LinearLayout.LayoutParams(dp(44),dp(56)));row.addView(slider,new LinearLayout.LayoutParams(0,dp(56),1));row.addView(plus,new LinearLayout.LayoutParams(dp(44),dp(56)));group.addView(row);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int value,boolean user){float v=min+value*step;label.setText(title+"  "+String.format(Locale.ROOT,format,v));change.accept(v);}});
        return group;
    }
}
