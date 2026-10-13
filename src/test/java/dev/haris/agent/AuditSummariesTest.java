package dev.haris.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import dev.haris.memory.ForgetTool;
import dev.haris.memory.MemoryService;
import dev.haris.memory.MemoryStore;
import dev.haris.memory.RememberTool;
import dev.haris.tools.ToolOutcome;
import dev.haris.tools.impl.ClipboardWriteTool;
import dev.haris.tools.impl.ComposeMessageTool;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** What reaches ~/.haris/audit.jsonl must never be the private text itself (F7). */
class AuditSummariesTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void clipboardWritesAreLoggedByLengthOnly() throws Exception {
        ObjectNode input = json.createObjectNode().put("text", "hunter2-bank-password");
        assertThat(new ClipboardWriteTool().auditSummary(input))
                .isEqualTo("Copy 21 characters to clipboard").doesNotContain("hunter2");
    }

    @Test
    void messageDraftsAreLoggedWithoutTheirBody() throws Exception {
        ObjectNode input = json.createObjectNode().put("channel", "email").put("to", "a@b.example")
                .put("text", "the secret plan");
        assertThat(new ComposeMessageTool().auditSummary(input))
                .isEqualTo("Draft email message to a@b.example (15 characters)").doesNotContain("secret plan");
    }

    @Test
    void memoryWritesAndUndoDescriptionsLeaveTheFactOut() throws Exception {
        MemoryService memory = mock(MemoryService.class);
        MemoryStore store = mock(MemoryStore.class);
        when(memory.store()).thenReturn(store);
        when(store.add(any(), any())).thenReturn(new MemoryStore.Fact("m7", "Lives at 5 Nile St", "fact", "2026-10-13"));
        when(store.all()).thenReturn(List.of(new MemoryStore.Fact("m7", "Lives at 5 Nile St", "fact", "2026-10-13")));

        ObjectNode remember = json.createObjectNode().put("fact", "Lives at 5 Nile St").put("category", "fact");
        RememberTool rememberTool = new RememberTool(memory);
        assertThat(rememberTool.auditSummary(remember)).isEqualTo("Remember a fact (18 characters)");
        ToolOutcome added = rememberTool.run(remember);
        assertThat(added.undo().description()).isEqualTo("forget memory m7").doesNotContain("Nile");

        ObjectNode forget = json.createObjectNode().put("id", "m7");
        ForgetTool forgetTool = new ForgetTool(memory);
        assertThat(forgetTool.auditSummary(forget)).isEqualTo("Forget memory m7");
        ToolOutcome removed = forgetTool.run(forget);
        assertThat(removed.undo().description()).isEqualTo("remember memory m7 again").doesNotContain("Nile");
    }
}
