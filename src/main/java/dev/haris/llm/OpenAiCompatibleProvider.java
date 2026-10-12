package dev.haris.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Ollama, LM Studio, and any other server speaking the OpenAI chat-completions protocol,
 * including its function-calling extension. Built on the JDK's HttpClient: redirects are
 * never followed, so a local engine cannot bounce the conversation to another host.
 */
public class OpenAiCompatibleProvider implements ChatProvider {

    private static final Object END = new Object();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final String name;
    private final String baseUrl;
    private final String model;
    private final String apiKey;   // null for local engines
    private final JsonMapper json;
    /** How long the server may stay silent (before replying, or mid-reply) before we give up. */
    private final Duration patience;

    public OpenAiCompatibleProvider(String name, String baseUrl, String model, String apiKey, JsonMapper json) {
        this(name, baseUrl, model, apiKey, json, Duration.ofMinutes(10));
    }

    public OpenAiCompatibleProvider(String name, String baseUrl, String model, String apiKey, JsonMapper json,
                                    Duration patience) {
        this.patience = patience;
        this.name = name;
        this.baseUrl = baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "");
        this.model = model;
        this.apiKey = apiKey;
        this.json = json;
    }

    @Override
    public String label() {
        return name + " · " + (model == null || model.isBlank() ? "no model selected" : model);
    }

    @Override
    public List<String> listModels() {
        try {
            HttpResponse<String> res = HTTP.send(request("/v1/models").GET().timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode m : json.readTree(res.body()).path("data")) {
                String id = m.path("id").asString();
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
            ids.sort(String::compareToIgnoreCase);
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public ProviderReply complete(String systemPrompt, List<Turn> history, List<ToolSpec> tools, Consumer<String> onDelta) {
        if (model == null || model.isBlank()) {
            throw new ProviderException("No model selected for " + name + ". Pick one in Settings.");
        }
        try {
            return call(systemPrompt, history, tools, onDelta, null);
        } catch (ToolsUnsupported e) {
            // Small local models often have no function calling. Keep chatting without tools.
            return call(systemPrompt, history, List.of(), onDelta,
                    "This model does not support tools, so I can only talk. Pick a tool-capable model "
                            + "(e.g. qwen2.5 or llama3.2) to let me act on the computer.");
        }
    }

    private ProviderReply call(String systemPrompt, List<Turn> history, List<ToolSpec> tools,
                               Consumer<String> onDelta, String notice) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", toMessages(systemPrompt, history));
        body.put("stream", true);
        if (!tools.isEmpty()) {
            body.put("tools", tools.stream().map(t -> Map.of("type", "function", "function", Map.of(
                    "name", t.name(), "description", t.description(), "parameters", t.schema()))).toList());
        }

        HttpRequest req = request("/v1/chat/completions")
                .header("Content-Type", "application/json")
                .timeout(patience)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        HttpResponse<InputStream> res;
        try {
            res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ProviderException(name + " is not reachable at " + baseUrl + ". Is it running?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Request cancelled.", e);
        }

        StringBuilder text = new StringBuilder();
        Map<Integer, CallBuilder> calls = new TreeMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
            if (res.statusCode() != 200) {
                String err = reader.lines().limit(20).reduce("", String::concat);
                if (res.statusCode() == 400 && !tools.isEmpty() && err.toLowerCase().contains("tool")) {
                    throw new ToolsUnsupported();
                }
                throw new ProviderException(switch (res.statusCode()) {
                    case 401 -> name + " rejected the API key or access token. Check it in Settings.";
                    case 403 -> name + " refused the request (HTTP 403)."
                            + ("Colab".equals(name) ? " If a tunnel goes straight to Ollama, Ollama refuses the tunnel's"
                            + " host name: start localtunnel with --local-host 127.0.0.1, or use the Haris notebook." : "");
                    case 404 -> name + " does not know the model \"" + model + "\".";
                    case 429 -> name + " rate limit reached. Try again in a moment.";
                    case 502, 503, 504, 530 -> name + " is not answering (HTTP " + res.statusCode()
                            + "). If it runs in Colab, the session may have ended: run the notebook again.";
                    default -> name + " returned HTTP " + res.statusCode() + ".";
                });
            }
            // The request timeout only covers the wait for the first response. A tunnel can also stall
            // mid-reply (a Colab session ending), so lines are read on a side thread and the reply is
            // abandoned when the server stays silent for longer than `patience`.
            BlockingQueue<Object> lines = new LinkedBlockingQueue<>();
            Thread.ofVirtual().name("llm-stream").start(() -> {
                try {
                    String l;
                    while ((l = reader.readLine()) != null) {
                        lines.add(l);
                    }
                } catch (IOException e) {
                    lines.add(e);
                } finally {
                    lines.add(END);
                }
            });
            for (;;) {
                Object next;
                try {
                    next = lines.poll(patience.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ProviderException("Request cancelled.", e);
                }
                if (next == null) {
                    res.body().close();
                    throw new ProviderException(name + " stopped answering for " + patience.toSeconds() + " s."
                            + ("Colab".equals(name) ? " The Colab session may have ended: run the notebook again." : ""));
                }
                if (next == END) {
                    break;
                }
                if (next instanceof IOException e) {
                    throw e;
                }
                String line = (String) next;
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).strip();
                if (data.equals("[DONE]")) {
                    break;
                }
                JsonNode delta = json.readTree(data).path("choices").path(0).path("delta");
                String content = delta.path("content").asString();
                if (!content.isEmpty()) {
                    text.append(content);
                    onDelta.accept(content);
                }
                for (JsonNode tc : delta.path("tool_calls")) {
                    CallBuilder cb = calls.computeIfAbsent(tc.path("index").asInt(calls.size()), i -> new CallBuilder());
                    if (tc.hasNonNull("id")) cb.id = tc.path("id").asString();
                    JsonNode fn = tc.path("function");
                    if (fn.hasNonNull("name")) cb.name.append(fn.path("name").asString());
                    if (fn.hasNonNull("arguments")) cb.args.append(fn.path("arguments").asString());
                }
            }
        } catch (IOException e) {
            throw new ProviderException("The connection to " + name + " dropped.", e);
        }

        List<Part> parts = new ArrayList<>();
        if (!text.isEmpty()) {
            parts.add(new Part.Text(text.toString()));
        }
        for (CallBuilder cb : calls.values()) {
            String id = cb.id != null ? cb.id : "call_" + UUID.randomUUID().toString().substring(0, 8);
            parts.add(new Part.ToolCall(id, cb.name.toString(), parseArgs(cb.args.toString())));
        }
        return new ProviderReply(new Turn(Turn.Role.ASSISTANT, parts, null), false, false, notice);
    }

    private List<Map<String, ?>> toMessages(String systemPrompt, List<Turn> history) {
        List<Map<String, ?>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (Turn turn : history) {
            if (turn.role() == Turn.Role.ASSISTANT) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("content", turn.text());
                List<Part.ToolCall> tcs = turn.toolCalls();
                if (!tcs.isEmpty()) {
                    m.put("tool_calls", tcs.stream().map(tc -> Map.of(
                            "id", tc.id(), "type", "function",
                            "function", Map.of("name", tc.name(),
                                    "arguments", tc.input().isMissingNode() ? "{}" : json.writeValueAsString(tc.input()))))
                            .toList());
                }
                messages.add(m);
            } else if (!turn.toolResults().isEmpty()) {
                List<Map<String, Object>> pictures = new ArrayList<>();
                for (Part.ToolResult r : turn.toolResults()) {
                    String content = r.content();
                    if (r.jpeg() != null) {
                        content += "\n(The picture follows in the next message.)";
                        pictures.add(Map.of("type", "image_url", "image_url", Map.of("url",
                                "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(r.jpeg()))));
                    }
                    messages.add(Map.of("role", "tool", "tool_call_id", r.callId(), "content", content));
                }
                // The chat-completions protocol has no images in tool messages; vision models
                // (llava, qwen2.5-vl, gemma3…) read them from a user message instead.
                if (!pictures.isEmpty()) {
                    List<Map<String, Object>> parts = new ArrayList<>();
                    parts.add(Map.of("type", "text", "text", "[Picture returned by the tool above]"));
                    parts.addAll(pictures);
                    messages.add(Map.of("role", "user", "content", parts));
                }
            } else {
                messages.add(Map.of("role", "user", "content", turn.text()));
            }
        }
        return messages;
    }

    private JsonNode parseArgs(String raw) {
        if (raw.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode node = json.readTree(raw);
            return node.isObject() ? node : MissingNode.getInstance();
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path));
        if (apiKey != null && !apiKey.isBlank()) {
            b.header("Authorization", "Bearer " + apiKey);
        }
        return b;
    }

    private static final class CallBuilder {
        String id;
        final StringBuilder name = new StringBuilder();
        final StringBuilder args = new StringBuilder();
    }

    private static final class ToolsUnsupported extends RuntimeException {
        ToolsUnsupported() {
            super(null, null, false, false);
        }
    }
}
