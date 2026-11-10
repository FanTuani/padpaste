// PadPaste — AutoJs6 client. MIT licensed; see THIRD_PARTY_NOTICES.md.
// Install this folder under /sdcard/Scripts/PadPaste/.
const CONFIG_PATH = '/sdcard/PadPaste/config.json';
const DATA_DIR = '/sdcard/PadPaste';
if (!files.exists(CONFIG_PATH)) throw new Error('Copy android-config.json to ' + CONFIG_PATH);
const config = JSON.parse(files.read(CONFIG_PATH));
if (!config.server || !config.username || !config.password) throw new Error('Configure server, username and password');
const BASE = String(config.server).replace(/\/+$/, '');
const AUTH = 'Basic ' + $base64.encode(config.username + ':' + config.password);
const DIR = config.screenshotDirectory || '/sdcard/DCIM/Screenshots';
const STATUS = DATA_DIR + '/status.json';
const formats = require('./formats.js');
const canonical = formats.canonicalFilename;
const completeImage = formats.completeImage;
files.ensureDir(DATA_DIR + '/');
function status(state, detail) {
    files.write(STATUS, JSON.stringify({ state:state, time:new Date().toISOString(), detail:detail || '' }));
}
// Keep one copy running when the script is restarted.
const me = engines.myEngine();
engines.all().forEach(function(engine) {
    if (Number(engine.id) < Number(me.id) && String(engine.getSource()) === String(me.getSource())) engine.forceStop();
});
try {
    androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean('key_$_foreground_service', true).apply();
    const intent = new android.content.Intent();
    intent.setClassName(context.getPackageName(), 'org.autojs.autojs.external.foreground.AppForegroundService');
    context.startForegroundService(intent);
} catch (error) { console.warn('Foreground service: ' + error); }
if (config.startOnBoot !== false) {
    try {
        const ownPath = String(me.getSource());
        const existing = $timers.queryIntentTasks({path:ownPath, action:'android.intent.action.BOOT_COMPLETED'});
        if (!existing.length) $timers.addIntentTask({path:ownPath, action:'android.intent.action.BOOT_COMPLETED'});
    } catch (error) { console.warn('Boot task: ' + error); }
}

const uploaded = {};
const waiting = {};
const eventQueue = new java.util.concurrent.ConcurrentLinkedQueue();
const traces = [];
const watchers = [];
let lastScan = Date.now();
let lastHeartbeat = 0;
let lastError = 0;
let observerWorking = false;
const pumpScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
function requestPump(delay) {
    if (pumpScheduled.compareAndSet(false,true)) {
        try { setTimeout(function(){pumpScheduled.set(false);pump();},Math.max(0,delay)); }
        catch(e){pumpScheduled.set(false);throw e;}
    } else if (delay===0) {
        setTimeout(pump,0);
    }
}
function list() {
    const arr = new java.io.File(DIR).listFiles();
    const out = [];
    if (arr) for (let i=0;i<arr.length;i++) {
        if (arr[i].isFile() && canonical(arr[i].getName())) out.push(arr[i]);
    }
    return out;
}
function signature(f) { return String(f.length()) + ':' + String(f.lastModified()); }
function put(path, bytes, type, base) {
    const conn = new java.net.URL((base||BASE)+path).openConnection();
    conn.setConnectTimeout(3000); conn.setReadTimeout(10000);
    conn.setRequestMethod('PUT'); conn.setDoOutput(true);
    conn.setRequestProperty('Authorization',AUTH);
    conn.setRequestProperty('Content-Type',type);
    conn.setFixedLengthStreamingMode(bytes.length);
    let ok=false;
    try {
        const stream=conn.getOutputStream();stream.write(bytes);stream.close();
        const code=conn.getResponseCode();
        if(code<200||code>=300)throw new Error('HTTP '+code);
        const response=conn.getInputStream();
        try { const buffer=java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE,512); while(response.read(buffer)!==-1){} } finally {response.close();}
        ok=true;
    } finally {
        // Fully consumed successful responses can reuse the same LAN connection.
        if(!ok)conn.disconnect();
    }
}
function notice(source, at, event) {
    source=String(source);
    const name=canonical(source);
    if(!name || uploaded[name])return;
    const path=DIR+'/'+source;
    const f=new java.io.File(path);
    if(!f.exists() || !f.isFile())return;
    let item=waiting[name];
    if(!item)item=waiting[name]={name:name,firstEventMs:at,retryAfter:0};
    const sig=signature(f);
    if(item.path!==path || item.sig!==sig) {
        item.path=path;item.source=source;item.sig=sig;item.readyAt=Date.now()+40;
    }
    // CLOSE_WRITE or MOVED_TO indicates the producer has finished writing.
    if((event&8)!==0 || (event&128)!==0)item.readyAt=Date.now();
}
list().forEach(function(f){uploaded[canonical(f.getName())]=true;});
try {
    const mask=256|8|128|2;
    const observer=new JavaAdapter(android.os.FileObserver, {
        onEvent:function(event,path) {
            if(path!=null) {
                eventQueue.offer(new java.lang.String(String(path)+'\t'+String(event)+'\t'+String(java.lang.System.currentTimeMillis())));
                try{requestPump(0);}catch(e){console.warn('Event scheduling failed: '+e);}
            }
        }
    },DIR,mask);
    observer.startWatching();
    watchers.push(observer);
    observerWorking=true;
} catch(e) { console.warn('FileObserver unavailable; using fallback scans: '+e); }
events.on('exit',function(){watchers.forEach(function(w){try{w.stopWatching();}catch(e){}});});
status('watching',{directory:DIR,mode:observerWorking?'file-events':'fallback'});

console.log('Fast screenshot sync started; event watcher='+observerWorking);
function pump() {
    try {
        let raw;
        while((raw=eventQueue.poll())!=null) {
            const parts=String(raw).split('\t');
            notice(parts[0],Number(parts[2]),Number(parts[1]));
        }
        const now=Date.now();
        if(now-lastScan>=(observerWorking?5000:150)) {
            lastScan=now;
            list().forEach(function(f){notice(f.getName(),now,0);});
        }
        if(now-lastHeartbeat>=20000) {
            lastHeartbeat=now;
            files.write(DATA_DIR+'/heartbeat.json',JSON.stringify({time:now,mode:observerWorking?'file-events':'fallback',pending:Object.keys(waiting).length}));
        }
        const items=Object.keys(waiting).map(function(k){return waiting[k];}).sort(function(a,b){return a.firstEventMs-b.firstEventMs;});
        for(let i=0;i<items.length;i++) {
            const item=items[i];
            if(Date.now()<item.readyAt || Date.now()<item.retryAfter)continue;
            const f=new java.io.File(item.path);
            if(!f.exists()){if(Date.now()-item.firstEventMs>120000)delete waiting[item.name];else item.retryAfter=Date.now()+500;continue;} // Wait for final rename event.
            const sig=signature(f);
            if(sig!==item.sig){item.sig=sig;item.readyAt=Date.now()+40;continue;}
            const readStart=Date.now();
            const bytes=files.readBytes(item.path);
            if(signature(f)!==sig || bytes.length!==Number(f.length()) || !completeImage(bytes)) {
                item.readyAt=Date.now()+40;continue;
            }
            const readDone=Date.now();

            const hashDone=Date.now();
            try {
                const channel='native';
                put('/screenshot/'+encodeURIComponent(item.name),bytes,'application/octet-stream',BASE);
                const imagePutDone=Date.now();
                const done=Date.now();
                uploaded[item.name]=true;delete waiting[item.name];
                const trace={name:item.name,source:item.source,channel:channel,bytes:bytes.length,firstEventMs:item.firstEventMs,readStartMs:readStart,readDoneMs:readDone,hashDoneMs:hashDone,imagePutDoneMs:imagePutDone,profileDoneMs:done,detectionAndReadyMs:readStart-item.firstEventMs,readMs:readDone-readStart,hashMs:hashDone-readDone,imagePutMs:imagePutDone-hashDone,profilePutMs:done-imagePutDone,totalToServerMs:done-item.firstEventMs};
                traces.push(trace);if(traces.length>30)traces.shift();
                if(config.diagnostics)files.write(DATA_DIR+'/latency-trace.json',JSON.stringify(traces));
                status('uploaded',trace);
                console.log('Screenshot uploaded in '+trace.totalToServerMs+' ms: '+item.name);
            } catch(e) {
                item.retryAfter=Date.now()+1000;
                throw e;
            }
        }
    } catch(e) {
        status('retrying',String(e));
        if(Date.now()-lastError>10000){console.warn(String(e));lastError=Date.now();}
    } finally {
        let next=Infinity;
        const now=Date.now();
        Object.keys(waiting).forEach(function(k){const item=waiting[k];next=Math.min(next,Math.max(item.readyAt,item.retryAfter)-now);});
        if(next!==Infinity)requestPump(Math.max(10,Math.min(next,5000)));
    }
}
setInterval(function(){requestPump(0);},observerWorking?5000:150);
