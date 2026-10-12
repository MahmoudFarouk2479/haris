package dev.haris.memory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What Haris remembers about the user, encrypted at rest with AES-256-GCM.
 *
 * <p>The key lives in the OS credential vault, never next to the file, so copying
 * ~/.haris/memory.enc off the machine (or out of a backup) reveals nothing. GCM also
 * authenticates the data: a file edited by anything other than Haris fails to open
 * instead of silently feeding planted "memories" to the model.
 */
public class MemoryStore {

    public record Fact(String id, String text, String category, String created) {}

    public static final int MAX_FACTS = 500;
    public static final int MAX_FACT_CHARS = 300;

    private static final byte[] MAGIC = "JMEM1".getBytes(StandardCharsets.US_ASCII);
    // File-format identifiers from when the project was called JARVIS. Changing them would make
    // existing memory files unreadable, so they stay.
    private static final byte[] AAD = "jarvis-memory-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path file;
    private final Supplier<byte[]> key;
    private final JsonMapper json;
    private final SecureRandom random = new SecureRandom();
    private List<Fact> facts;

    public MemoryStore(Path file, Supplier<byte[]> key, JsonMapper json) {
        this.file = file;
        this.key = key;
        this.json = json;
    }

    public synchronized List<Fact> all() throws IOException {
        return List.copyOf(load());
    }

    public synchronized Fact add(String text, String category) throws IOException {
        List<Fact> list = load();
        String clean = text.strip().replaceAll("\\s+", " ");
        for (Fact f : list) {
            if (f.text().equalsIgnoreCase(clean)) {
                return f;   // already known
            }
        }
        if (list.size() >= MAX_FACTS) {
            throw new IOException("Memory is full (" + MAX_FACTS + " facts). Forget some first.");
        }
        Fact fact = new Fact(UUID.randomUUID().toString().substring(0, 8), clean, category, Instant.now().toString());
        list.add(fact);
        save(list);
        return fact;
    }

    public synchronized boolean remove(String id) throws IOException {
        List<Fact> list = load();
        boolean removed = list.removeIf(f -> f.id().equals(id));
        if (removed) {
            save(list);
        }
        return removed;
    }

    public synchronized void clear() throws IOException {
        save(new ArrayList<>());
    }

    /** Facts sharing the most words with the query, best first. */
    public synchronized List<Fact> search(String query, int limit) throws IOException {
        List<String> words = Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() > 1).toList();
        return load().stream()
                .map(f -> new Object[] {f, score(f.text().toLowerCase(Locale.ROOT), words)})
                .filter(p -> (int) p[1] > 0)
                .sorted(Comparator.comparingInt(p -> -(int) p[1]))
                .limit(limit)
                .map(p -> (Fact) p[0])
                .toList();
    }

    private static int score(String text, List<String> words) {
        int s = 0;
        for (String w : words) {
            if (text.contains(w)) {
                s++;
            }
        }
        return s;
    }

    // ── encryption ─────────────────────────────────────────────────────────

    private List<Fact> load() throws IOException {
        if (facts != null) {
            return facts;
        }
        if (!Files.exists(file)) {
            facts = new ArrayList<>();
            return facts;
        }
        byte[] all = Files.readAllBytes(file);
        if (all.length < MAGIC.length + IV_BYTES + 16
                || !Arrays.equals(Arrays.copyOf(all, MAGIC.length), MAGIC)) {
            throw new IOException("The memory file is not a Haris memory file.");
        }
        byte[] iv = Arrays.copyOfRange(all, MAGIC.length, MAGIC.length + IV_BYTES);
        byte[] sealed = Arrays.copyOfRange(all, MAGIC.length + IV_BYTES, all.length);
        byte[] plain;
        byte[] k = key.get();
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(AAD);
            plain = c.doFinal(sealed);
        } catch (GeneralSecurityException e) {
            throw new IOException("The memory file was modified outside Haris or the key changed; refusing to read it.");
        } finally {
            Arrays.fill(k, (byte) 0);
        }
        try {
            facts = new ArrayList<>(json.readValue(plain, new TypeReference<List<Fact>>() {}));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        return facts;
    }

    private void save(List<Fact> list) throws IOException {
        byte[] plain = json.writeValueAsBytes(list);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte[] k = key.get();
        byte[] sealed;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(AAD);
            sealed = c.doFinal(plain);
        } catch (GeneralSecurityException e) {
            throw new IOException("Encryption failed.", e);
        } finally {
            Arrays.fill(k, (byte) 0);
            Arrays.fill(plain, (byte) 0);
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, ByteBuffer.allocate(MAGIC.length + IV_BYTES + sealed.length)
                .put(MAGIC).put(iv).put(sealed).array());
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        facts = list;
    }

    /** Decodes the vault's Base64 value into a fresh 32-byte key array. */
    public static byte[] decodeKey(String base64) {
        byte[] k = Base64.getDecoder().decode(base64);
        if (k.length != 32) {
            throw new IllegalStateException("Memory key has the wrong length.");
        }
        return k;
    }
}
