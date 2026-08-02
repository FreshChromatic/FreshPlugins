package github.freshchromatic.chunkrevive.api.model;

import java.util.Map;

public record ApiError(String code, String message, Map<String, String> details) {
    public ApiError {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
