package dev.haris.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** When SearXNG is down the hint must be one that works: no docker command, which would lack its secret. */
class SearchClientTest {

    @Test
    void aStoppedSearchEngineSuggestsRestartingHarisNotADockerCommand() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();   // closed again: nothing listens there
        }
        SearchClient client = new SearchClient(JsonMapper.builder().build(), port);

        IOException e = assertThrows(IOException.class, () -> client.search("weather", null, 3));

        assertEquals(SearchClient.NOT_RUNNING, e.getMessage());
        assertFalse(e.getMessage().contains("docker compose"));
    }
}
