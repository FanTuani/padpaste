package io.github.fantuani.padpaste;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.InputStream;
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
}
