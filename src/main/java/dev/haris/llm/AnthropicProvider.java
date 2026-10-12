package dev.haris.llm;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.WebSearchTool20260209;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Claude, via the official Anthropic Java SDK.
 *
 * <p>Assistant turns are stored as the exact {@link MessageParam} the API returned
 * ({@code Message.toParam()}) and replayed unchanged, so thinking blocks stay valid across
 * the tool loop. The conversation layer keeps the history append-only and the system prompt
 * and tool list frozen for the life of a conversation, as preserved thinking requires.
 */
public class AnthropicProvider implements ChatProvider {

    /** Models that accept server-side refusal fallbacks in the "default" routing form. */
    private static final List<String> FALLBACK_MODELS =
            List.of("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5");

    private static final List<String> MODELS = List.of(
            "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1");

    private final AnthropicClient client;
    private final String model;
    private final OutputConfig.Effort effort;
    private final boolean webSearch;
    private final JsonMapper json;

    public AnthropicProvider(AnthropicClient client, String model, String effort, boolean webSearch, JsonMapper json) {
        this.client = client;
        this.model = model;
        this.webSearch = webSearch;
        this.json = json;
        this.effort = switch (effort) {
            case "low" -> OutputConfig.Effort.LOW;
            case "high" -> OutputConfig.Effort.HIGH;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> OutputConfig.Effort.MEDIUM;
        };
    }

    @Override
    public String label() {
        return "Claude · " + model;
    }

    @Override
    public List<String> listModels() {
        return MODELS;
    }

    @Override
    public ProviderReply complete(String systemPrompt, List<Turn> history, List<ToolSpec> tools, Consumer<String> onDelta) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(systemPrompt)
                .outputConfig(OutputConfig.builder().effort(effort).build())
                .messages(history.stream().map(this::toParam).toList());

        for (ToolSpec spec : tools) {
            params.addTool(toTool(spec));
        }
        if (webSearch) {
            params.addTool(WebSearchTool20260209.builder().maxUses(5L).build());
        }
        if (FALLBACK_MODELS.contains(model)) {
            // If a safety classifier declines, let the server re-route to a suitable model
            // instead of ending the turn with nothing.
            params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        MessageAccumulator accumulator = MessageAccumulator.create();
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params.build())) {
            stream.stream().forEach(event -> {
                accumulator.accumulate(event);
                event.contentBlockDelta()
                        .flatMap(d -> d.delta().text())
                        .ifPresent(t -> onDelta.accept(t.text()));
            });
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new ProviderException("Claude rejected the API key. Check it in Settings.", e);
        } catch (RateLimitException e) {
            throw new ProviderException("Claude rate limit reached. Try again in a moment.", e);
        } catch (AnthropicServiceException e) {
            throw new ProviderException("Claude returned an error (HTTP " + e.statusCode() + ").", e);
        } catch (AnthropicIoException e) {
            throw new ProviderException("Could not reach Claude. Check your internet connection.", e);
        }

        Message message = accumulator.message();
        String stop = message.stopReason().map(Object::toString).orElse("");

        List<Part> parts = new ArrayList<>();
        boolean untrusted = false;
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(t -> parts.add(new Part.Text(t.text())));
            block.toolUse().ifPresent(tu -> parts.add(new Part.ToolCall(tu.id(), tu.name(), toNode(tu._input()))));
            if (block.webSearchToolResult().isPresent() || block.webFetchToolResult().isPresent()) {
                untrusted = true;
            }
        }

        if ("refusal".equalsIgnoreCase(stop) && parts.isEmpty()) {
            throw new ProviderException("Claude declined this request.");
        }
        boolean hasCalls = parts.stream().anyMatch(p -> p instanceof Part.ToolCall);
        if ("max_tokens".equalsIgnoreCase(stop) && hasCalls) {
            // A tool call cut off mid-arguments must not run.
            throw new ProviderException("Claude's reply was cut off while preparing an action; nothing was run.");
        }
        String notice = "max_tokens".equalsIgnoreCase(stop) ? "The reply was cut off at the length limit." : null;

        Turn turn = new Turn(Turn.Role.ASSISTANT, parts, message.toParam());
        return new ProviderReply(turn, untrusted, "pause_turn".equalsIgnoreCase(stop), notice);
    }

    private MessageParam toParam(Turn turn) {
        if (turn.role() == Turn.Role.ASSISTANT) {
            if (turn.nativePayload() instanceof MessageParam native_) {
                return native_;   // replay exactly what the API produced
            }
            return MessageParam.builder().role(MessageParam.Role.ASSISTANT).content(turn.text()).build();
        }
        List<Part.ToolResult> results = turn.toolResults();
        if (results.isEmpty()) {
            return MessageParam.builder().role(MessageParam.Role.USER).content(turn.text()).build();
        }
        List<ContentBlockParam> blocks = results.stream()
                .map(r -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(r.callId())
                        .content(r.jpeg() == null ? ToolResultBlockParam.Content.ofString(r.content())
                                : ToolResultBlockParam.Content.ofBlocks(List.of(
                                        ToolResultBlockParam.Content.Block.ofText(r.content()),
                                        ToolResultBlockParam.Content.Block.ofImage(Base64ImageSource.builder()
                                                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                                .data(Base64.getEncoder().encodeToString(r.jpeg()))
                                                .build()))))
                        .isError(r.error())
                        .build()))
                .toList();
        return MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(blocks).build();
    }

    @SuppressWarnings("unchecked")
    private static Tool toTool(ToolSpec spec) {
        Map<String, Object> props = (Map<String, Object>) spec.schema().getOrDefault("properties", Map.of());
        List<String> required = (List<String>) spec.schema().getOrDefault("required", List.of());

        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        props.forEach((k, v) -> properties.putAdditionalProperty(k, JsonValue.from(v)));

        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(properties.build())
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                // Stream large arguments as they are generated; every tool validates its own input.
                .eagerInputStreaming(true)
                .build();
    }

    /** SDK JSON (Jackson 2) to the app's JSON (Jackson 3). Broken JSON becomes a MissingNode. */
    private JsonNode toNode(JsonValue value) {
        try {
            return json.readTree(ObjectMappers.jsonMapper().writeValueAsString(value));
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }
}
