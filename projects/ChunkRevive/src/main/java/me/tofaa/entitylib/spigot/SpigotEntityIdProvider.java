package me.tofaa.entitylib.spigot;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import me.tofaa.entitylib.EntityIdProvider;
import me.tofaa.entitylib.Platform;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class SpigotEntityIdProvider implements EntityIdProvider {

    private final Platform<JavaPlugin> platform;
    private final Supplier<Integer> entityIdSupplier;

    public SpigotEntityIdProvider(final @NotNull Platform<JavaPlugin> platform) {
        this.platform = platform;
        this.entityIdSupplier = detectIdSupplier();
    }

    @Override
    public int provide(@NotNull UUID entityUUID, @NotNull EntityType entityType) {
        return entityIdSupplier.get();
    }

    private Supplier<Integer> detectIdSupplier() {
        final ServerVersion serverVersion = platform.getAPI().getPacketEvents().getServerManager().getVersion();

        if (isPaper() && serverVersion.isNewerThanOrEquals(ServerVersion.V_1_16)) {
            Supplier<Integer> paperSupplier = resolvePaperSupplier();
            if (paperSupplier != null) {
                return paperSupplier;
            }
        }

        final Class<?> entityClass = getEntityClass();
        if (serverVersion.isNewerThanOrEquals(ServerVersion.V_1_14)) {
            final Supplier<Integer> modernSupplier = resolveAtomicSupplier(entityClass);
            if (modernSupplier != null) {
                return modernSupplier;
            }
        }

        return resolveLegacySupplier(entityClass);
    }

    private Supplier<Integer> resolvePaperSupplier() {
        Object unsafe = Bukkit.getUnsafe();
        try {
            Method nextEntityId = unsafe.getClass().getMethod("nextEntityId", World.class);
            return () -> {
                try {
                    return (Integer) nextEntityId.invoke(unsafe, entityIdWorld());
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Failed to call UnsafeValues#nextEntityId(World)", exception);
                }
            };
        } catch (NoSuchMethodException ignored) {
            // Older Paper exposed a no-arg method; keep supporting it without linking to it.
        }

        try {
            Method nextEntityId = unsafe.getClass().getMethod("nextEntityId");
            return () -> {
                try {
                    return (Integer) nextEntityId.invoke(unsafe);
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Failed to call UnsafeValues#nextEntityId()", exception);
                }
            };
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private World entityIdWorld() {
        World world = platform.getHandle().getServer().getWorlds().stream().findFirst().orElse(null);
        if (world == null) {
            throw new IllegalStateException("Cannot allocate an entity id before any Bukkit worlds are loaded");
        }
        return world;
    }

    private Supplier<Integer> resolveAtomicSupplier(final Class<?> entityClass) {
        final Field entityAtomicField = getStaticFieldOfType(entityClass, AtomicInteger.class,
                "entityCount", "d", "c", "counter", "nextEntityId");
        if (entityAtomicField == null) {
            return null;
        }
        try {
            entityAtomicField.setAccessible(true);
            final Object fieldValue = entityAtomicField.get(null);
            if (!(fieldValue instanceof AtomicInteger)) {
                return null;
            }
            final AtomicInteger counter = (AtomicInteger) fieldValue;
            return counter::incrementAndGet;
        } catch (final IllegalAccessException exception) {
            throw new IllegalStateException("Failed to access entity counter", exception);
        }
    }

    private Supplier<Integer> resolveLegacySupplier(final Class<?> entityClass) {
        final Field entityLegacyField = getStaticFieldOfType(entityClass, Integer.TYPE, "entityCount", "b");
        if (entityLegacyField == null) {
            throw new IllegalStateException("Could not find legacy entity counter field");
        }
        entityLegacyField.setAccessible(true);
        return () -> {
            try {
                final int entityId = entityLegacyField.getInt(null);
                entityLegacyField.setInt(null, entityId + 1);
                return entityId;
            } catch (final IllegalAccessException exception) {
                throw new IllegalStateException("Failed to modify entity counter", exception);
            }
        };
    }

    private Class<?> getEntityClass() {
        final ServerVersion serverVersion = platform.getAPI().getPacketEvents().getServerManager().getVersion();
        final boolean isFlattened = serverVersion.isNewerThanOrEquals(ServerVersion.V_1_17);

        final String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        final String packagePath = isFlattened ? "net.minecraft.world.entity" : "net.minecraft.server." + version;

        try {
            return Class.forName(packagePath + ".Entity");
        } catch (final ClassNotFoundException exception) {
            throw new IllegalStateException("Could not find Entity class", exception);
        }
    }

    private static Field getField(final Class<?> clazz, final String... possibleNames) {
        for (final String name : possibleNames) {
            try {
                return clazz.getDeclaredField(name);
            } catch (final NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Field getStaticFieldOfType(final Class<?> clazz, final Class<?> desiredType,
                                              final String... possibleNames) {
        for (final String name : possibleNames) {
            final Field field = getField(clazz, name);
            if (field != null && desiredType.isAssignableFrom(field.getType())
                    && Modifier.isStatic(field.getModifiers())) {
                return field;
            }
        }

        for (final Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && desiredType.isAssignableFrom(field.getType())) {
                return field;
            }
        }
        return null;
    }

    private static boolean isPaper() {
        return Stream.of(
                "com.destroystokyo.paper.PaperConfig",
                "io.papermc.paper.configuration.Configuration"
        ).anyMatch(SpigotEntityIdProvider::hasClass);
    }

    private static boolean hasClass(final String className) {
        try {
            Class.forName(className);
            return true;
        } catch (final ClassNotFoundException ignored) {
            return false;
        }
    }
}
