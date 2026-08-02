package github.freshchromatic.freshlib.util;

import org.slf4j.Logger;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.StackWalker.Option;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Logging {
    private static final StackWalker WALKER = StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE);
    private static final Map<Plugin, LoggerProxy> PROXIES = new ConcurrentHashMap<>();

    private Logging() {
    }

    /**
     * No-op kept for source compatibility. Each caller's logger is now resolved
     * per-plugin from {@link #logger()} via the caller's classloader, since FreshLib
     * is shared across every consuming plugin and a single static logger field would
     * get overwritten by whichever plugin called init() last.
     */
    @Deprecated
    public static void init(final JavaPlugin plugin) {
    }

    @Deprecated
    public static void init(final Logger loggerInstance) {
    }

    public static LoggerProxy logger() {
        Class<?> caller = WALKER.getCallerClass();
        Plugin plugin = JavaPlugin.getProvidingPlugin(caller);
        return PROXIES.computeIfAbsent(plugin, p -> new LoggerProxy(((JavaPlugin) p).getSLF4JLogger()));
    }

    public static final class LoggerProxy {
        private final Logger slf4j;

        public LoggerProxy(Logger slf4j) {
            this.slf4j = slf4j;
        }

        public void info(String msg) {
            if (slf4j != null) slf4j.info(msg);
        }

        public void warning(String msg) {
            if (slf4j != null) slf4j.warn(msg);
        }

        public void severe(String msg) {
            if (slf4j != null) slf4j.error(msg);
        }

        public void severe(String msg, Throwable t) {
            if (slf4j != null) slf4j.error(msg, t);
        }
    }

    public static String replace(final String message, final Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("keyValuePairs must be even");
        }
        String result = message;
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            result = result.replace("<" + keyValuePairs[i] + ">", String.valueOf(keyValuePairs[i + 1]));
        }
        return result;
    }
}
