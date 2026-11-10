package io.github.fantuani.padpaste;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.net.URI;

final class Config {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("padpaste", Context.MODE_PRIVATE); }
    final String server, username, password, directory;
    Config(Context c) {
        SharedPreferences p = prefs(c);
        server = p.getString("server", ""); username = p.getString("username", "padpaste");
        password = p.getString("password", ""); directory = p.getString("directory", "/storage/emulated/0/DCIM/Screenshots");
    }
    boolean valid() {
        try { URI u = URI.create(server); return ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getHost() != null && u.getUserInfo() == null && !username.isEmpty() && !username.contains(":") && !password.isEmpty(); }
        catch (RuntimeException e) { return false; }
    }
    static void save(Context c, String server, String user, String pass, String dir) {
        prefs(c).edit().putString("server", server.trim().replaceAll("/+$", "")).putString("username", user.trim()).putString("password", pass)
            .putString("directory", dir.trim().isEmpty() ? "/storage/emulated/0/DCIM/Screenshots" : dir.trim()).apply();
    }
    static void importJson(Context c, JSONObject j) throws Exception {
        String server = j.getString("server"), user = j.getString("username"), pass = j.getString("password");
        URI u = URI.create(server);
        if (u.getHost() == null || !("http".equals(u.getScheme()) || "https".equals(u.getScheme())) || user.isEmpty() || pass.isEmpty() || user.contains(":")) throw new Exception("配对文件中的地址或凭据无效");
        save(c, server, user, pass, j.optString("screenshotDirectory", "/storage/emulated/0/DCIM/Screenshots"));
    }
}
