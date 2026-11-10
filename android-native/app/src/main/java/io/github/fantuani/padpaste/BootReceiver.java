package io.github.fantuani.padpaste;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){
        if(Config.prefs(c).getBoolean("enabled",false)){
            try{c.startForegroundService(new Intent(c,SyncService.class));}
            catch(RuntimeException e){Config.prefs(c).edit().putString("connection_error","后台启动受限，请打开 PadPaste 恢复").apply();}
        }
    }
}
