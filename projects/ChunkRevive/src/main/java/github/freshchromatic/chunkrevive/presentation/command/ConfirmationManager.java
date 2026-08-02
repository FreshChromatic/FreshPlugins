package github.freshchromatic.chunkrevive.presentation.command;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared two-step confirmation token store for {@code /cr fullmark}, {@code /cr resetmark} and
 * {@code /cr regenallmark}. A pending confirmation is keyed by sender identity + a command-specific
 * key string (e.g. {@code "fullmark:world"}); it must be confirmed within the configured timeout.
 */
public final class ConfirmationManager {

    private record Pending(String key, long expiresAt, Object payload) {}

    private final Map<Object, Pending> pending = new ConcurrentHashMap<>();

    /** Registers a new pending confirmation, replacing any previous one for the same sender. */
    public void request(Object senderId, String key, int timeoutSeconds) {
        request(senderId, key, timeoutSeconds, null);
    }

    /** Registers a confirmation together with the immutable preview/result it applies to. */
    public void request(Object senderId, String key, int timeoutSeconds, Object payload) {
        pending.put(senderId, new Pending(
            key, System.currentTimeMillis() + timeoutSeconds * 1000L, payload));
    }

    /**
     * Returns the matching confirmation payload without consuming the token. This lets a confirmed
     * command execute the exact previewed selection instead of performing an expensive scan again.
     */
    public Optional<Object> peekPayload(Object senderId, String key) {
        Pending p = validPending(senderId, key);
        return p == null ? Optional.empty() : Optional.ofNullable(p.payload());
    }

    /**
     * Attempts to consume a pending confirmation for the given sender + key.
     * Returns false (without side effects beyond cleanup) if there is no matching, non-expired request.
     */
    public boolean confirm(Object senderId, String key) {
        Pending p = validPending(senderId, key);
        if (p == null) return false;
        pending.remove(senderId);
        return true;
    }

    private Pending validPending(Object senderId, String key) {
        Pending p = pending.get(senderId);
        if (p == null) return null;
        if (System.currentTimeMillis() > p.expiresAt() || !p.key().equals(key)) {
            pending.remove(senderId, p);
            return null;
        }
        return p;
    }
}
