package dev.haris.agent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import dev.haris.tools.Risk;
import org.springframework.stereotype.Component;

/**
 * Holds actions waiting for the user's decision. The only way to resolve one is an
 * authenticated POST from the HUD carrying the request's random id — the model has no
 * channel to approve its own actions. Unanswered requests are denied after two minutes.
 */
@Component
public class ConfirmationBroker {

    public enum Decision { ALLOW_ONCE, ALLOW_SESSION, DENY }

    /**
     * @param preview     a data: URL of the exact picture that will be shared, or null
     * @param destination the engine the result goes to (e.g. "Claude · claude-opus-5-5")
     * @param localEngine whether that engine runs on this computer; the HUD words the line in its own language
     */
    public record Request(String id, String tool, String summary, Risk risk,
                          boolean sessionOption, boolean externalContent, String arguments,
                          String preview, String destination, boolean localEngine) {}

    private static final long TIMEOUT_SECONDS = 120;

    private final Map<String, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();
    private final Map<String, Request> requests = new ConcurrentHashMap<>();

    public Decision ask(String tool, String summary, Risk risk, boolean sessionOption, boolean externalContent,
                        String arguments, String preview, String destination, boolean localEngine,
                        Consumer<Request> notify) {
        return ask(tool, summary, risk, sessionOption, externalContent, arguments, preview, destination, localEngine,
                notify, () -> false);
    }

    /**
     * @param abandoned checked once the request is registered, so a Stop that raced past
     *                  {@link #denyAll()} still denies it instead of leaving it open for two minutes
     */
    public Decision ask(String tool, String summary, Risk risk, boolean sessionOption, boolean externalContent,
                        String arguments, String preview, String destination, boolean localEngine,
                        Consumer<Request> notify, BooleanSupplier abandoned) {
        Request request = new Request(UUID.randomUUID().toString(), tool, summary, risk, sessionOption,
                externalContent, arguments, preview, destination, localEngine);
        CompletableFuture<Decision> future = new CompletableFuture<>();
        pending.put(request.id(), future);
        requests.put(request.id(), request);
        try {
            if (abandoned.getAsBoolean()) {
                return Decision.DENY;
            }
            notify.accept(request);
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            return Decision.DENY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.DENY;
        } finally {
            pending.remove(request.id());
            requests.remove(request.id());
        }
    }

    /** Returns false if the id is unknown or already decided. */
    public boolean resolve(String id, Decision decision) {
        CompletableFuture<Decision> future = pending.get(id);
        Request request = requests.get(id);
        if (future == null || request == null) {
            return false;
        }
        // "Allow for session" is only honoured where it was offered.
        Decision effective = decision == Decision.ALLOW_SESSION && !request.sessionOption()
                ? Decision.ALLOW_ONCE : decision;
        return future.complete(effective);
    }

    public void denyAll() {
        pending.values().forEach(f -> f.complete(Decision.DENY));
    }
}
