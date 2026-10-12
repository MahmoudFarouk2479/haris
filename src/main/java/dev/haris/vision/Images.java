package dev.haris.vision;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Every picture the model sees passes through here: scaled so its long edge is at most
 * 1568 px (what vision models use anyway — more pixels only cost more and reveal more) and
 * re-encoded as a fresh JPEG, which also drops any metadata (location, device) it carried.
 */
public final class Images {

    public static final int MAX_EDGE = 1568;

    private Images() {}

    public static byte[] toJpeg(BufferedImage source) throws IOException {
        BufferedImage img = fit(source);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.82f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** Decodes untrusted image bytes (from the HUD camera) and re-encodes them cleanly. */
    public static byte[] sanitize(byte[] untrusted) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(untrusted));
        if (img == null || img.getWidth() < 16 || img.getHeight() < 16 || img.getWidth() > 8000 || img.getHeight() > 8000) {
            throw new IOException("not a usable image");
        }
        return toJpeg(img);
    }

    private static BufferedImage fit(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);   // no alpha, no metadata
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }
}
