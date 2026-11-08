package github.freshchromatic.freshlib.config.configurate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlCommentWriterTest {

    @Test
    void writesNestedCommentsAtTheirConfigurationLevel(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("nested/config.yml");
        var node = YamlConfigurationLoader.builder().build().createNode();
        node.node("section").comment("Section comment");
        node.node("section", "value").comment("Value comment\nSecond line");
        node.node("section", "value").set("saved");

        new YamlCommentWriter(path).save(node);

        String output = Files.readString(path);
        assertTrue(output.contains("# Section comment\nsection:"));
        assertTrue(output.contains("    # Value comment\n    # Second line\n    value: saved"));
    }

    @Test
    void writesYamlWithoutComments(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        var node = YamlConfigurationLoader.builder().build().createNode();
        node.node("value").set("saved");

        new YamlCommentWriter(path).save(node);

        assertTrue(Files.readString(path).contains("value: saved"));
    }
}
