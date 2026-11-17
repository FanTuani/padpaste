package io.github.fantuani.draftcanvas;

import android.app.*;
import android.os.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int INK=0xFF243137,TEAL=0xFF167B70,MUTED=0xFF7F8C8C;
    CanvasView canvas;
    private TextView pen,select,erase,undo,redo,paste,zoom,selectionLabel;
    private LinearLayout selectionBar;
    private final Map<Integer,Swatch> swatches=new LinkedHashMap<>();
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    @Override public void onCreate(Bundle state){super.onCreate(state);if(Build.VERSION.SDK_INT>=27)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);Ink ink=getLastNonConfigurationInstance() instanceof Ink?(Ink)getLastNonConfigurationInstance():new Ink();build(ink);}
    @Override public Object onRetainNonConfigurationInstance(){return canvas.ink;}
    private TextView action(String title,Runnable clicked){TextView t=new TextView(this);t.setText(title);t.setTextSize(14);t.setTextColor(INK);t.setGravity(Gravity.CENTER);t.setPadding(dp(14),0,dp(14),0);t.setMinWidth(dp(48));t.setContentDescription(title);t.setOnClickListener(v->clicked.run());return t;}
    private void add(LinearLayout row,View child,int width){row.addView(child,new LinearLayout.LayoutParams(width<0?width:dp(width),dp(44)));}
    private void divider(LinearLayout row){View line=new View(this);line.setBackgroundColor(0xFFE3E8E5);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(1),dp(24));p.leftMargin=dp(9);p.rightMargin=dp(9);p.gravity=Gravity.CENTER_VERTICAL;row.addView(line,p);}
    private LinearLayout bar(){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(8),dp(6),dp(8),dp(6));row.setBackground(bg(Color.WHITE,20));row.setElevation(dp(5));return row;}
    private void build(Ink ink){
        FrameLayout root=new FrameLayout(this);root.setFitsSystemWindows(true);root.setBackgroundColor(0xFFFAFAF7);canvas=new CanvasView(this,ink);root.addView(canvas,new FrameLayout.LayoutParams(-1,-1));
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);scroll.setClipToPadding(false);scroll.setPadding(dp(14),dp(10),dp(14),dp(12));
        LinearLayout tools=bar();TextView brand=action("草稿",()->canvas.overview());brand.setTextSize(20);brand.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));add(tools,brand,-2);divider(tools);
        pen=action("书写",()->canvas.tool(CanvasView.PEN));select=action("框选",()->{canvas.tool(CanvasView.SELECT);hint("用笔框选，拖动选区");});erase=action("橡皮",()->canvas.tool(CanvasView.ERASE));add(tools,pen,64);add(tools,select,64);add(tools,erase,64);divider(tools);
        for(int c:new int[]{INK,0xFF3169CA,0xFFCF4941,0xFF167B70,0xFFBA761F,0xFF8655B2}){Swatch swatch=new Swatch(c);swatches.put(c,swatch);add(tools,swatch,40);swatch.setOnClickListener(v->{canvas.color=c;canvas.tool(CanvasView.PEN);update();});}
        TextView size=action("粗细",()->showWidths());add(tools,size,60);divider(tools);
        undo=action("撤销",()->{canvas.tool(canvas.mode);ink.undo();canvas.refresh();});redo=action("重做",()->{canvas.tool(canvas.mode);ink.redo();canvas.refresh();});paste=action("粘贴",()->paste());add(tools,undo,60);add(tools,redo,60);add(tools,paste,60);
        scroll.addView(tools);FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);tp.topMargin=dp(10);root.addView(scroll,tp);
        selectionBar=bar();selectionLabel=action("",()->{});add(selectionBar,selectionLabel,-2);divider(selectionBar);add(selectionBar,action("复制",()->{ink.copy();update();hint("已复制选区");}),64);add(selectionBar,action("粘贴",this::paste),64);add(selectionBar,action("删除",()->{ink.deleteSelected();canvas.refresh();}),64);add(selectionBar,action("取消",()->{ink.selected.clear();canvas.refresh();}),64);
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);sp.bottomMargin=dp(getResources().getConfiguration().screenWidthDp<840?80:24);root.addView(selectionBar,sp);
        LinearLayout position=new LinearLayout(this);position.setGravity(Gravity.CENTER_VERTICAL);zoom=action("100%",()->{canvas.ink.view.scale=1;canvas.refresh();});zoom.setTextColor(MUTED);add(position,zoom,-2);TextView overview=action("总览",()->canvas.overview());overview.setTextColor(MUTED);add(position,overview,-2);FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-2,dp(48),Gravity.BOTTOM|Gravity.LEFT);pp.leftMargin=dp(16);pp.bottomMargin=dp(14);root.addView(position,pp);
        TextView clear=action("清空",()->{if(ink.strokes.isEmpty())return;new AlertDialog.Builder(this).setTitle("清空草稿？").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{ink.clear();canvas.refresh();}).show();});clear.setTextColor(MUTED);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(72),dp(48),Gravity.BOTTOM|Gravity.RIGHT);cp.rightMargin=dp(16);cp.bottomMargin=dp(14);root.addView(clear,cp);
        canvas.changed=this::update;setContentView(root);update();
    }
    private void hint(String value){Toast.makeText(this,value,Toast.LENGTH_SHORT).show();}
    private void paste(){canvas.ink.paste(canvas.getWidth(),canvas.getHeight());canvas.mode=CanvasView.SELECT;canvas.refresh();}
    private void update(){
        for(TextView t:new TextView[]{pen,select,erase}){boolean selected=(t==pen&&canvas.mode==CanvasView.PEN)||(t==select&&canvas.mode==CanvasView.SELECT)||(t==erase&&canvas.mode==CanvasView.ERASE);t.setBackground(bg(selected?0xFFE7F2EE:Color.TRANSPARENT,12));t.setTextColor(selected?TEAL:INK);t.setSelected(selected);}
        undo.setEnabled(canvas.ink.canUndo());undo.setAlpha(undo.isEnabled()?1:.3f);redo.setEnabled(canvas.ink.canRedo());redo.setAlpha(redo.isEnabled()?1:.3f);paste.setEnabled(canvas.ink.canPaste());paste.setAlpha(paste.isEnabled()?1:.3f);
        int n=canvas.ink.selected.size();selectionBar.setVisibility(n>0?View.VISIBLE:View.GONE);selectionLabel.setText("已选 "+n+" 笔");String z=String.format(Locale.ROOT,canvas.ink.view.scale>=.1?"%.0f%%":"%.2f%%",canvas.ink.view.scale*100);if(!zoom.getText().toString().equals(z))zoom.setText(z);
        for(Swatch s:swatches.values())s.invalidate();
    }
    private void showWidths(){final String[] labels={"极细","细","中","粗"};final float[] values={1.2f,2.3f,3.8f,6};new AlertDialog.Builder(this).setTitle("笔画粗细").setItems(labels,(d,i)->{canvas.width=dp(values[i]);canvas.tool(CanvasView.PEN);}).show();}
    private final class Swatch extends View {
        private final int color;private final Paint p=new Paint(3);Swatch(int c){super(MainActivity.this);color=c;setContentDescription(color==INK?"墨黑":color==0xFF3169CA?"蓝色":color==0xFFCF4941?"红色":color==0xFF167B70?"绿色":color==0xFFBA761F?"橙色":"紫色");setClickable(true);setFocusable(true);}
        @Override protected void onDraw(android.graphics.Canvas c){float x=getWidth()/2f,y=getHeight()/2f;p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawCircle(x,y,dp(10),p);if(canvas.color==color){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1.5f));c.drawCircle(x,y,dp(14),p);}}
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
