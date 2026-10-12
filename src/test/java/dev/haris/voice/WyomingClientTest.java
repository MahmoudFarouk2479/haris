package dev.haris.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Speaks the Wyoming wire format against a fake engine: header line, data, payload. */
class WyomingClientTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void transcribeSendsAudioAndReadsTranscript() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            List<String> seen = new ArrayList<>();
            int[] audioBytes = {0};
            CompletableFuture<Void> engine = CompletableFuture.runAsync(() -> {
                try (Socket s = server.accept()) {
                    InputStream in = new BufferedInputStream(s.getInputStream());
                    for (;;) {
                        JsonNode header = json.readTree(line(in));
                        String type = header.path("type").asString();
                        seen.add(type);
                        int dl = header.path("data_length").asInt(0);
                        int pl = header.path("payload_length").asInt(0);
                        JsonNode data = dl > 0 ? json.readTree(in.readNBytes(dl)) : json.createObjectNode();
                        if (type.equals("transcribe")) {
                            assertThat(data.path("language").asString()).isEqualTo("ar");
                        }
                        audioBytes[0] += in.readNBytes(pl).length;
                        if (type.equals("audio-stop")) {
                            // reply the "inline data" way, to check both encodings are understood
                            OutputStream out = s.getOutputStream();
                            out.write(("{\"type\":\"transcript\",\"data\":{\"text\":\" مرحبا يا جارفيس \"}}\n")
                                    .getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            return;
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            VoiceEngines engines = new VoiceEngines(json, server.getLocalPort(), 1);
            String text = engines.transcribe(new byte[20_000], "ar");
            engine.get();

            assertThat(text).isEqualTo("مرحبا يا جارفيس");
            assertThat(seen.getFirst()).isEqualTo("transcribe");
            assertThat(seen.get(1)).isEqualTo("audio-start");
            assertThat(seen.getLast()).isEqualTo("audio-stop");
            assertThat(audioBytes[0]).isEqualTo(20_000);
        }
    }

    @Test
    void synthesizeAssemblesAPlayableWav() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> engine = CompletableFuture.runAsync(() -> {
                try (Socket s = server.accept()) {
                    InputStream in = new BufferedInputStream(s.getInputStream());
                    JsonNode header = json.readTree(line(in));
                    JsonNode data = json.readTree(in.readNBytes(header.path("data_length").asInt()));
                    assertThat(data.path("voice").path("name").asString()).isEqualTo("en_GB-alan-medium");
                    OutputStream out = s.getOutputStream();
                    send(out, "audio-start", Map.of("rate", 22050, "width", 2, "channels", 1), null);
                    send(out, "audio-chunk", Map.of("rate", 22050, "width", 2, "channels", 1), new byte[1000]);
                    send(out, "audio-chunk", Map.of("rate", 22050, "width", 2, "channels", 1), new byte[500]);
                    send(out, "audio-stop", Map.of(), null);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            byte[] wav = new VoiceEngines(json, 1, server.getLocalPort()).synthesize("Hello", "en_GB-alan-medium");
            engine.get();

            assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
            assertThat(new String(wav, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
            assertThat(wav.length).isEqualTo(44 + 1500);
        }
    }

    @Test
    void cleanStripsMarkdownAndLinks() {
        assertThat(VoiceController.clean("**Done**, sir. See https://example.com/x?y=1 and `code`."))
                .isEqualTo("Done , sir. See link and code .");
    }

    private void send(OutputStream out, String type, Map<String, ?> data, byte[] payload) throws Exception {
        byte[] d = json.writeValueAsBytes(data);
        String header = "{\"type\":\"" + type + "\",\"data_length\":" + d.length
                + (payload != null ? ",\"payload_length\":" + payload.length : "") + "}\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(d);
        if (payload != null) {
            out.write(payload);
        }
        out.flush();
    }

    private static String line(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != '\n') {
            if (c < 0) {
                throw new IllegalStateException("closed");
            }
            b.write(c);
        }
        return b.toString(StandardCharsets.UTF_8);
    }
}
