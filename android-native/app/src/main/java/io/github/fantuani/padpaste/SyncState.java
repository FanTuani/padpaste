package io.github.fantuani.padpaste;

import android.content.Context;
import android.content.Intent;

final class SyncState {
    static final String ACTION="io.github.fantuani.padpaste.STATE";
    static volatile boolean running=false;
    static volatile String phase="尚未启动";
    static volatile int pending=0;
    static void publish(Context c){c.sendBroadcast(new Intent(ACTION).setPackage(c.getPackageName()));}
    static void connection(Context c,boolean ready,String error){
        android.content.SharedPreferences.Editor e=Config.prefs(c).edit().putBoolean("connected",ready).putString("connection_error",error==null?"":error);
        if(ready)e.putLong("last_confirmed",System.currentTimeMillis());e.apply();publish(c);
    }
}
