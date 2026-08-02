package github.freshchromatic.freshlib.config.configurate.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a configuration validation result containing any errors or warnings found.
 *
 * <p>Based on DiscordSRV's validation error collection approach.</p>
 */
public class ValidationResult {

    private final List<ValidationError> errors;
    private final List<ValidationWarning> warnings;

    public ValidationResult() {
        this.errors = new ArrayList<>();
        this.warnings = new ArrayList<>();
    }

    public void addError(String path, String message) {
        errors.add(new ValidationError(path, message));
    }

    public void addWarning(String path, String message) {
        warnings.add(new ValidationWarning(path, message));
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public List<ValidationError> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public List<ValidationWarning> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ValidationResult{");
        if (!errors.isEmpty()) {
            sb.append("errors=").append(errors.size());
        }
        if (!warnings.isEmpty()) {
            if (sb.length() > "ValidationResult{".length()) sb.append(", ");
            sb.append("warnings=").append(warnings.size());
        }
        sb.append("}");
        return sb.toString();
    }

    public static class ValidationError {
        private final String path;
        private final String message;

        public ValidationError(String path, String message) {
            this.path = path;
            this.message = message;
        }

        public String getPath() { return path; }
        public String getMessage() { return message; }

        @Override
        public String toString() {
            return "ERROR [" + path + "]: " + message;
        }
    }

    public static class ValidationWarning {
        private final String path;
        private final String message;

        public ValidationWarning(String path, String message) {
            this.path = path;
            this.message = message;
        }

        public String getPath() { return path; }
        public String getMessage() { return message; }

        @Override
        public String toString() {
            return "WARN  [" + path + "]: " + message;
        }
    }
}
