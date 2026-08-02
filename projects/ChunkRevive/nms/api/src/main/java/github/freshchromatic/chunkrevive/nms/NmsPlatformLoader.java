package github.freshchromatic.chunkrevive.nms;

import org.bukkit.Bukkit;

/** Selects a version adapter without loading implementation classes from unsupported adapters. */
public final class NmsPlatformLoader {
    private static volatile NmsPlatform loaded;

    private NmsPlatformLoader() {}

    public static NmsPlatform load() {
        NmsPlatform current = loaded;
        if (current != null) return current;
        synchronized (NmsPlatformLoader.class) {
            if (loaded == null) loaded = discover();
            return loaded;
        }
    }

    private static NmsPlatform discover() {
        String minecraftVersion = Bukkit.getMinecraftVersion();
        AdapterDescriptor adapter = switch (minecraftVersion) {
            case "1.21.11" -> new AdapterDescriptor(
                "github.freshchromatic.chunkrevive.nms.v1_21_11.V1_21_11NmsPlatformProvider", 21);
            case "26.1.2" -> new AdapterDescriptor(
                "github.freshchromatic.chunkrevive.nms.v26_1_2.V26_1_2NmsPlatformProvider", 25);
            case "26.2" -> new AdapterDescriptor(
                "github.freshchromatic.chunkrevive.nms.v26_2.V26_2NmsPlatformProvider", 25);
            default -> throw new IllegalStateException(
                "ChunkRevive does not support Minecraft " + minecraftVersion);
        };

        int runtimeFeature = Runtime.version().feature();
        if (runtimeFeature < adapter.minimumJavaFeature()) {
            throw new IllegalStateException(
                "Minecraft " + minecraftVersion + " requires Java "
                    + adapter.minimumJavaFeature() + "; the server is running Java " + runtimeFeature);
        }

        try {
            Class<?> providerClass = Class.forName(adapter.providerClassName(), true,
                NmsPlatformLoader.class.getClassLoader());
            NmsPlatformProvider provider = providerClass.asSubclass(NmsPlatformProvider.class)
                .getDeclaredConstructor()
                .newInstance();
            return provider.create();
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException(
                "Could not load the ChunkRevive NMS adapter for Minecraft " + minecraftVersion,
                exception);
        }
    }

    private record AdapterDescriptor(String providerClassName, int minimumJavaFeature) {}
}
