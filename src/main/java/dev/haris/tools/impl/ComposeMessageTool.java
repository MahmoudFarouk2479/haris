package dev.haris.tools.impl;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import dev.haris.tools.Args;
import dev.haris.tools.Risk;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Prepares a WhatsApp, Telegram or e-mail message and opens it as a draft. The assistant can
 * never press Send: the user reads the message in the real app and sends it themselves.
 */
@Component
public class ComposeMessageTool implements Tool {

    private static final Set<String> CHANNELS = Set.of("whatsapp", "telegram", "email");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9]{8,15}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s,;<>]{1,64}@[^@\\s,;<>]{1,255}");

    @Override
    public String name() {
        return "compose_message";
    }

    @Override
    public String description() {
        return "Open a message draft in WhatsApp, Telegram or the mail app. The user reviews and sends it; you cannot send.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("channel", "text"),
                Schema.oneOf("channel", "Messaging app", "email", "telegram", "whatsapp"),
                Schema.str("to", "WhatsApp: phone number with country code; email: address; telegram: leave empty"),
                Schema.str("subject", "Email subject"),
                Schema.str("text", "The message body"));
    }

    @Override
    public Risk risk(JsonNode input) {
        return Risk.CONFIRM;
    }

    @Override
    public boolean sendsDataOut() {
        return true;
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        String channel = Args.oneOf(input, "channel", CHANNELS);
        String to = Args.optStr(input, "to", 120, "");
        String text = Args.str(input, "text", 4000);
        return "Draft " + channel + " message" + (to.isEmpty() ? "" : " to " + to) + ": \""
                + (text.length() > 120 ? text.substring(0, 120) + "…" : text) + "\"";
    }

    @Override
    public String auditSummary(JsonNode input) throws ToolException {
        String channel = Args.oneOf(input, "channel", CHANNELS);
        String to = Args.optStr(input, "to", 120, "");
        int chars = Args.str(input, "text", 4000).length();
        return "Draft " + channel + " message" + (to.isEmpty() ? "" : " to " + to) + " (" + chars + " characters)";
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        String channel = Args.oneOf(input, "channel", CHANNELS);
        String to = Args.optStr(input, "to", 120, "").replaceAll("[\\s()-]", "");
        String text = Args.str(input, "text", 4000);
        URI uri = switch (channel) {
            case "whatsapp" -> {
                if (!PHONE.matcher(to).matches()) {
                    throw new ToolException("WhatsApp needs a phone number with country code, e.g. +201234567890.");
                }
                yield URI.create("whatsapp://send?phone=" + to.replace("+", "") + "&text=" + enc(text));
            }
            case "telegram" -> URI.create("tg://msg?text=" + enc(text));
            default -> {
                if (!to.isEmpty() && !EMAIL.matcher(to).matches()) {
                    throw new ToolException("That e-mail address does not look valid.");
                }
                String subject = Args.optStr(input, "subject", 200, "");
                yield URI.create("mailto:" + to + "?subject=" + enc(subject) + "&body=" + enc(text));
            }
        };
        Launch.uri(uri);
        return ToolOutcome.ok("Draft opened in " + channel + ". The user must press Send themselves.");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
