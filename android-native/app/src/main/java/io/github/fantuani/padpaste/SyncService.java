package io.github.fantuani.padpaste;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.net.*;
import android.os.*;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public final class SyncService extends Service {
    static final String STOP="io.github.fantuani.padpaste.STOP";
    static final String RECONFIGURE="io.github.fantuani.padpaste.RECONFIGURE";
    static final int NOTIFICATION=21;
    private static final class Capture {
        String name,path; long noticed,next;
        Capture(String n,String p){name=n;path=p;noticed=SystemClock.elapsedRealtime();}
    }
    private HandlerThread thread;
    private Handler worker;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private FileObserver observer;
    private CaptureLedger ledger;
    // Only a short, in-memory wait for the system to finish writing each image.
    private final Map<String,Capture> writing=new LinkedHashMap<>();
    private Config config;
    private boolean initialized;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Runnable scanLoop=new Runnable(){public void run(){if(!SyncState.running)return;scan();worker.postDelayed(this,5000);}};
    private final Runnable processLoop=this::process;

    static boolean filesAllowed(Context c){
        return Build.VERSION.SDK_INT>=30 ? Environment.isExternalStorageManager() : c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
    }
    @Override public void onCreate(){
        super.onCreate();
        NotificationChannel channel=new NotificationChannel("sync","截图同步",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示截图监控是否正在运行");getSystemService(NotificationManager.class).createNotificationChannel(channel);
        Notification n=notification("正在恢复截图监控…");
        if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(NOTIFICATION,n);
        thread=new HandlerThread("PadPasteTransfer");thread.start();worker=new Handler(thread.getLooper());
        connectivity=getSystemService(ConnectivityManager.class);
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network n){worker.post(()->checkConnection());}
            @Override public void onLost(Network n){SyncState.connection(SyncService.this,false,"网络已断开");}
        };
        connectivity.registerDefaultNetworkCallback(networkCallback);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){Config.prefs(this).edit().putBoolean("enabled",false).apply();stopSelf();return START_NOT_STICKY;}
        if(!Config.prefs(this).getBoolean("enabled",false)){stopSelf();return START_NOT_STICKY;}
        if(!filesAllowed(this)||!new Config(this).valid()){
            SyncState.phase=!filesAllowed(this)?"需要文件访问权限":"需要配置配对信息";SyncState.publish(this);stopSelf();return START_NOT_STICKY;
        }
        boolean reload=intent!=null&&RECONFIGURE.equals(intent.getAction());
        worker.post(()->{if(!initialized||reload)initialize();});
        return START_STICKY;
    }
    private void initialize(){
        if(observer!=null)observer.stopWatching();worker.removeCallbacks(scanLoop);worker.removeCallbacks(processLoop);
        config=new Config(this);if(ledger==null)ledger=new CaptureLedger(this);
        if(!Config.prefs(this).getBoolean("clipboard_only_v1",false)){
            // Changeover applies to new captures, without deleting older personal photos.
            Config.prefs(this).edit().putBoolean("clipboard_only_v1",true).putLong("baseline",System.currentTimeMillis()).commit();
            deleteDatabase("transfer.db");
        }
        for(CaptureLedger.Entry entry:ledger.interrupted()){
            boolean cleaned=cleanCapture(entry.name,entry.path);
            ledger.finish(entry.name,cleaned);
            reportFailure("上次截图同步中断",cleaned);
        }
        writing.clear();
        File directory=new File(config.directory);
        if(!directory.isDirectory()||!directory.canRead()){
            SyncState.running=false;SyncState.phase="截图目录不可读";SyncState.publish(this);updateNotification("截图目录不可读，点此检查");return;
        }
        SyncState.running=true;SyncState.phase="正在监控";initialized=true;
        observer=new FileObserver(config.directory,FileObserver.CREATE|FileObserver.MODIFY|FileObserver.CLOSE_WRITE|FileObserver.MOVED_TO){
            @Override public void onEvent(int event,String path){if(path!=null&&SyncState.running)worker.post(()->notice(path,(event&(FileObserver.CLOSE_WRITE|FileObserver.MOVED_TO))!=0));}
        };
        observer.startWatching();scan();worker.postDelayed(scanLoop,5000);checkConnection();
        updateNotification("仅剪贴板 · 传输后清理平板截图");SyncState.publish(this);
    }
    private void notice(String source,boolean ready){
        if(!SyncState.running||config==null)return;String name=ImageFormats.canonical(source);if(name==null)return;
        File file=new File(config.directory,source);
        if(!file.isFile()||file.lastModified()<Config.prefs(this).getLong("baseline",0))return;
        if(ledger.handled(name)){ledger.finish(name,cleanCapture(name,file.getAbsolutePath()));return;}
        Capture capture=writing.get(name);
        if(capture==null){capture=new Capture(name,file.getAbsolutePath());ledger.begin(name,capture.path);writing.put(name,capture);}
        capture.path=file.getAbsolutePath();capture.next=SystemClock.elapsedRealtime()+(ready?0:40);schedule(ready?0:40);
    }
    private void scan(){
        File[] files=new File(config.directory).listFiles();if(files==null)return;
        for(File file:files)if(file.isFile())notice(file.getName(),false);
        schedule(0);
    }
    private void schedule(long delay){if(worker==null||!SyncState.running)return;worker.removeCallbacks(processLoop);worker.postDelayed(processLoop,Math.max(0,delay));}
    private void process(){
        if(!SyncState.running||writing.isEmpty())return;
        Capture capture=Collections.min(writing.values(),Comparator.comparingLong(c->c.next));
        long now=SystemClock.elapsedRealtime();if(capture.next>now){schedule(capture.next-now);return;}
        File file=new File(capture.path);if(!file.isFile())file=new File(config.directory,capture.name);
        boolean uploaded=false,attempted=false;long duration=0;String error="";
        try{
            long size=file.length(),modified=file.lastModified();
            if(size>50L*1024*1024)throw new IOException("图片超过 50 MB");
            byte[] data=size>0?Files.readAllBytes(file.toPath()):new byte[0];
            boolean complete=size>0&&file.length()==size&&file.lastModified()==modified&&ImageFormats.complete(data);
            if(!complete){
                if(now-capture.noticed<2000){capture.next=now+60;schedule(60);return;}
                throw new IOException("系统未完成截图写入");
            }
            SyncState.phase="正在上传";SyncState.publish(this);attempted=true;
            long started=SystemClock.elapsedRealtime();MacClient.upload(config,capture.name,data);duration=SystemClock.elapsedRealtime()-started;uploaded=true;
        }catch(Exception e){error=friendly(e);}
        // Every capture gets one network attempt. Success or failure both end in cleanup.
        boolean cleaned=cleanCapture(capture.name,capture.path);
        ledger.finish(capture.name,cleaned);writing.remove(capture.name);SyncState.phase="正在监控";
        if(uploaded){
            Config.prefs(this).edit().putLong("last_upload",System.currentTimeMillis()).putLong("last_duration",duration).putString("last_error",cleaned?"":"平板截图清理失败").apply();
            SyncState.connection(this,true,"");updateNotification("已送达剪贴板 · "+duration+" ms");
            if(!cleaned)popup("截图已同步，但平板截图清理失败");
        }else{
            if(attempted)SyncState.connection(this,false,error);
            reportFailure(error,cleaned);updateNotification("上次同步失败 · 未保留待传图片");
        }
        SyncState.publish(this);schedule(0);
    }
    private boolean cleanCapture(String name,String originalPath){
        File folder=new File(originalPath).getAbsoluteFile().getParentFile();
        Set<String> paths=new LinkedHashSet<>();paths.add(originalPath);paths.add(new File(folder,name).getAbsolutePath());
        File[] files=folder.listFiles();
        if(files!=null)for(File file:files)if(name.equals(ImageFormats.canonical(file.getName())))paths.add(file.getAbsolutePath());
        boolean cleaned=true;
        for(String path:paths){
            File file=new File(path);
            try{
                // A remembered capture may never delete outside its configured screenshot folder.
                if(!file.getCanonicalFile().getParentFile().equals(folder.getCanonicalFile())){cleaned=false;continue;}
                Files.deleteIfExists(file.toPath());
            }catch(IOException|SecurityException e){cleaned=false;}
        }
        MediaScannerConnection.scanFile(this,paths.toArray(new String[0]),null,null);
        return cleaned;
    }
    private void reportFailure(String error,boolean cleaned){
        Config.prefs(this).edit().putLong("last_failure",System.currentTimeMillis()).putBoolean("last_failure_cleaned",cleaned).putString("last_error",error+(cleaned?"":"；平板截图清理失败")).apply();
        popup(cleaned?"截图同步失败，临时截图已清理":"截图同步失败，平板截图清理失败");
    }
    private void popup(String message){ui.post(()->Toast.makeText(this,message,Toast.LENGTH_LONG).show());}
    private void checkConnection(){
        if(config==null)return;
        try{MacClient.check(config);SyncState.connection(this,true,"");}
        catch(Exception e){SyncState.connection(this,false,friendly(e));}
    }
    static String friendly(Exception e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():s;}
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,SyncService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"sync").setSmallIcon(io.github.fantuani.padpaste.R.drawable.ic_padpaste).setContentTitle("PadPaste").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"暂停",stop).build()).build();
    }
    private void updateNotification(String text){getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(text));}
    @Override public void onDestroy(){
        SyncState.running=false;SyncState.phase="已暂停";SyncState.publish(this);
        if(observer!=null)observer.stopWatching();
        if(connectivity!=null&&networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}
        if(worker!=null){worker.removeCallbacksAndMessages(null);worker.post(()->{if(ledger!=null){for(Capture capture:writing.values()){boolean cleaned=cleanCapture(capture.name,capture.path);ledger.finish(capture.name,cleaned);reportFailure("截图监控已暂停",cleaned);}writing.clear();ledger.close();}});thread.quitSafely();}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
