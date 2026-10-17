package dev.haris.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import dev.haris.llm.ChatProvider;
import dev.haris.llm.Part;
import dev.haris.llm.ProviderException;
import dev.haris.llm.ProviderFactory;
import dev.haris.llm.ProviderReply;
import dev.haris.llm.ToolSpec;
import dev.haris.llm.Turn;
import dev.haris.settings.Settings;
import dev.haris.settings.SettingsService;
import dev.haris.tools.PathGuard;
import dev.haris.tools.Risk;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolOutcome;
import dev.haris.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Stop button: a running reply ends now, nothing new runs, and a tool already running finishes. */
class StopTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private ConfirmationBroker broker;
    private ConversationService conversations;
    private AuditLog audit;
    private Script model;
    private AgentService agent;
    private Listener listener;

    @BeforeEach
    void setUp() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.get()).thenReturn(Settings.defaults());
        PathGuard paths = mock(PathGuard.class);
        when(paths.roots()).thenReturn(List.of());
        broker = new ConfirmationBroker();
        conversations = new ConversationService(settings, paths, broker);
        audit = mock(AuditLog.class);
        model = new Script();
        listener = new Listener();
    }

    private void useTools(Tool... tools) {
        ProviderFactory providers = mock(ProviderFactory.class);
        when(providers.current()).thenReturn(model);
        agent = new AgentService(providers, conversations, new ToolRegistry(List.of(tools)), broker, audit,
                new UndoStack(audit), json);
    }

    @Test
    void stoppingWhileAnApprovalIsOpenDeniesItAndRunsNothingElse() {
        Fake critical = new Fake("critical_tool", Risk.CRITICAL);
        Fake safe = new Fake("safe_tool", Risk.SAFE);
        useTools(critical, safe);
        listener.onAsk = request -> agent.stop();   // the user presses Stop instead of answering
        model.calls("critical_tool", "safe_tool");

        assertThatThrownBy(() -> agent.run("go", listener)).isInstanceOf(AgentStoppedException.class);

        assertThat(critical.runs).isZero();
        assertThat(safe.runs).isZero();
        verify(audit).record(eq("critical_tool"), any(), eq(Risk.CRITICAL), eq("denied"), any());
    }

    @Test
    void stoppingDuringAStreamedReplyEndsItAndForgetsTheExchange() {
        useTools();
        model.streams("first part ", "second part");
        listener.onFirstDelta = () -> agent.stop();
        int before = conversations.current().size();

        assertThatThrownBy(() -> agent.run("tell me a story", listener)).isInstanceOf(AgentStoppedException.class);

        assertThat(listener.deltas).containsExactly("first part ");
        assertThat(conversations.current().size()).isEqualTo(before);
    }

    @Test
    void aToolThatIsAlreadyRunningFinishesButTheNextOneDoesNotStart() {
        Fake[] second = new Fake[1];
        Fake first = new Fake("first_tool", Risk.SAFE) {
            @Override public ToolOutcome run(JsonNode input) {
                agent.stop();   // pressed while this tool is working
                return super.run(input);
            }
        };
        second[0] = new Fake("second_tool", Risk.SAFE);
        useTools(first, second[0]);
        model.calls("first_tool", "second_tool");

        assertThatThrownBy(() -> agent.run("go", listener)).isInstanceOf(AgentStoppedException.class);

        assertThat(first.runs).isEqualTo(1);
        assertThat(second[0].runs).isZero();
        verify(audit).record(eq("first_tool"), any(), eq(Risk.SAFE), eq("auto"), eq("ok"));
    }

    @Test
    void stoppingInterruptsAModelThatHasNotAnsweredYet() throws Exception {
        useTools();
        CountDownLatch waiting = new CountDownLatch(1);
        model.blockUntilInterrupted(waiting);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread run = Thread.ofVirtual().start(() -> {
            try {
                agent.run("hello", listener);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });

        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        agent.stop();
        run.join(5000);

        assertThat(run.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(AgentStoppedException.class);
    }

    @Test
    void aStopWithNothingRunningDoesNotCancelTheNextMessage() {
        Fake safe = new Fake("safe_tool", Risk.SAFE);
        useTools(safe);
        assertThat(agent.stop()).isFalse();
        model.calls("safe_tool");

        agent.run("go", listener);

        assertThat(safe.runs).isEqualTo(1);
    }

    // ── fakes ─────────────────────────────────────────────────────────────

    private static ProviderReply reply(Part... parts) {
        return new ProviderReply(new Turn(Turn.Role.ASSISTANT, List.of(parts), null), false, false, null);
    }

    private final class Script implements ChatProvider {
        private final Deque<java.util.function.Function<Consumer<String>, ProviderReply>> steps = new ArrayDeque<>();

        /** Each named tool is requested in its own model turn, then the model answers in text. */
        void calls(String... toolNames) {
            int i = 0;
            for (String name : toolNames) {
                ProviderReply r = reply(new Part.ToolCall("call" + i++, name, json.createObjectNode()));
                steps.add(onDelta -> r);
            }
            steps.add(onDelta -> reply(new Part.Text("done")));
        }

        void streams(String... chunks) {
            steps.add(onDelta -> {
                for (String c : chunks) {
                    onDelta.accept(c);
                }
                return reply(new Part.Text(String.join("", chunks)));
            });
        }

        /** Behaves like an HTTP call waiting for the first byte: only an interrupt ends it. */
        void blockUntilInterrupted(CountDownLatch waiting) {
            steps.add(onDelta -> {
                waiting.countDown();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ProviderException("Request cancelled.", e);
                }
                return reply(new Part.Text("too late"));
            });
        }

        @Override public String label() { return "fake"; }
        @Override public List<String> listModels() { return List.of(); }

        @Override
        public ProviderReply complete(String system, List<Turn> history, List<ToolSpec> tools, Consumer<String> onDelta) {
            return steps.removeFirst().apply(onDelta);
        }
    }

    private static class Fake implements Tool {
        final String name;
        final Risk risk;
        int runs;

        Fake(String name, Risk risk) {
            this.name = name;
            this.risk = risk;
        }

        @Override public String name() { return name; }
        @Override public String description() { return name; }
        @Override public Map<String, Object> schema() { return Schema.none(); }
        @Override public Risk risk(JsonNode input) { return risk; }
        @Override public String summarize(JsonNode input) { return name; }

        @Override
        public ToolOutcome run(JsonNode input) {
            runs++;
            return ToolOutcome.ok("ok");
        }
    }

    private static final class Listener implements AgentListener {
        Consumer<ConfirmationBroker.Request> onAsk = r -> {};
        Runnable onFirstDelta = () -> {};
        final List<String> deltas = new java.util.ArrayList<>();

        @Override public void onConfirm(ConfirmationBroker.Request request) { onAsk.accept(request); }

        @Override
        public void onDelta(String text) {
            deltas.add(text);
            if (deltas.size() == 1) {
                onFirstDelta.run();
            }
        }

        @Override public void onMeta(String providerLabel) {}
        @Override public void onTool(String callId, String tool, String summary, String status, String detail) {}
        @Override public void onNotice(String message) {}
        @Override public void onSecurity(boolean externalContent) {}
        @Override public void onImage(String callId, String dataUrl) {}
    }
}
