package dev.haris.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class ImagesTest {

    @Test
    void largeCapturesAreScaledToTheVisionLimit() throws IOException {
        byte[] jpeg = Images.toJpeg(new BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB));
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(back.getWidth()).isEqualTo(Images.MAX_EDGE);
        assertThat(back.getHeight()).isEqualTo(882);
    }

    @Test
    void cameraPhotosAreReEncodedWithoutTheirMetadata() throws IOException {
        byte[] original = Images.toJpeg(new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB));
        // splice a fake EXIF-like comment segment (COM) carrying "GPS" right after the SOI marker
        byte[] comment = "GPS 30.0444N 31.2357E".getBytes(StandardCharsets.US_ASCII);
        byte[] tagged = new byte[original.length + 4 + comment.length];
        tagged[0] = (byte) 0xFF;
        tagged[1] = (byte) 0xD8;
        tagged[2] = (byte) 0xFF;
        tagged[3] = (byte) 0xFE;
        tagged[4] = 0;
        tagged[5] = (byte) (comment.length + 2);
        System.arraycopy(comment, 0, tagged, 6, comment.length);
        System.arraycopy(original, 2, tagged, 6 + comment.length, original.length - 2);
        assertThat(new String(tagged, StandardCharsets.ISO_8859_1)).contains("GPS");

        byte[] clean = Images.sanitize(tagged);
        assertThat(new String(clean, StandardCharsets.ISO_8859_1)).doesNotContain("GPS");
    }

    @Test
    void garbageIsRefused() {
        assertThatThrownBy(() -> Images.sanitize("not an image".getBytes())).isInstanceOf(IOException.class);
    }
}
