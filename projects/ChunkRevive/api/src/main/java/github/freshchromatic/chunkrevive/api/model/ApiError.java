package github.freshchromatic.chunkrevive.api.model;

import java.util.Map;

public record ApiError(String code, String message, Map<String, String> details) {

    public ApiError {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
