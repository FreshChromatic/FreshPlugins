package github.freshchromatic.freshlib.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/*
    From: https://gist.github.com/Jofkos/d0c469528b032d820f42
 */

public class UUIDFetcher {

    /**
     * Date when name changes were introduced
     *
     * @see UUIDFetcher#getUUIDAt(String, long)
     */
    public static final long FEBRUARY_2015 = 1422748800000L;
    // Utilizes FreshLib's built-in static Logging library
    private static final String                   UUID_URL            = "https://api.minecraftservices.com/users/profiles/minecraft/%s?at=%d";
    private static final String                   NAME_URL            = "https://api.minecraftservices.com/user/profile/%s";
    private static final Gson                     gson                = new GsonBuilder().registerTypeAdapter(
        UUID.class, new UUIDTypeAdapter()).create();
    private static final Map<String, UUID>        currentUuidCache    = new ConcurrentHashMap<>();
    private static final Map<UuidLookupKey, UUID> historicalUuidCache = new ConcurrentHashMap<>();
    private static final Map<UUID, String>        nameCache           = new ConcurrentHashMap<>();
    private static final ExecutorService          pool                = Executors.newCachedThreadPool();

    private String name;
    private UUID   id;

    /**
     * Fetches the uuid asynchronously and passes it to the consumer
     *
     * @param name   The name
     * @param action Do what you want to do with the uuid her
     */
    public static void getUUID(String name, Consumer<UUID> action) {
        pool.execute(() -> action.accept(getUUID(name)));
    }

    /**
     * Fetches the uuid synchronously and returns it
     *
     * @param name The name
     * @return The uuid
     */
    public static UUID getUUID(String name) {
        String normalizedName = normalizeName(name);
        if (normalizedName == null) {
            return null;
        }
        UUID cachedUuid = currentUuidCache.get(normalizedName);
        if (cachedUuid != null) {
            return cachedUuid;
        }
        UUID uuid = getUUIDAt(normalizedName, System.currentTimeMillis());
        if (uuid != null) {
            currentUuidCache.put(normalizedName, uuid);
        }
        return uuid;
    }

    /**
     * Fetches the uuid synchronously for a specified name and time and passes the result to the consumer
     *
     * @param name      The name
     * @param timestamp Time when the player had this name in milliseconds
     * @param action    Do what you want to do with the uuid her
     */
    public static void getUUIDAt(String name, long timestamp, Consumer<UUID> action) {
        pool.execute(() -> action.accept(getUUIDAt(name, timestamp)));
    }

    /**
     * Fetches the uuid synchronously for a specified name and time
     *
     * @param name      The name
     * @param timestamp Time when the player had this name in milliseconds
     * @see UUIDFetcher#FEBRUARY_2015
     */
    public static UUID getUUIDAt(String name, long timestamp) {
        String normalizedName = normalizeName(name);
        if (normalizedName == null) {
            return null;
        }
        UuidLookupKey cacheKey = lookupKey(normalizedName, timestamp);
        long timestampSeconds = cacheKey.timestampSeconds();
        UUID cachedUuid = historicalUuidCache.get(cacheKey);
        if (cachedUuid != null) {
            return cachedUuid;
        }
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(String.format(Locale.ROOT, UUID_URL,
                normalizedName, timestampSeconds)).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            UUIDFetcher data;
            try (var reader = new BufferedReader(new InputStreamReader(connection.getInputStream(),
                StandardCharsets.UTF_8))) {
                data = gson.fromJson(reader, UUIDFetcher.class);
            } finally {
                connection.disconnect();
            }

            historicalUuidCache.put(cacheKey, data.id);

            return data.id;
        } catch (Exception e) {
            Logging.logger().severe("Could not fetch UUID for name: " + normalizedName, e);
            return null;
        }
    }

    private static String normalizeName(String name) {
        if (name == null || name.isEmpty() || name.length() > 16) {
            return null;
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (!(character >= 'a' && character <= 'z') && !(character >= 'A' && character <= 'Z') && !(character >= '0'
                && character <= '9') && character != '_') {
                return null;
            }
        }
        return name.toLowerCase(Locale.ROOT);
    }

    static UuidLookupKey lookupKey(String normalizedName, long timestamp) {
        return new UuidLookupKey(normalizedName, timestamp / 1000);
    }

    /**
     * Fetches the name asynchronously and passes it to the consumer
     *
     * @param uuid   The uuid
     * @param action Do what you want to do with the name her
     */
    public static void getName(UUID uuid, Consumer<String> action) {
        pool.execute(() -> action.accept(getName(uuid)));
    }

    /**
     * Fetches the name synchronously and returns it
     *
     * @param uuid The uuid
     * @return The name
     */
    public static String getName(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        if (nameCache.containsKey(uuid)) {
            return nameCache.get(uuid);
        }
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(String.format(Locale.ROOT, NAME_URL,
                UUIDTypeAdapter.fromUUID(uuid))).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            UUIDFetcher currentNameData;
            try (var reader = new BufferedReader(new InputStreamReader(connection.getInputStream(),
                StandardCharsets.UTF_8))) {
                currentNameData = gson.fromJson(reader, UUIDFetcher.class);
            } finally {
                connection.disconnect();
            }

            currentUuidCache.put(currentNameData.name.toLowerCase(Locale.ROOT), uuid);
            nameCache.put(uuid, currentNameData.name);

            return currentNameData.name;
        } catch (Exception e) {
            Logging.logger().severe("Could not fetch name for uuid: " + uuid, e);
            return null;
        }
    }

}

record UuidLookupKey(String name, long timestampSeconds) {}

class UUIDTypeAdapter extends TypeAdapter<UUID> {

    public static String fromUUID(UUID value) {
        return value.toString().replace("-", "");
    }

    public static UUID fromString(String input) {
        return UUID.fromString(input.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
    }

    public void write(JsonWriter out, UUID value) throws IOException {
        out.value(fromUUID(value));
    }

    public UUID read(JsonReader in) throws IOException {
        return fromString(in.nextString());
    }
}
