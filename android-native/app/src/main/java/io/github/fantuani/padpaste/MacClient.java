package io.github.fantuani.padpaste;

import android.util.Base64;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.IOException;

final class MacClient {
    static final class Result { final int code; final String body; Result(int c,String b){code=c;body=b;} }
    static Result request(Config cfg,String method,String path,byte[] bytes) throws IOException {
        HttpURLConnection c=(HttpURLConnection)new URL(cfg.endpoint()+path).openConnection();
        c.setConnectTimeout(2500);c.setReadTimeout(8000);c.setRequestMethod(method);
        String auth=Base64.encodeToString((cfg.username+":"+cfg.password).getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
        c.setRequestProperty("Authorization","Basic "+auth);
        try {
            if(bytes!=null){c.setDoOutput(true);c.setFixedLengthStreamingMode(bytes.length);c.setRequestProperty("Content-Type","application/octet-stream");try(java.io.OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int code=c.getResponseCode();InputStream stream=code>=400?c.getErrorStream():c.getInputStream();
            String body="";if(stream!=null){try(InputStream in=stream){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[512];int n;while((n=in.read(b))!=-1){if(out.size()+n>4096)throw new IOException("服务端响应过长");out.write(b,0,n);}body=out.toString("UTF-8");}}
            return new Result(code,body);
        } finally {c.disconnect();}
    }
    static void locate(Config c) throws IOException {
        try {c.resolved(MacDiscovery.discover(c));}
        catch(Exception e){throw new IOException("未找到已配对 Mac，请确认同一 Wi-Fi 与 Mac 已唤醒",e);}
    }
    static void upload(Config c,String name,byte[] data) throws IOException {
        // Resolve the address before sending image bytes. Never retry an image PUT.
        check(c);
        Result r=request(c,"PUT","/screenshot/"+URLEncoder.encode(name,"UTF-8").replace("+","%20"),data);
        if(r.code==401)throw new IOException("配对信息不匹配，请重新导入");
        if(r.code!=200 || !(r.body.equals("clipboard ready")||r.body.equals("duplicate")))throw new IOException("接收失败（HTTP "+r.code+"）");
    }
    static void check(Config c) throws IOException {
        try {checkAtAddress(c);}
        catch(IOException first){locate(c);checkAtAddress(c);}
    }
    static void checkAtAddress(Config c) throws IOException {
        if(!c.valid())throw new IOException("请先配置 Mac 地址和配对信息");
        Result r=request(c,"GET","/health",null);
        // Older PadPaste receivers have no health route. A rejected, deliberately
        // incomplete image verifies credentials without changing the clipboard.
        if(r.code==405)r=request(c,"PUT","/screenshot/Screenshot_ConnectionCheck.png",new byte[]{0});
        if(r.code==200 && r.body.equals("PadPaste ready") || r.code==422)return;
        if(r.code==401)throw new IOException("配对信息不匹配，请重新导入");
        throw new IOException("Mac 未就绪（HTTP "+r.code+"）");
    }
}
