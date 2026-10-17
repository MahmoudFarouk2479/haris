package dev.haris.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.haris.agent.AgentService;
import dev.haris.agent.AgentStoppedException;
import dev.haris.agent.ConversationService;
import dev.haris.llm.ProviderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** POST /api/chat/stop and the "stopped" event the HUD gets instead of an error. */
class ChatStopTest {

    private final AgentService agent = mock(AgentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ChatController(agent, mock(ProviderFactory.class), mock(ConversationService.class))).build();

    @Test
    void stopReportsWhetherAReplyWasRunning() throws Exception {
        when(agent.stop()).thenReturn(true);
        mvc.perform(post("/api/chat/stop")).andExpect(status().isOk()).andExpect(jsonPath("$.stopped").value(true));

        when(agent.stop()).thenReturn(false);
        mvc.perform(post("/api/chat/stop")).andExpect(jsonPath("$.stopped").value(false));
    }

    @Test
    void aStoppedReplyEndsWithAStoppedEventNotAnError() throws Exception {
        doThrow(new AgentStoppedException()).when(agent).run(anyString(), any());

        MvcResult started = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(5000);
        String body = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("event:stopped").doesNotContain("event:error");
    }
}
