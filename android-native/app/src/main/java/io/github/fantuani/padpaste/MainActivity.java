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
    private static final int ACCENT=0xFF5365E9,INK=0xFF192039,MUTED=0xFF7D8497,GREEN=0xFF22856B;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private TextView connectionTitle,connectionSubtitle,monitorTitle,lastTransfer,errorText,permissionText;
    private EditText server,user,password,directory;
    private Switch enabled;
    private boolean rendering;
    private volatile boolean checking;
    private long lastCheck;
    private LinearLayout content;
    private final Runnable refresh=new Runnable(){public void run(){render();if(System.currentTimeMillis()-lastCheck>20000)checkConnection();ui.postDelayed(this,1000);}};
    private final BroadcastReceiver stateReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){render();}};

    @Override public void onCreate(Bundle saved){super.onCreate(saved);buildUi();fillConfig();}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void gap(LinearLayout parent,int height){Space s=new Space(this);parent.addView(s,new LinearLayout.LayoutParams(1,dp(height)));}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(24),dp(22),dp(24),dp(22));c.setBackground(background(Color.WHITE,22));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(18);content.addView(c,p);return c;}
    private Button button(String label,boolean primary){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?Color.WHITE:ACCENT);b.setBackground(background(primary?ACCENT:0xFFEEF0FF,12));b.setMinHeight(dp(46));b.setPadding(dp(14),0,dp(14),0);return b;}
    private void action(LinearLayout parent,String label,Runnable action){Button b=button(label,false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(46));p.topMargin=dp(10);parent.addView(b,p);b.setOnClickListener(v->action.run());}
    private EditText field(LinearLayout parent,String label,String hint,boolean secret){
        TextView caption=text(label,12,MUTED,false);parent.addView(caption);EditText f=new EditText(this);f.setTextSize(15);f.setSingleLine(true);f.setTextColor(INK);f.setHint(hint);f.setPadding(dp(12),dp(10),dp(12),dp(10));f.setBackground(background(0xFFF5F6FA,10));
        f.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.topMargin=dp(6);p.bottomMargin=dp(14);parent.addView(f,p);return f;
    }
    private void buildUi(){
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(0xFFF5F6FA);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout shell=column();shell.setGravity(Gravity.CENTER_HORIZONTAL);scroll.addView(shell,new ScrollView.LayoutParams(-1,-2));
        content=column();content.setPadding(dp(24),dp(38),dp(24),dp(40));int available=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);shell.addView(content,new LinearLayout.LayoutParams(dp(Math.min(available,720)),-2));
        TextView brand=text("PadPaste",34,INK,true);content.addView(brand);gap(content,5);content.addView(text("截图即粘贴  ·  同一 Wi-Fi，原图直达 Mac",14,MUTED,false));gap(content,28);
        LinearLayout status=card();status.addView(text("MAC 连接",11,MUTED,true));gap(status,10);connectionTitle=text("尚未检测",24,INK,true);status.addView(connectionTitle);gap(status,6);connectionSubtitle=text("导入配对信息后检测连接",13,MUTED,false);status.addView(connectionSubtitle);
        action(status,"检测连接",this::checkConnection);
        LinearLayout monitoring=card();LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);LinearLayout labels=column();monitorTitle=text("截图监控已暂停",18,INK,true);labels.addView(monitorTitle);gap(labels,5);labels.addView(text("开启后，新截图会自动进入 Mac 剪贴板",12,MUTED,false));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));enabled=new Switch(this);row.addView(enabled);monitoring.addView(row);gap(monitoring,18);lastTransfer=text("还没有同步记录",14,MUTED,false);monitoring.addView(lastTransfer);gap(monitoring,7);errorText=text("",12,0xFFBA5E49,false);monitoring.addView(errorText);
        enabled.setOnCheckedChangeListener((b,on)->{if(rendering)return;if(on)enableMonitoring();else{Config.prefs(this).edit().putBoolean("enabled",false).apply();if(SyncState.running)startService(new Intent(this,SyncService.class).setAction(SyncService.STOP));render();}});
        LinearLayout settings=card();settings.addView(text("配对设置",18,INK,true));gap(settings,16);server=field(settings,"MAC 地址","http://your-mac.local:5034",false);user=field(settings,"用户名","padpaste",false);password=field(settings,"配对密码","导入配对文件自动填写",true);directory=field(settings,"截图目录","/storage/emulated/0/DCIM/Screenshots",false);
        Button save=button("保存设置",true);settings.addView(save,new LinearLayout.LayoutParams(-1,dp(48)));save.setOnClickListener(v->{saveConfig();if(!new Config(this).valid()){toast("请检查 Mac 地址、用户名和密码");return;}toast("已保存");if(Config.prefs(this).getBoolean("enabled",false))startForegroundService(new Intent(this,SyncService.class).setAction(SyncService.RECONFIGURE));checkConnection();});
        action(settings,"导入配对文件",()->{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,101);});
        LinearLayout permissions=card();permissions.addView(text("后台运行",18,INK,true));gap(permissions,10);permissionText=text("",13,MUTED,false);permissions.addView(permissionText);action(permissions,"授权文件访问",this::requestFiles);action(permissions,"后台运行设置",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        gap(content,2);content.addView(text("原生文件监听 · 不读取普通剪贴板或通知内容",12,MUTED,false));setContentView(frame);
    }
    private void fillConfig(){Config c=new Config(this);server.setText(c.server);user.setText(c.username);password.setText(c.password);directory.setText(c.directory);}
    private void saveConfig(){Config.save(this,server.getText().toString(),user.getText().toString(),password.getText().toString(),directory.getText().toString());}
    private void enableMonitoring(){
        saveConfig();Config c=new Config(this);
        if(!c.valid()){toast("请先导入配对信息或填写 Mac 地址和密码");render();return;}
        Config.prefs(this).edit().putBoolean("enabled",true).apply();
        if(!SyncService.filesAllowed(this)){requestFiles();render();return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},102);
        startForegroundService(new Intent(this,SyncService.class));render();
    }
    private void requestFiles(){
        if(Build.VERSION.SDK_INT>=30)startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())));
        else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},103);
    }
    private void checkConnection(){
        if(checking)return;Config c=new Config(this);if(!c.valid()){connectionTitle.setText("等待配对");connectionSubtitle.setText("导入 Mac 生成的配对文件");return;}
        checking=true;lastCheck=System.currentTimeMillis();connectionTitle.setText("正在检测…");
        network.execute(()->{try{MacClient.check(c);SyncState.connection(this,true,"");}catch(Exception e){SyncState.connection(this,false,SyncService.friendly(e));}finally{checking=false;runOnUiThread(this::render);}});
    }
    private String clock(long ms){return new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(ms));}
    private void render(){
        android.content.SharedPreferences p=Config.prefs(this);long confirmed=p.getLong("last_confirmed",0);boolean connected=p.getBoolean("connected",false)&&System.currentTimeMillis()-confirmed<90000;
        if(!checking){connectionTitle.setText(connected?"● 已连接到 Mac":new Config(this).valid()?"○ 等待 Mac 连接":"等待配对");connectionTitle.setTextColor(connected?GREEN:INK);connectionSubtitle.setText(connected?new Config(this).server+"\n最近确认 "+clock(confirmed):p.getString("connection_error","点击检测连接"));}
        monitorTitle.setText(SyncState.running?SyncState.phase+(SyncState.pending>0?" · 排队 "+SyncState.pending:""):p.getBoolean("enabled",false)?"等待恢复监控":"截图监控已暂停");
        rendering=true;enabled.setChecked(p.getBoolean("enabled",false));rendering=false;
        long last=p.getLong("last_upload",0);lastTransfer.setText(last>0?"最近同步  "+clock(last)+"  ·  上传与接收 "+p.getLong("last_duration",0)+" ms":"还没有同步记录");
        errorText.setText(p.getString("last_error",""));
        permissionText.setText((SyncService.filesAllowed(this)?"✓ 文件访问已授权":"需要文件访问权限以读取新截图")+"\n请开启后台自启动，并将省电策略设为无限制。");
    }
    @Override protected void onResume(){super.onResume();if(Build.VERSION.SDK_INT>=33)registerReceiver(stateReceiver,new IntentFilter(SyncState.ACTION),Context.RECEIVER_NOT_EXPORTED);else registerReceiver(stateReceiver,new IntentFilter(SyncState.ACTION));
        if(Config.prefs(this).getBoolean("enabled",false)&&SyncService.filesAllowed(this)&&new Config(this).valid()&&!SyncState.running)startForegroundService(new Intent(this,SyncService.class));ui.post(refresh);
    }
    @Override protected void onPause(){ui.removeCallbacks(refresh);unregisterReceiver(stateReceiver);super.onPause();}
    @Override protected void onDestroy(){network.shutdown();super.onDestroy();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=101||result!=RESULT_OK||data==null)return;
        try(InputStream in=getContentResolver().openInputStream(data.getData())){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1){if(out.size()+n>65536)throw new Exception("配对文件过大");out.write(b,0,n);}Config.importJson(this,new JSONObject(out.toString("UTF-8")));fillConfig();toast("配对信息已导入");if(Config.prefs(this).getBoolean("enabled",false))enableMonitoring();else checkConnection();}
        catch(Exception e){new AlertDialog.Builder(this).setTitle("无法导入配对文件").setMessage(SyncService.friendly(e)).setPositiveButton("好",null).show();}
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}
