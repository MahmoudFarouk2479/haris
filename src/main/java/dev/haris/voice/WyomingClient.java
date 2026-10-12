package dev.haris.voice;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Minimal client for the Wyoming protocol spoken by the local voice containers: a JSON
 * header line, then optional extra JSON data, then an optional binary payload (PCM audio).
 * It only ever connects to 127.0.0.1, on ports fixed in configuration.
 */
public final class WyomingClient implements AutoCloseable {

    public record Event(String type, JsonNode data, byte[] payload) {}

    private static final int MAX_HEADER = 64 * 1024;
    private static final int MAX_DATA = 1024 * 1024;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final JsonMapper json;

    public WyomingClient(int port, int readTimeoutMs, JsonMapper json) throws IOException {
        this.json = json;
        this.socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
        socket.setSoTimeout(readTimeoutMs);
        socket.setTcpNoDelay(true);
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    public synchronized void send(String type, Map<String, ?> data) throws IOException {
        send(type, data, null);
    }

    public synchronized void send(String type, Map<String, ?> data, byte[] payload) throws IOException {
        byte[] dataBytes = data == null || data.isEmpty() ? new byte[0] : json.writeValueAsBytes(data);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", type);
        if (dataBytes.length > 0) {
            header.put("data_length", dataBytes.length);
        }
        if (payload != null && payload.length > 0) {
            header.put("payload_length", payload.length);
        }
        out.write(json.writeValueAsBytes(header));
        out.write('\n');
        out.write(dataBytes);
        if (payload != null) {
            out.write(payload);
        }
        out.flush();
    }

    public Event read() throws IOException {
        JsonNode header = json.readTree(readLine());
        String type = header.path("type").asString();
        ObjectNode data = header.path("data").isObject() ? (ObjectNode) header.path("data").deepCopy() : json.createObjectNode();

        int dataLength = header.path("data_length").asInt(0);
        if (dataLength < 0 || dataLength > MAX_DATA) {
            throw new IOException("Wyoming data too large");
        }
        if (dataLength > 0) {
            JsonNode extra = json.readTree(readExactly(dataLength));
            if (extra.isObject()) {
                data.setAll((ObjectNode) extra);
            }
        }
        int payloadLength = header.path("payload_length").asInt(0);
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
            throw new IOException("Wyoming payload too large");
        }
        byte[] payload = payloadLength > 0 ? readExactly(payloadLength) : new byte[0];
        return new Event(type, data, payload);
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != '\n') {
            if (b < 0) {
                throw new EOFException("Wyoming connection closed");
            }
            if (line.size() >= MAX_HEADER) {
                throw new IOException("Wyoming header too large");
            }
            line.write(b);
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    private byte[] readExactly(int n) throws IOException {
        byte[] buf = in.readNBytes(n);
        if (buf.length != n) {
            throw new EOFException("Wyoming connection closed mid-message");
        }
        return buf;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
