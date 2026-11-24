package io.github.fantuani.padpaste;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.regex.Pattern;

final class ImageFormats {
    private static final int MAX_CAPTURE_BYTES = 50 * 1024 * 1024;
    private static final Pattern IMAGE_NAME = Pattern.compile(".*\\.(png|jpe?g|webp)$", Pattern.CASE_INSENSITIVE);
    static String canonical(String name) {
        if (!name.startsWith("Screenshot_") && !name.startsWith(".pending-")) return null;
        if (!IMAGE_NAME.matcher(name).matches()) return null;
        if (name.startsWith("Screenshot_")) return name;
        if (name.startsWith(".pending-")) { int start = name.indexOf("Screenshot_"); if (start >= 0) return name.substring(start); }
        return null;
    }
    // A capture may still be growing between filesystem notifications. Probe the edges
    // before allocating the whole image, and validate the bytes we actually deliver.
    static byte[] readComplete(File file) throws IOException {
        long modified = file.lastModified();
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            if (length > MAX_CAPTURE_BYTES) throw new IOException("图片超过 50 MB");
            if (length < 32) return null;
            int size = (int) length;
            if (size > 64 * 1024) {
                byte[] edges = new byte[20];
                input.readFully(edges, 0, 12);
                input.seek(length - 8);
                input.readFully(edges, 12, 8);
                if (!complete(edges, 12, size)) return null;
                input.seek(0);
            }
            byte[] data = new byte[size];
            input.readFully(data);
            return file.length() == length && file.lastModified() == modified && complete(data) ? data : null;
        } catch (EOFException changedWhileReading) {
            return null;
        } catch (FileNotFoundException missing) {
            if (!file.exists()) return null; // Pending MediaStore files can be renamed at any time.
            throw missing;
        }
    }

    static boolean complete(byte[] data) {
        return data.length >= 32 && complete(data, data.length - 8, data.length);
    }

    private static boolean complete(byte[] data, int tail, int length) {
        if ((data[0] & 255) == 255 && (data[1] & 255) == 216) {
            return (data[tail + 6] & 255) == 255 && (data[tail + 7] & 255) == 217;
        }
        if ((data[0] & 255) == 137 && data[1] == 80 && data[2] == 78 && data[3] == 71) {
            return data[tail] == 73 && data[tail + 1] == 69 && data[tail + 2] == 78 && data[tail + 3] == 68;
        }
        if (data[0] == 82 && data[1] == 73 && data[2] == 70 && data[3] == 70) {
            long size = (data[4] & 255L) | ((data[5] & 255L) << 8) | ((data[6] & 255L) << 16) | ((data[7] & 255L) << 24);
            return size + 8 <= length && data[8] == 87 && data[9] == 69 && data[10] == 66 && data[11] == 80;
        }
        return false;
    }
}
