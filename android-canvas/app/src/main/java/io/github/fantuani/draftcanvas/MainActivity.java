package io.github.fantuani.draftcanvas;

import android.app.*;
import android.content.SharedPreferences;
import android.os.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int INK = 0xFF243137, TEAL = 0xFF167B70;
    CanvasView canvas;
    private DraftStore draftStore;
    private SharedPreferences toolSizes;
    private FanMenu menu;
    private TextView zoom;
    private double shownScale = Double.NaN;
    private PenButtons penButtons;
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
        draftStore = DraftStore.get(this);
        toolSizes = getSharedPreferences("tool_sizes", MODE_PRIVATE);
        draftStore.open(ink -> {
            if (!isDestroyed() && !isFinishing()) build(ink);
        });
    }

    private void build(Ink ink) {
        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.setMotionEventSplittingEnabled(false);
        root.setBackgroundColor(0xFFFAFAF7);
        canvas = new CanvasView(this, ink);
        float density = getResources().getDisplayMetrics().density;
        canvas.width = toolSizes.getFloat("pen_width_dp", 2.3f) * density;
        canvas.setEraserSize(toolSizes.getFloat("eraser_size_dp", 24f) * density);
        canvas.eraserSensitivity = toolSizes.getFloat("eraser_sensitivity", 2f);
        root.addView(canvas, new FrameLayout.LayoutParams(-1, -1));
        zoom = new TextView(this);
        zoom.setTextColor(0xFF7F8C8C);
        zoom.setTextSize(13);
        zoom.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        zoom.setMinWidth(dp(60));
        zoom.setMinHeight(dp(48));
        zoom.setOnClickListener(view -> canvas.resetZoom());
        FrameLayout.LayoutParams label = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        label.setMarginStart(dp(16));
        label.bottomMargin = dp(1);
        root.addView(zoom, label);
        canvas.changed = () -> {
            updateZoom();
            draftStore.observe();
        };
        updateZoom();
        menu = new FanMenu(this, new FanMenu.Actions() {
            @Override public void color(int color) { canvas.setPenColor(color); }
            @Override public void command(int id) { menuCommand(id); }
        });
        root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        canvas.setOnClickListener(view -> {
            PointF anchor = canvas.selectionMenuAnchor();
            menu.showSelection(anchor.x, anchor.y, !ink.selected.isEmpty(), ink.canPaste());
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
                if (canvas.selectionArmed()) canvas.clearSelection();
                showTools(canvas.menuAnchor());
            }
            @Override public void beginSelection() {
                menu.dismiss(false);
                canvas.armSelection();
            }
            @Override public void endSelection(boolean canceled) {
                if (canceled && canvas.selectionArmed()) canvas.clearSelection();
            }
        });
        setContentView(root);
    }

    private void showTools(PointF p) {
        if (menu.hasToolsAt(p.x, p.y)) return;
        canvas.finishContactForMenu();
        menu.showTools(p.x, p.y, canvas.color, !canvas.ink.strokes.isEmpty());
    }

    private void menuCommand(int id) {
        Ink ink = canvas.ink;
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

    @Override protected void onPause() {
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
        if (canvas != null && event.isCtrlPressed() && event.getKeyCode() == KeyEvent.KEYCODE_Z
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
        if (menu == null) super.onBackPressed();
        else if (menu.isOpen()) menu.dismiss(true);
        else if (canvas.selectionArmed() || canvas.hasSelectionBox()) canvas.clearSelection();
        else super.onBackPressed();
    }

    private void showWidths(){
        float density=getResources().getDisplayMetrics().density;
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(24),dp(8),dp(24),dp(8));panel.setMinimumWidth(dp(340));
        View preview=new View(this){private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);@Override protected void onDraw(android.graphics.Canvas c){
            float y=getHeight()/2f;p.setColor(canvas.color);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(canvas.width);c.drawLine(getWidth()*.08f,y,getWidth()*.45f,y,p);
            p.setStyle(Paint.Style.FILL);p.setColor(0x10167B70);c.drawCircle(getWidth()*.75f,y,canvas.eraserSize/2,p);p.setStyle(Paint.Style.STROKE);p.setColor(TEAL);p.setStrokeWidth(dp(1));c.drawCircle(getWidth()*.75f,y,canvas.eraserSize/2,p);
        }};
        panel.addView(preview,new LinearLayout.LayoutParams(-1,dp(110)));
        panel.addView(sizeControl("笔粗细",.3f,10,.1f,canvas.width/density,value->{canvas.width=value*density;toolSizes.edit().putFloat("pen_width_dp",value).apply();preview.invalidate();}));
        panel.addView(sizeControl("橡皮大小",6,96,1,canvas.eraserSize/density,value->{canvas.setEraserSize(value*density);toolSizes.edit().putFloat("eraser_size_dp",value).apply();preview.invalidate();}));
        panel.addView(sizeControl("橡皮压感灵敏度",.5f,4,.1f,canvas.eraserSensitivity,value->{canvas.eraserSensitivity=value;toolSizes.edit().putFloat("eraser_sensitivity",value).apply();}));
        new AlertDialog.Builder(this).setTitle("笔与橡皮").setView(panel).setPositiveButton("完成",null).show();
    }
    private View sizeControl(String title,float min,float max,float step,float current,java.util.function.Consumer<Float> change){
        LinearLayout group=new LinearLayout(this);group.setOrientation(LinearLayout.VERTICAL);
        TextView label=new TextView(this);label.setTextColor(INK);label.setTextSize(16);label.setText(title+"  "+String.format(Locale.ROOT,"%.1f",current));group.addView(label);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        SeekBar slider=new SeekBar(this);slider.setMax(Math.round((max-min)/step));slider.setProgress(Math.round((current-min)/step));slider.setContentDescription(title);slider.setProgressTintList(android.content.res.ColorStateList.valueOf(TEAL));slider.setThumbTintList(android.content.res.ColorStateList.valueOf(TEAL));
        TextView minus=action("−",()->slider.setProgress(slider.getProgress()-1)),plus=action("+",()->slider.setProgress(slider.getProgress()+1));
        row.addView(minus,new LinearLayout.LayoutParams(dp(44),dp(56)));row.addView(slider,new LinearLayout.LayoutParams(0,dp(56),1));row.addView(plus,new LinearLayout.LayoutParams(dp(44),dp(56)));group.addView(row);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int value,boolean user){float v=min+value*step;label.setText(title+"  "+String.format(Locale.ROOT,"%.1f",v));change.accept(v);}});
        return group;
    }
}
