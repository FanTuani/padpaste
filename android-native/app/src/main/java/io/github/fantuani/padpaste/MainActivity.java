package io.github.fantuani.padpaste;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int ACCENT=0xFF167B70,INK=0xFF182B30,MUTED=0xFF75858A,GREEN=0xFF167B70;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private TextView connectionTitle,connectionSubtitle,monitorTitle,lastTransfer,lastDuration,errorText,permissionText;
    private Button retry;
    private android.app.Dialog settingsDialog;
    private ConnectionArtwork artwork;
    private EditText server,user,password,directory;
    private SyncToggle enabled;
    private boolean rendering;
    private volatile boolean checking;
    private long lastCheck;
    private LinearLayout content;
    private final Runnable refresh=new Runnable(){public void run(){render();if(System.currentTimeMillis()-lastCheck>20000)checkConnection();ui.postDelayed(this,1000);}};
    private final BroadcastReceiver stateReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){render();}};

    @Override public void onCreate(Bundle saved){super.onCreate(saved);buildUi();fillConfig();}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void gap(LinearLayout parent,int height){parent.addView(new Space(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private void rule(LinearLayout parent){View line=new View(this);line.setBackgroundColor(0xFFE0E5E6);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
    private Button button(String title,boolean primary){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?Color.WHITE:INK);b.setBackground(background(primary?ACCENT:0xFFE9EEEF,12));b.setMinHeight(dp(48));b.setMinimumHeight(0);b.setStateListAnimator(null);b.setPadding(dp(18),0,dp(18),0);return b;}
    private void action(LinearLayout parent,String title,Runnable action){Button b=button(title,false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.topMargin=dp(10);parent.addView(b,p);b.setOnClickListener(v->action.run());}
    private EditText field(LinearLayout parent,String title,String hint,boolean secret){
        parent.addView(text(title,13,MUTED,false));gap(parent,8);EditText f=new EditText(this);f.setTextSize(15);f.setSingleLine(true);f.setTextColor(INK);f.setHint(hint);f.setPadding(dp(14),0,dp(14),0);f.setBackground(background(0xFFF1F4F4,10));
        f.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        parent.addView(f,new LinearLayout.LayoutParams(-1,dp(48)));gap(parent,20);return f;
    }
    private void buildUi(){
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(0xFFF7F9F9);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout shell=column();shell.setGravity(Gravity.CENTER);scroll.addView(shell,new ScrollView.LayoutParams(-1,-1));
        int available=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);boolean wide=available>=840;
        content=column();content.setPadding(dp(wide?44:28),dp(28),dp(wide?44:28),dp(32));shell.addView(content,new LinearLayout.LayoutParams(dp(Math.min(available,1056)),-2));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=text("PadPaste",27,INK,true);brand.setLetterSpacing(-0.035f);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button settings=button("设置",false);settings.setBackgroundColor(Color.TRANSPARENT);header.addView(settings,new LinearLayout.LayoutParams(dp(76),dp(48)));settings.setOnClickListener(v->showSettings());content.addView(header);
        gap(content,wide?44:28);
        LinearLayout connection=new LinearLayout(this);connection.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);connection.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout status=column();status.setGravity(Gravity.CENTER_VERTICAL);
        connectionTitle=text("正在连接",wide?52:42,INK,true);connectionTitle.setLetterSpacing(-0.04f);status.addView(connectionTitle);gap(status,16);
        connectionSubtitle=text("",15,MUTED,false);connectionSubtitle.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));status.addView(connectionSubtitle);gap(status,22);
        retry=button("重新检测  ↗",false);retry.setTextColor(ACCENT);retry.setBackgroundColor(Color.TRANSPARENT);retry.setPadding(0,0,dp(16),0);status.addView(retry,new LinearLayout.LayoutParams(-2,dp(48)));retry.setOnClickListener(v->checkConnection());
        artwork=new ConnectionArtwork(this);artwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if(wide){connection.addView(status,new LinearLayout.LayoutParams(0,dp(220),1));connection.addView(artwork,new LinearLayout.LayoutParams(dp(350),dp(220)));}
        else{connection.addView(status,new LinearLayout.LayoutParams(-1,-2));connection.addView(artwork,new LinearLayout.LayoutParams(-1,dp(150)));}
        content.addView(connection);gap(content,wide?36:20);rule(content);gap(content,24);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names=column();names.addView(text("截图同步",19,INK,true));gap(names,8);monitorTitle=text("",14,MUTED,false);names.addView(monitorTitle);row.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        enabled=new SyncToggle(this);enabled.setContentDescription("截图同步");row.addView(enabled,new LinearLayout.LayoutParams(dp(108),dp(56)));content.addView(row);
        enabled.setOnCheckedChangeListener((b,on)->{if(rendering)return;if(on)enableMonitoring();else{Config.prefs(this).edit().putBoolean("enabled",false).apply();if(SyncState.running)startService(new Intent(this,SyncService.class).setAction(SyncService.STOP));render();}});
        gap(content,24);rule(content);gap(content,24);
        LinearLayout stats=new LinearLayout(this);LinearLayout latest=column();latest.addView(text("最近同步",12,MUTED,false));gap(latest,10);lastTransfer=text("—",24,INK,true);latest.addView(lastTransfer);stats.addView(latest,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout timing=column();timing.addView(text("传输耗时",12,MUTED,false));gap(timing,10);lastDuration=text("—",24,INK,true);timing.addView(lastDuration);stats.addView(timing,new LinearLayout.LayoutParams(0,-2,1));content.addView(stats);
        errorText=text("",13,0xFFAC513D,false);LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,-2);ep.topMargin=dp(20);content.addView(errorText,ep);
        buildSettings();setContentView(frame);content.setAlpha(0);content.setTranslationY(dp(8));content.animate().alpha(1).translationY(0).setDuration(220).start();
    }
    private void buildSettings(){
        settingsDialog=new android.app.Dialog(this);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
        LinearLayout body=column();body.setPadding(dp(28),dp(24),dp(28),dp(28));scroll.addView(body);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);heading.addView(text("设置",24,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        Button close=button("完成",false);close.setBackgroundColor(Color.TRANSPARENT);heading.addView(close,new LinearLayout.LayoutParams(dp(72),dp(48)));close.setOnClickListener(v->settingsDialog.dismiss());body.addView(heading);gap(body,12);
        action(body,"导入配对文件",()->{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,101);});gap(body,28);
        server=field(body,"Mac 地址","http://your-mac.local:5034",false);server.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        user=field(body,"用户名","padpaste",false);password=field(body,"配对密码","",true);directory=field(body,"截图目录","/storage/emulated/0/DCIM/Screenshots",false);
        Button save=button("保存配对",true);body.addView(save,new LinearLayout.LayoutParams(-1,dp(48)));save.setOnClickListener(v->{saveConfig();if(!new Config(this).valid()){toast("请检查 Mac 地址、用户名和密码");return;}if(Config.prefs(this).getBoolean("enabled",false))startForegroundService(new Intent(this,SyncService.class).setAction(SyncService.RECONFIGURE));settingsDialog.dismiss();checkConnection();toast("配对已保存");});
        gap(body,28);rule(body);gap(body,20);permissionText=text("",14,INK,false);body.addView(permissionText);action(body,"文件访问权限",this::requestFiles);action(body,"后台运行设置",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        gap(body,24);body.addView(text("仅剪贴板：传输后清理平板截图，失败不重传。",13,MUTED,false));
        settingsDialog.setOnDismissListener(d->fillConfig());
        settingsDialog.setContentView(scroll);android.view.Window window=settingsDialog.getWindow();if(window!=null){window.setBackgroundDrawable(background(Color.WHITE,24));window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
    }
    private void showSettings(){
        render();settingsDialog.show();android.view.Window window=settingsDialog.getWindow();if(window!=null){int w=getResources().getDisplayMetrics().widthPixels,h=getResources().getDisplayMetrics().heightPixels;window.setLayout(Math.min(dp(520),w-dp(32)),Math.min(dp(640),h-dp(64)));}
        settingsDialog.getWindow().getDecorView().setAlpha(0);settingsDialog.getWindow().getDecorView().animate().alpha(1).setDuration(160).start();
    }
    private final class SyncToggle extends CompoundButton {
        private final android.graphics.Paint paint=new android.graphics.Paint(3);
        private float position;
        private android.animation.ValueAnimator movement;
        SyncToggle(Context context){super(context,null,0);setButtonDrawable(null);setBackgroundColor(Color.TRANSPARENT);setClickable(true);setFocusable(true);}
        @Override public void setChecked(boolean checked){
            boolean changed=checked!=isChecked();super.setChecked(checked);
            if(!changed)return;if(movement!=null)movement.cancel();
            movement=android.animation.ValueAnimator.ofFloat(position,checked?1:0);movement.setDuration(180);
            movement.addUpdateListener(a->{position=(float)a.getAnimatedValue();invalidate();});movement.start();
        }
        @Override public CharSequence getAccessibilityClassName(){return "android.widget.Switch";}
        @Override protected void onDraw(android.graphics.Canvas canvas){
            float w=getWidth(),h=getHeight(),inset=dp(3),radius=(h-inset*2)/2;
            paint.setStyle(android.graphics.Paint.Style.FILL);paint.setColor((Integer)new android.animation.ArgbEvaluator().evaluate(position,0xFFDFE7E7,ACCENT));
            canvas.drawRoundRect(inset,inset,w-inset,h-inset,radius,radius,paint);
            paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));paint.setTextSize(dp(15));paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            paint.setColor(isChecked()?Color.WHITE:0xFF71848A);String label=isChecked()?"开":"关";
            float textX=isChecked()?w*.27f:w*.73f;canvas.drawText(label,textX,h/2-(paint.ascent()+paint.descent())/2,paint);
            float thumbR=h/2-dp(8),left=h/2,right=w-h/2,x=left+(right-left)*position;
            paint.setColor(Color.WHITE);canvas.drawCircle(x,h/2,thumbR,paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);paint.setColor(isChecked()?ACCENT:0xFF9AA9AD);
            if(isChecked()){canvas.drawLine(x-dp(6),h/2,x-dp(1),h/2+dp(5),paint);canvas.drawLine(x-dp(1),h/2+dp(5),x+dp(7),h/2-dp(5),paint);}
            else canvas.drawLine(x-dp(5),h/2,x+dp(5),h/2,paint);
        }
    }
    private final class ConnectionArtwork extends View {
        private final android.graphics.Paint paint=new android.graphics.Paint(3);private int state=-1;
        ConnectionArtwork(Context context){super(context);}
        void state(int next){if(state!=next){state=next;invalidate();animate().alpha(1).setDuration(180).start();}}
        @Override protected void onDraw(android.graphics.Canvas canvas){super.onDraw(canvas);canvas.save();float scale=Math.min(getWidth()/350f,getHeight()/220f);canvas.translate((getWidth()-350*scale)/2,(getHeight()-220*scale)/2);canvas.scale(scale,scale);
            int line=state==2?ACCENT:0xFFADB9BD;paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(1.6f);paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);paint.setColor(0xFFCAD3D5);
            canvas.drawLine(89,107,232,107,paint);paint.setColor(line);canvas.drawRoundRect(29,64,91,153,9,9,paint);canvas.drawLine(51,143,68,143,paint);
            canvas.drawRoundRect(231,65,342,139,6,6,paint);canvas.drawLine(232,127,341,127,paint);canvas.drawLine(287,139,287,154,paint);canvas.drawLine(265,155,309,155,paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);paint.setColor(state==2?ACCENT:0xFFE4E9EA);canvas.drawCircle(161,107,17,paint);paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(state==2?Color.WHITE:0xFF9AA8AD);
            if(state==2){canvas.drawLine(154,107,159,112,paint);canvas.drawLine(159,112,169,102,paint);}else{canvas.drawLine(155,107,167,107,paint);}canvas.restore();
        }
    }
    private void fillConfig(){Config c=new Config(this);server.setText(c.server);user.setText(c.username);password.setText(c.password);directory.setText(c.directory);}
    private void saveConfig(){Config.save(this,server.getText().toString(),user.getText().toString(),password.getText().toString(),directory.getText().toString());}
    private void enableMonitoring(){
        saveConfig();Config c=new Config(this);
        if(!c.valid()){toast("请先导入配对信息或填写 Mac 地址和密码");render();return;}
        android.content.SharedPreferences p=Config.prefs(this);
        android.content.SharedPreferences.Editor update=p.edit().putBoolean("enabled",true);
        if(!p.getBoolean("enabled",false))update.putLong("baseline",System.currentTimeMillis());
        update.apply();
        if(!SyncService.filesAllowed(this)){requestFiles();render();return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},102);
        startForegroundService(new Intent(this,SyncService.class).setAction(SyncService.RECONFIGURE));render();
    }
    private void requestFiles(){
        if(Build.VERSION.SDK_INT>=30)startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())));
        else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},103);
    }
    private void checkConnection(){
        if(checking)return;lastCheck=System.currentTimeMillis();Config c=new Config(this);if(!c.valid()){render();return;}
        checking=true;lastCheck=System.currentTimeMillis();render();
        network.execute(()->{try{MacClient.check(c);SyncState.connection(this,true,"");}catch(Exception e){SyncState.connection(this,false,SyncService.friendly(e));}finally{checking=false;runOnUiThread(this::render);}});
    }
    private String clock(long ms){return new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(ms));}
    private void label(TextView view,String value){if(!view.getText().toString().equals(value))view.setText(value);}
    private void render(){
        android.content.SharedPreferences p=Config.prefs(this);Config config=new Config(this);
        long confirmed=p.getLong("last_confirmed",0);boolean connected=p.getBoolean("connected",false)&&System.currentTimeMillis()-confirmed<90000;
        String title=connected?"已连接":checking?"正在连接":config.valid()?"未连接":"等待配对";
        if(!connectionTitle.getText().toString().equals(title)){label(connectionTitle,title);connectionTitle.setAlpha(.45f);connectionTitle.animate().alpha(1).setDuration(180).start();}
        connectionTitle.setTextColor(connected?GREEN:INK);artwork.state(connected?2:checking?1:0);
        label(connectionSubtitle,connected?config.endpoint().replaceFirst("^https?://",""):config.valid()?"Mac":"尚未添加 Mac");
        retry.setEnabled(!checking);label(retry,checking?"检测中…":"重新检测  ↗");
        boolean on=p.getBoolean("enabled",false);label(monitorTitle,SyncState.running?(SyncState.phase.equals("正在监控")?"运行中 · 仅剪贴板":SyncState.phase):on?"正在启动":"已暂停");monitorTitle.setTextColor(SyncState.running?GREEN:MUTED);
        rendering=true;if(enabled.isChecked()!=on)enabled.setChecked(on);rendering=false;
        long last=p.getLong("last_upload",0),failure=p.getLong("last_failure",0);label(lastTransfer,last>0?clock(last):"—");
        label(lastDuration,last>0?p.getLong("last_duration",0)+" ms":"—");
        String error=p.getString("last_error","");boolean failed=failure>last&&!error.isEmpty();
        errorText.setVisibility(failed?View.VISIBLE:View.GONE);if(failed)label(errorText,"同步失败 · "+error);
        label(permissionText,SyncService.filesAllowed(this)?"文件访问已授权":"文件访问未授权");
    }
    // API 33+ explicitly excludes other apps; older versions use package-scoped broadcasts.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onResume(){super.onResume();if(Build.VERSION.SDK_INT>=33)registerReceiver(stateReceiver,new IntentFilter(SyncState.ACTION),Context.RECEIVER_NOT_EXPORTED);else registerReceiver(stateReceiver,new IntentFilter(SyncState.ACTION));
        if(Config.prefs(this).getBoolean("enabled",false)&&SyncService.filesAllowed(this)&&new Config(this).valid()&&!SyncState.running)startForegroundService(new Intent(this,SyncService.class));ui.post(refresh);
    }
    @Override protected void onPause(){ui.removeCallbacks(refresh);unregisterReceiver(stateReceiver);super.onPause();}
    @Override protected void onDestroy(){if(settingsDialog!=null)settingsDialog.dismiss();network.shutdown();super.onDestroy();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=101||result!=RESULT_OK||data==null)return;
        try(InputStream in=getContentResolver().openInputStream(data.getData())){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1){if(out.size()+n>65536)throw new Exception("配对文件过大");out.write(b,0,n);}Config.importJson(this,new JSONObject(out.toString("UTF-8")));fillConfig();toast("配对信息已导入");if(Config.prefs(this).getBoolean("enabled",false))enableMonitoring();else checkConnection();}
        catch(Exception e){new AlertDialog.Builder(this).setTitle("无法导入配对文件").setMessage(SyncService.friendly(e)).setPositiveButton("好",null).show();}
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}
