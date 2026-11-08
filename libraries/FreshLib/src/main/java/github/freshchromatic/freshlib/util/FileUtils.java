package github.freshchromatic.freshlib.util;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public class FileUtils {

    public static String getSHA256Checksum(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] byteArray = new byte[1024];
                int bytesCount;

                // Read the file data and update the MessageDigest
                while ((bytesCount = fis.read(byteArray)) != -1) {
                    digest.update(byteArray, 0, bytesCount);
                }
            }

            // Get the final hash bytes
            byte[] hashBytes = digest.digest();

            // Convert hash bytes to hex format
            StringBuilder hexString = new StringBuilder();
            for (byte hashByte : hashBytes) {
                String hex = Integer.toHexString(0xff & hashByte);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return "N/A";
        }
    }

    public static File findFirstFileByName(File directory, String name) {
        File[] files = directory.listFiles();
        if (files == null) {
            return null;
        }
        String normalizedName = name.toLowerCase(Locale.ROOT);
        for (File file : files) {
            if (file.getName().toLowerCase(Locale.ROOT).contains(normalizedName)) {
                return file;
            }
        }
        return null;
    }

    public String readResource(String name) {
        if (name == null) {
            return null;
        }
        URL url = getClass().getClassLoader().getResource(name);
        if (url == null) {
            return null;
        }
        try {
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            try (InputStream inputStream = connection.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            Logging.logger().severe("Could not read bundled resource " + name + ": " + e.getMessage(), e);
            return null;
        }
    }

    public void saveFile(Plugin plugin, String name) {
        Path dataDirectory = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path target = dataDirectory.resolve(name).normalize();
        if (target.equals(dataDirectory) || !target.startsWith(dataDirectory)) {
            throw new IllegalArgumentException("Resource destination must be inside the plugin data directory");
        }

        URL url = getClass().getClassLoader().getResource(name);
        if (url == null) {
            return;
        }
        if (Files.exists(target)) {
            return;
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                createSafeParentDirectories(dataDirectory, parent);
            }
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            try (InputStream inputStream = connection.getInputStream()) {
                Files.copy(inputStream, target);
            }
        } catch (IOException e) {
            Logging.logger().severe("Could not copy bundled resource " + name + ": " + e.getMessage(), e);
        }
    }

    private static void createSafeParentDirectories(Path dataDirectory, Path parent) throws IOException {
        Files.createDirectories(dataDirectory);
        Path realDataDirectory = dataDirectory.toRealPath();
        Path current = dataDirectory;
        for (Path segment : dataDirectory.relativize(parent)) {
            current = current.resolve(segment);
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException ignored) {
            }
            if (!current.toRealPath().startsWith(realDataDirectory)) {
                throw new IllegalArgumentException("Resource destination must be inside the plugin data directory");
            }
        }
    }

}
