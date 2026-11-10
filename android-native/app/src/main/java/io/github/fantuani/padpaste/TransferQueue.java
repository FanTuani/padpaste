package io.github.fantuani.padpaste;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

final class TransferQueue extends SQLiteOpenHelper {
    static final class Job { String name, path; long firstSeen, next; int tries; }
    TransferQueue(Context c) { super(c, "transfer.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE pending(name TEXT PRIMARY KEY,path TEXT NOT NULL,first_seen INTEGER NOT NULL,next_attempt INTEGER NOT NULL,tries INTEGER NOT NULL DEFAULT 0)");
        d.execSQL("CREATE TABLE sent(name TEXT PRIMARY KEY,done INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase d, int oldV, int newV) { }
    boolean sent(String name) {
        try(Cursor q=getReadableDatabase().rawQuery("SELECT 1 FROM sent WHERE name=?",new String[]{name})){return q.moveToFirst();}
    }
    void notice(String name, String path, boolean ready) {
        if (sent(name)) return;
        long now=System.currentTimeMillis(); SQLiteDatabase d=getWritableDatabase();
        ContentValues v=new ContentValues();v.put("name",name);v.put("path",path);v.put("first_seen",now);v.put("next_attempt",now+(ready?0:40));v.put("tries",0);
        long inserted=d.insertWithOnConflict("pending",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        if(inserted<0){ContentValues update=new ContentValues();update.put("path",path);if(ready)update.put("next_attempt",now);d.update("pending",update,"name=?",new String[]{name});}
    }
    Job next() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT name,path,first_seen,next_attempt,tries FROM pending ORDER BY next_attempt,first_seen LIMIT 1",null)){
            if(!c.moveToFirst())return null;Job j=new Job();j.name=c.getString(0);j.path=c.getString(1);j.firstSeen=c.getLong(2);j.next=c.getLong(3);j.tries=c.getInt(4);return j;
        }
    }
    void retry(Job j,long delay,boolean failure) {
        ContentValues v=new ContentValues();v.put("next_attempt",System.currentTimeMillis()+delay);v.put("tries",j.tries+(failure?1:0));getWritableDatabase().update("pending",v,"name=?",new String[]{j.name});
    }
    void success(Job j){SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{ContentValues v=new ContentValues();v.put("name",j.name);v.put("done",System.currentTimeMillis());d.insertWithOnConflict("sent",null,v,SQLiteDatabase.CONFLICT_REPLACE);d.delete("pending","name=?",new String[]{j.name});d.setTransactionSuccessful();}finally{d.endTransaction();}}
    void forget(Job j){getWritableDatabase().delete("pending","name=?",new String[]{j.name});}
    int count(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM pending",null)){c.moveToFirst();return c.getInt(0);}}
}
