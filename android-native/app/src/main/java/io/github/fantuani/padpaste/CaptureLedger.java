package io.github.fantuani.padpaste;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** Metadata only: interrupted captures are cleaned up, never retransmitted. */
final class CaptureLedger extends SQLiteOpenHelper {
    static final class Entry { String name,path; }
    CaptureLedger(Context c){super(c,"captures.db",null,1);}
    @Override public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE active(name TEXT PRIMARY KEY,path TEXT NOT NULL,handled INTEGER NOT NULL DEFAULT 0)");
        d.execSQL("CREATE TABLE done(name TEXT PRIMARY KEY,at INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase d,int oldVersion,int newVersion){}
    boolean handled(String name){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM done WHERE name=? UNION ALL SELECT 1 FROM active WHERE name=? AND handled=1 LIMIT 1",new String[]{name,name})){return c.moveToFirst();}
    }
    void begin(String name,String path){
        ContentValues v=new ContentValues();v.put("name",name);v.put("path",path);
        getWritableDatabase().insertWithOnConflict("active",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    List<Entry> interrupted(){
        List<Entry> entries=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT name,path FROM active",null)){
            while(c.moveToNext()){Entry e=new Entry();e.name=c.getString(0);e.path=c.getString(1);entries.add(e);}
        }
        return entries;
    }
    void finish(String name,boolean cleaned){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{
            ContentValues v=new ContentValues();v.put("name",name);v.put("at",System.currentTimeMillis());
            d.insertWithOnConflict("done",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            if(cleaned)d.delete("active","name=?",new String[]{name});
            else {ContentValues flag=new ContentValues();flag.put("handled",1);d.update("active",flag,"name=?",new String[]{name});}
            d.execSQL("DELETE FROM done WHERE name NOT IN (SELECT name FROM done ORDER BY at DESC LIMIT 2048)");
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
    }
}
