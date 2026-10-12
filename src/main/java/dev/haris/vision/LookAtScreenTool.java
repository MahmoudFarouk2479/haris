package dev.haris.vision;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.haris.launcher.HudWindow;
import dev.haris.tools.Args;
import dev.haris.tools.CapturingTool;
import dev.haris.tools.Risk;
import dev.haris.tools.Schema;
import dev.haris.tools.ToolException;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Takes one picture of the screen when asked. There is no continuous watching: every look is
 * a separate capture the user sees and approves before it reaches the model.
 */
@Component
public class LookAtScreenTool implements CapturingTool {

    private static final Set<String> DISPLAYS = Set.of("primary", "all");

    private final HudWindow hud;

    public LookAtScreenTool(HudWindow hud) {
        this.hud = hud;
    }

    @Override
    public String name() {
        return "look_at_screen";
    }

    @Override
    public String description() {
        return "Take one screenshot to see what is on the user's screen (an error, a page, a document). "
                + "The user previews and approves every screenshot.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of(),
                Schema.oneOf("display", "primary (default) or all monitors", "all", "primary"));
    }

    @Override
    public Risk risk(JsonNode input) {
        return Risk.CONFIRM;
    }

    @Override
    public boolean sessionApprovable() {
        return false;   // the screen changes; every capture is its own decision
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        return "all".equals(display(input)) ? "Screenshot of all monitors" : "Screenshot of the main screen";
    }

    @Override
    public Capture capture(JsonNode input) throws ToolException {
        if (GraphicsEnvironment.isHeadless()) {
            throw new ToolException("No screen is available.");
        }
        String display = display(input);
        try {
            BufferedImage shot = hud.whileHidden(() -> grab(display));
            return new Capture(Images.toJpeg(shot),
                    "Screenshot of the " + ("all".equals(display) ? "whole desktop" : "main screen")
                            + " (" + shot.getWidth() + "×" + shot.getHeight() + "), shown to you as an image.");
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("Could not capture the screen.");
        }
    }

    private static BufferedImage grab(String display) throws Exception {
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Rectangle bounds;
        if ("all".equals(display)) {
            bounds = new Rectangle();
            for (GraphicsDevice d : env.getScreenDevices()) {
                bounds = bounds.union(d.getDefaultConfiguration().getBounds());
            }
        } else {
            bounds = env.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        }
        Robot robot = new Robot();
        // The multi-resolution capture keeps full detail on scaled (high-DPI) displays.
        MultiResolutionImage multi = robot.createMultiResolutionScreenCapture(bounds);
        List<Image> variants = multi.getResolutionVariants();
        Image best = variants.getLast();
        if (best instanceof BufferedImage b) {
            return b;
        }
        BufferedImage out = new BufferedImage(best.getWidth(null), best.getHeight(null), BufferedImage.TYPE_INT_RGB);
        out.getGraphics().drawImage(best, 0, 0, null);
        return out;
    }

    private static String display(JsonNode input) throws ToolException {
        return input.has("display") ? Args.oneOf(input, "display", DISPLAYS) : "primary";
    }
}
