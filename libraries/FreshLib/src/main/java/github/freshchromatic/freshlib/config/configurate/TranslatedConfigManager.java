package github.freshchromatic.freshlib.config.configurate;

import github.freshchromatic.freshlib.config.Config;
import github.freshchromatic.freshlib.util.Logging;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class TranslatedConfigManager<T extends Config> extends AbstractConfigManager<T> {

    private final String resourcePath;

    public TranslatedConfigManager(Path path, Class<T> configClass, String resourcePath) {
        super(path, configClass);
        this.resourcePath = resourcePath;
    }

    @Override
    public void load() {
        if (resourcePath != null) {
            extractDefaultIfNeeded();
        }
        super.load();
    }

    private void extractDefaultIfNeeded() {
        if (Files.exists(path)) {
            return;
        }

        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                // If resource is missing, we just rely on the POJO defaults in super.load()
                return;
            }

            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Logging.logger().severe("Failed to extract default translation: " + e.getMessage());
        }
    }
}
