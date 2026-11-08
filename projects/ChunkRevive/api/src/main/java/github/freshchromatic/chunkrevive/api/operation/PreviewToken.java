package github.freshchromatic.chunkrevive.api.operation;

public record PreviewToken(String value) {

    public PreviewToken {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }
}
