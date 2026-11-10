package io.github.fantuani.padpaste;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import java.io.File;
import java.nio.file.Files;

public final class SyncService extends Service {
    static final String STOP="io.github.fantuani.padpaste.STOP";
    static final String RECONFIGURE="io.github.fantuani.padpaste.RECONFIGURE";
    static final int NOTIFICATION=21;
    private HandlerThread thread;
    private Handler worker;
    private FileObserver observer;
    private TransferQueue queue;
    private Config config;
    private boolean initialized;
    private long networkRetryAt;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Runnable scanLoop=new Runnable(){public void run(){if(!SyncState.running)return;scan();worker.postDelayed(this,5000);}};
    private final Runnable processLoop=this::process;

    static boolean filesAllowed(Context c){
        return Build.VERSION.SDK_INT>=30 ? Environment.isExternalStorageManager() : c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
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
            @Override public void onAvailable(Network n){worker.post(()->{networkRetryAt=0;checkConnection();schedule(0);});}
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
        config=new Config(this);if(queue==null)queue=new TransferQueue(this);
        if(Config.prefs(this).getLong("baseline",0)==0)Config.prefs(this).edit().putLong("baseline",System.currentTimeMillis()).commit();
        File directory=new File(config.directory);
        if(!directory.isDirectory()||!directory.canRead()){
            SyncState.running=false;SyncState.phase="截图目录不可读";Config.prefs(this).edit().putString("connection_error","请检查截图目录和文件访问权限").apply();SyncState.publish(this);updateNotification("截图目录不可读，点此检查");return;
        }
        SyncState.running=true;SyncState.phase="正在监控";initialized=true;
        observer=new FileObserver(config.directory,FileObserver.CREATE|FileObserver.MODIFY|FileObserver.CLOSE_WRITE|FileObserver.MOVED_TO){
            @Override public void onEvent(int event,String path){if(path!=null&&SyncState.running)worker.post(()->notice(path,(event&(FileObserver.CLOSE_WRITE|FileObserver.MOVED_TO))!=0));}
        };
        observer.startWatching();scan();worker.postDelayed(scanLoop,5000);checkConnection();updateNotification("截图监控中 · 同一 Wi-Fi 自动同步");SyncState.publish(this);
    }
    private void notice(String source,boolean ready){
        if(config==null||queue==null)return;String name=ImageFormats.canonical(source);if(name==null)return;
        File f=new File(config.directory,source);
        if(!f.isFile()||f.lastModified()<Config.prefs(this).getLong("baseline",0))return;
        queue.notice(name,f.getAbsolutePath(),ready);SyncState.pending=queue.count();schedule(ready?0:40);
    }
    private void scan(){
        if(config==null)return;File[] files=new File(config.directory).listFiles();if(files==null)return;
        for(File f:files)if(f.isFile())notice(f.getName(),false);
        schedule(0);
    }
    private void schedule(long delay){if(worker==null||!SyncState.running)return;worker.removeCallbacks(processLoop);worker.postDelayed(processLoop,Math.max(0,delay));}
    private void process(){
        if(!SyncState.running||queue==null)return;
        TransferQueue.Job job=queue.next();if(job==null){SyncState.pending=0;return;}
        long now=System.currentTimeMillis();long next=Math.max(job.next,networkRetryAt);
        if(next>now){schedule(next-now);return;}
        File file=new File(job.path);
        if(!file.exists()){
            File renamed=new File(config.directory,job.name);
            if(renamed.isFile()){queue.notice(job.name,renamed.getAbsolutePath(),true);schedule(0);return;}
            if(now-job.firstSeen>120000)queue.forget(job);else queue.retry(job,500,false);schedule(500);return;
        }
        try{
            long size=file.length(),modified=file.lastModified();
            if(size<=0){queue.retry(job,60,false);schedule(60);return;}
            if(size>50L*1024*1024){queue.forget(job);SyncState.phase="图片超过 50 MB";SyncState.publish(this);schedule(0);return;}
            byte[] data=Files.readAllBytes(file.toPath());
            if(file.length()!=size||file.lastModified()!=modified||!ImageFormats.complete(data)){queue.retry(job,60,false);schedule(60);return;}
            SyncState.phase="正在上传";SyncState.publish(this);
            long started=SystemClock.elapsedRealtime();MacClient.upload(config,job.name,data);long duration=SystemClock.elapsedRealtime()-started;
            queue.success(job);networkRetryAt=0;SyncState.pending=queue.count();SyncState.phase="正在监控";
            Config.prefs(this).edit().putLong("last_upload",System.currentTimeMillis()).putLong("last_duration",duration).putString("last_filename",job.name).putString("last_error","").apply();
            SyncState.connection(this,true,"");updateNotification("截图已同步 · "+duration+" ms");schedule(0);
        }catch(Exception e){
            long delay=Math.min(30000,1000L<<(Math.min(job.tries,5)));queue.retry(job,delay,true);networkRetryAt=System.currentTimeMillis()+delay;
            SyncState.phase="等待重试";Config.prefs(this).edit().putString("last_error",friendly(e)).apply();SyncState.connection(this,false,friendly(e));updateNotification("等待 Mac 连接 · 图片已排队");schedule(delay);
        }
    }
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
        if(worker!=null){worker.removeCallbacksAndMessages(null);worker.post(()->{if(queue!=null)queue.close();});thread.quitSafely();}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
