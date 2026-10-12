package dev.haris.tools.impl;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

import dev.haris.tools.ToolException;

/** Hands a URI to Windows' default handler (browser, mail app, WhatsApp…) — as an argument, never a shell string. */
final class Launch {

    private Launch() {}

    static void uri(URI uri) throws ToolException {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
                    && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                Desktop.getDesktop().browse(uri);
                return;
            }
            new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", uri.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException | UnsupportedOperationException e) {
            throw new ToolException("Windows could not open that link.");
        }
    }
}
