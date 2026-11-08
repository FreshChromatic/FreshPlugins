package github.freshchromatic.freshlib.util;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileUtilsTest {

    @Test
    void readsTheEntireResource() {
        var fileUtils = new FileUtils();

        assertEquals("first line\nsecond line\n", fileUtils.readResource("fileutils/source.txt"));
        assertNull(fileUtils.readResource(null));
    }

    @Test
    void savesResourcesWithoutOverwritingExistingFiles(@TempDir Path directory) throws Exception {
        var plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        var target = directory.resolve("fileutils/source.txt");

        new FileUtils().saveFile(plugin, "fileutils/source.txt");

        assertEquals("first line\nsecond line\n", Files.readString(target));
        Files.writeString(target, "user-edited\n");
        new FileUtils().saveFile(plugin, "fileutils/source.txt");
        assertEquals("user-edited\n", Files.readString(target));
    }

    @Test
    void rejectsResourceDestinationsOutsideThePluginDirectory(@TempDir Path directory) {
        var plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());

        assertThrows(IllegalArgumentException.class, () -> new FileUtils().saveFile(plugin, "../outside.txt"));
    }

    @Test
    void rejectsDestinationsThatEscapeThroughSymbolicParent(@TempDir Path directory) throws Exception {
        Path dataDirectory = Files.createDirectory(directory.resolve("plugin"));
        Path outsideDirectory = Files.createDirectory(directory.resolve("outside"));
        Path linkedParent = dataDirectory.resolve("fileutils");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink /J \"" + linkedParent + "\" \""
                + outsideDirectory + "\"").redirectErrorStream(true).start();
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
            }
            assumeTrue(completed, "Could not create directory junction before timeout");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } else {
            Files.createSymbolicLink(linkedParent, outsideDirectory);
        }
        var plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(dataDirectory.toFile());

        assertThrows(IllegalArgumentException.class, () -> new FileUtils().saveFile(plugin, "fileutils/source.txt"));

        assertFalse(Files.exists(outsideDirectory.resolve("source.txt")));
    }

    @Test
    void filenameSearchDoesNotDependOnTheDefaultLocale(@TempDir Path directory) throws Exception {
        Path file = Files.createFile(directory.resolve("FILE.txt"));
        Locale previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertEquals(file.toFile(), FileUtils.findFirstFileByName(directory.toFile(), "file"));
        } finally {
            Locale.setDefault(previousLocale);
        }
    }
}
