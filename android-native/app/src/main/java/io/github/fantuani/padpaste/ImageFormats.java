package io.github.fantuani.padpaste;

final class ImageFormats {
    static String canonical(String name) {
        if (!name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(png|jpe?g|webp)$")) return null;
        if (name.startsWith("Screenshot_")) return name;
        if (name.startsWith(".pending-")) { int start = name.indexOf("Screenshot_"); if (start >= 0) return name.substring(start); }
        return null;
    }
    static boolean complete(byte[] a) {
        int n = a.length; if (n < 32) return false;
        if ((a[0] & 255) == 255 && (a[1] & 255) == 216) return (a[n-2] & 255) == 255 && (a[n-1] & 255) == 217;
        if ((a[0]&255)==137 && a[1]==80 && a[2]==78 && a[3]==71) return a[n-8]==73 && a[n-7]==69 && a[n-6]==78 && a[n-5]==68;
        if (a[0]==82 && a[1]==73 && a[2]==70 && a[3]==70) {
            long size=(a[4]&255L)|((a[5]&255L)<<8)|((a[6]&255L)<<16)|((a[7]&255L)<<24);
            return size+8<=n && a[8]==87 && a[9]==69 && a[10]==66 && a[11]==80;
        }
        return false;
    }
}
