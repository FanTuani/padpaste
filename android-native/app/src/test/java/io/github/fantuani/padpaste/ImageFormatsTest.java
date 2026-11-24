package io.github.fantuani.padpaste;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class ImageFormatsTest {
    @Test public void pendingRenameKeepsIdentity(){
        String name="Screenshot_2026-01-01-12-00-00-123_com.example.jpg";
        assertEquals(name,ImageFormats.canonical(name));assertEquals(name,ImageFormats.canonical(".pending-123-"+name));
        assertNull(ImageFormats.canonical("IMG_123.jpg"));assertNull(ImageFormats.canonical(name+".tmp"));
    }
    @Test public void completePngAndTruncatedWrite() throws Exception {
        byte[] image;try(InputStream in=getClass().getResourceAsStream("/image.png")){image=in.readAllBytes();}
        assertTrue(ImageFormats.complete(image));assertFalse(ImageFormats.complete(Arrays.copyOf(image,image.length-5)));
    }

    @Test public void largeCaptureCanFinishAfterAnIncompleteRead() throws Exception {
        Path path = Files.createTempFile("padpaste-capture", ".tmp");
        try {
            int size = 70000;
            byte[] jpeg = new byte[size];
            jpeg[0] = (byte) 255;
            jpeg[1] = (byte) 216;
            jpeg[size - 2] = (byte) 255;
            jpeg[size - 1] = (byte) 217;

            byte[] png = new byte[size];
            ByteBuffer.wrap(png).putInt(0x89504e47).putInt(size - 8, 0x49454e44);

            byte[] webp = new byte[size];
            ByteBuffer.wrap(webp).putInt(0x52494646).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(size - 8).order(ByteOrder.BIG_ENDIAN).putInt(0x57454250);

            for (byte[] capture : new byte[][] {jpeg, png, webp}) {
                Files.write(path, Arrays.copyOf(capture, size - 6));
                assertNull(ImageFormats.readComplete(path.toFile()));
                Files.write(path, capture);
                assertArrayEquals(capture, ImageFormats.readComplete(path.toFile()));
            }
        } finally {
            Files.deleteIfExists(path);
        }
        assertNull(ImageFormats.readComplete(path.toFile()));
    }

    @Test public void oversizedCaptureRejectedBeforeAllocating() throws Exception {
        Path path = Files.createTempFile("padpaste-large", ".tmp");
        try {
            try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
                file.setLength(50L * 1024 * 1024 + 1);
            }
            assertThrows(IOException.class, () -> ImageFormats.readComplete(path.toFile()));
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
