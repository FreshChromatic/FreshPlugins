package github.freshchromatic.chunkrevive.infrastructure.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.freshlib.scheduler.ScheduledTask;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** Polls Modrinth's public API; Modrinth does not push release webhooks to plugin servers. */
public final class ModrinthUpdateChecker implements Listener {
    private static final String PROJECT_ID = "chunkrevive";
    private static final long CHECK_INTERVAL_TICKS = 12L * 60L * 60L * 20L;
    private static final URI API_BASE = URI.create("https://api.modrinth.com/v2/project/");
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final ChunkRevivePlugin plugin;
    private final Supplier<Messages> messages;
    private ScheduledTask task;
    private volatile String announcedVersion;
    private volatile RemoteVersion announced;

    public ModrinthUpdateChecker(ChunkRevivePlugin plugin, Supplier<Messages> messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public void start(PluginConfig.Updates config) {
        stop();
        if (!config.enabled) return;

        task = Scheduler.runTaskTimerAsynchronously(plugin, this::check, 20L * 30L, CHECK_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void check() {
        try {
            Optional<RemoteVersion> latest = fetchLatest();
            if (latest.isEmpty()) return;

            String installed = plugin.getPluginMeta().getVersion();
            RemoteVersion remote = latest.get();
            if (compareVersions(remote.number(), installed) <= 0 || remote.number().equals(announcedVersion)) return;

            announcedVersion = remote.number();
            announced = remote;
            String message = "ChunkRevive " + remote.number() + " is available on Modrinth (installed: "
                + installed + "): " + remote.url();
            Logging.logger().info(message);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Logging.logger().warning("Could not check Modrinth for ChunkRevive updates: " + e.getMessage());
        } catch (RuntimeException e) {
            Logging.logger().warning("Could not parse the Modrinth update response: " + e.getMessage());
        }
    }

    private Optional<RemoteVersion> fetchLatest() throws IOException, InterruptedException {
        String project = URLEncoder.encode(PROJECT_ID, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(API_BASE.resolve(project + "/version"))
            .header("User-Agent", "ChunkRevive/" + plugin.getPluginMeta().getVersion())
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Modrinth returned HTTP " + response.statusCode());
        }

        String minecraftVersion = Bukkit.getMinecraftVersion();
        JsonArray versions = JsonParser.parseString(response.body()).getAsJsonArray();
        return versions.asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(version -> "listed".equals(string(version, "status")))
            .filter(version -> "release".equals(string(version, "version_type")))
            .filter(version -> supports(version, minecraftVersion))
            .map(version -> new RemoteVersion(
                string(version, "version_number"),
                Instant.parse(string(version, "date_published")),
                "https://modrinth.com/plugin/" + PROJECT_ID + "/version/" + string(version, "id")))
            .filter(version -> !version.number().isBlank())
            .max(Comparator.comparing(RemoteVersion::published));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        RemoteVersion remote = latestAvailable();
        if (!plugin.getPluginConfig().updates.enabled || remote == null
            || !player.hasPermission("chunkrevive.admin")) return;

        player.sendMessage(messages.get().update.available.withPlaceholders(
            Components.placeholder("latest_version", remote.number()),
            Components.placeholder("current_version", plugin.getPluginMeta().getVersion()),
            Components.placeholder("url", remote.url())));
    }

    private RemoteVersion latestAvailable() {
        return announced;
    }

    private static boolean supports(JsonObject version, String minecraftVersion) {
        JsonElement gameVersions = version.get("game_versions");
        return gameVersions != null && gameVersions.isJsonArray()
            && gameVersions.getAsJsonArray().asList().stream().anyMatch(element -> minecraftVersion.equals(element.getAsString()));
    }

    private static String string(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    // Natural comparison keeps common plugin versions such as 26-Release.2 and 1.10 above 1.9.
    static int compareVersions(String left, String right) {
        String[] leftParts = left.toLowerCase(Locale.ROOT).split("[._+\\-]");
        String[] rightParts = right.toLowerCase(Locale.ROOT).split("[._+\\-]");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            String a = i < leftParts.length ? leftParts[i] : "0";
            String b = i < rightParts.length ? rightParts[i] : "0";
            int result = comparePart(a, b);
            if (result != 0) return result;
        }
        return 0;
    }

    private static int comparePart(String left, String right) {
        boolean leftNumber = left.chars().allMatch(Character::isDigit);
        boolean rightNumber = right.chars().allMatch(Character::isDigit);
        if (leftNumber && rightNumber) {
            return new java.math.BigInteger(left).compareTo(new java.math.BigInteger(right));
        }
        return left.compareTo(right);
    }

    private record RemoteVersion(String number, Instant published, String url) {}
}
