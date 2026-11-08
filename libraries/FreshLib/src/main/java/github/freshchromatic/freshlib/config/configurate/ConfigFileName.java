package github.freshchromatic.freshlib.config.configurate;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

public record ConfigFileName(String value) {

    public ConfigFileName {
        if (!isValid(value)) {
            throw new IllegalArgumentException("Configuration file name must be a single file name");
        }
    }

    public static Optional<ConfigFileName> parse(String value) {
        try {
            return Optional.of(new ConfigFileName(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static boolean isValid(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.equals(".") || value.equals(
            "..")) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || "<>:\"/\\|?*".indexOf(character) >= 0) {
                return false;
            }
        }
        try {
            Path path = Path.of(value);
            return !path.isAbsolute() && path.getNameCount() == 1 && path.getFileName().toString().equals(value);
        } catch (InvalidPathException exception) {
            return false;
        }
    }
}
