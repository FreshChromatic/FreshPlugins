package github.freshchromatic.freshlib.config.configurate.cache;

import github.freshchromatic.freshlib.util.Logging;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Configuration cache with automatic file watching and hot-reload capability.
 *
 * <p>Features:
 * <ul>
 *   <li>Automatic file change detection via WatchService</li>
 *   <li>Configurable debounce interval to avoid excessive reloads</li>
 *   <li>Thread-safe configuration access</li>
 *   <li>Event notification on configuration changes</li>
 * </ul></p>
 *
 * <p>Based on DiscordSRV's reload mechanism with enhanced caching.</p>
 */
public class ConfigCache<T> {

    private final Path configPath;
    private volatile T cachedConfig;
    private final Consumer<T> loader;
    private final Consumer<T> saver;
    private final long debounceMs;
    private ScheduledExecutorService watchService;
    private volatile long lastModified;
    private final Map<Object, Consumer<T>> changeListeners = new ConcurrentHashMap<>();

    public ConfigCache(Path configPath, Consumer<T> loader, Consumer<T> saver) {
        this(configPath, loader, saver, 1000);
    }

    public ConfigCache(Path configPath, Consumer<T> loader, Consumer<T> saver, long debounceMs) {
        this.configPath = configPath;
        this.loader = loader;
        this.saver = saver;
        this.debounceMs = debounceMs;
        updateLastModified();
    }

    /**
     * Gets the cached configuration, loading if necessary.
     */
    public T get() {
        if (cachedConfig == null) {
            synchronized (this) {
                if (cachedConfig == null) {
                    load();
                }
            }
        }
        return cachedConfig;
    }

    /**
     * Sets the cached configuration and optionally saves to disk.
     */
    public void set(T config) {
        this.cachedConfig = config;
        updateLastModified();
        notifyListeners(config);
    }

    /**
     * Forces a reload from disk.
     */
    public synchronized void load() {
        try {
            loader.accept(null);
            Logging.logger().info("Configuration reloaded: " + configPath.getFileName());
        } catch (Exception e) {
            Logging.logger().severe("Failed to reload configuration: " + e.getMessage());
        }
    }

    /**
     * Saves the current configuration to disk.
     */
    public synchronized void save() {
        if (cachedConfig != null && saver != null) {
            try {
                saver.accept(cachedConfig);
                updateLastModified();
            } catch (Exception e) {
                Logging.logger().severe("Failed to save configuration: " + e.getMessage());
            }
        }
    }

    /**
     * Starts watching the configuration file for changes.
     */
    public void startWatching() {
        if (watchService != null) return;

        watchService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ConfigWatcher-" + configPath.getFileName());
            t.setDaemon(true);
            return t;
        });

        watchService.scheduleAtFixedRate(this::checkForChanges, debounceMs, debounceMs, TimeUnit.MILLISECONDS);
        Logging.logger().info("Started watching configuration file: " + configPath.getFileName());
    }

    /**
     * Stops watching the configuration file.
     */
    public void stopWatching() {
        if (watchService != null) {
            watchService.shutdown();
            try {
                if (!watchService.awaitTermination(5, TimeUnit.SECONDS)) {
                    watchService.shutdownNow();
                }
            } catch (InterruptedException e) {
                watchService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            watchService = null;
            Logging.logger().info("Stopped watching configuration file: " + configPath.getFileName());
        }
    }

    /**
     * Adds a listener that will be called when the configuration changes.
     */
    public void addChangeListener(Object key, Consumer<T> listener) {
        changeListeners.put(key, listener);
    }

    /**
     * Removes a previously registered change listener.
     */
    public void removeChangeListener(Object key) {
        changeListeners.remove(key);
    }

    /**
     * Clears all change listeners.
     */
    public void clearChangeListeners() {
        changeListeners.clear();
    }

    /**
     * Invalidates the cache, forcing a reload on next access.
     */
    public void invalidate() {
        cachedConfig = null;
    }

    /**
     * Checks if the file has been modified since last check.
     */
    private void checkForChanges() {
        try {
            if (!Files.exists(configPath)) return;

            long currentModified = Files.getLastModifiedTime(configPath).toMillis();
            if (currentModified > lastModified) {
                Logging.logger().info("Detected configuration change in: " + configPath.getFileName());
                load();
            }
        } catch (IOException e) {
            Logging.logger().warning("Error checking configuration file for changes: " + e.getMessage());
        }
    }

    private void updateLastModified() {
        try {
            if (Files.exists(configPath)) {
                lastModified = Files.getLastModifiedTime(configPath).toMillis();
            } else {
                lastModified = System.currentTimeMillis();
            }
        } catch (IOException e) {
            lastModified = System.currentTimeMillis();
        }
    }

    @SuppressWarnings("unchecked")
    private void notifyListeners(T config) {
        for (Consumer<T> listener : changeListeners.values()) {
            try {
                listener.accept(config);
            } catch (Exception e) {
                Logging.logger().warning("Error in configuration change listener: " + e.getMessage());
            }
        }
    }

    public Path getConfigPath() {
        return configPath;
    }

    public boolean isWatching() {
        return watchService != null && !watchService.isShutdown();
    }
}
