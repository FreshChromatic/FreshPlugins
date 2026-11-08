package github.freshchromatic.freshlib.config.configurate;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class YamlCommentWriter {

    private static final Pattern KEY_LINE = Pattern.compile("^( *)([a-zA-Z0-9_-]+):(.*)$");

    private final Path path;

    YamlCommentWriter(Path path) {
        this.path = path;
    }

    void save(CommentedConfigurationNode node) throws ConfigurateException {
        StringWriter writer = new StringWriter();
        YamlConfigurationLoader tempLoader = YamlConfigurationLoader.builder()
            .sink(() -> new BufferedWriter(
            writer))
            .nodeStyle(NodeStyle.BLOCK)
            .defaultOptions(node.options())
            .build();
        tempLoader.save(node);

        Map<String, String> comments = new LinkedHashMap<>();
        collectComments("", node, comments);

        String yaml = writer.toString();
        String output = comments.isEmpty() ? yaml : insertComments(yaml, comments);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, output, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ConfigurateException(exception);
        }
    }

    private static void collectComments(String prefix, CommentedConfigurationNode node, Map<String, String> comments) {
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
            ConfigurationNode child = entry.getValue();
            String key = entry.getKey().toString();
            String path = prefix.isEmpty() ? key : prefix + "." + key;

            if (child instanceof CommentedConfigurationNode) {
                String comment = ((CommentedConfigurationNode) child).comment();
                if (comment != null && !comment.isEmpty()) {
                    comments.put(path, comment);
                }
                collectComments(path, (CommentedConfigurationNode) child, comments);
            }
        }
    }

    private static String insertComments(String yaml, Map<String, String> comments) {
        StringBuilder result = new StringBuilder();
        String[] lines = yaml.split("\n", -1);
        List<String> keyStack = new ArrayList<>();
        List<Integer> indentStack = new ArrayList<>();

        for (String line : lines) {
            String normalized = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            Matcher matcher = KEY_LINE.matcher(normalized);
            if (matcher.matches()) {
                int indent = matcher.group(1).length();
                while (!indentStack.isEmpty() && indentStack.get(indentStack.size() - 1) >= indent) {
                    indentStack.remove(indentStack.size() - 1);
                    keyStack.remove(keyStack.size() - 1);
                }
                keyStack.add(matcher.group(2));
                indentStack.add(indent);

                String comment = comments.get(String.join(".", keyStack));
                if (comment != null) {
                    String indentation = matcher.group(1);
                    for (String commentLine : comment.split("\n", -1)) {
                        result.append(indentation).append("# ").append(commentLine).append("\n");
                    }
                }
            }
            result.append(line).append("\n");
        }

        String output = result.toString();
        if (output.endsWith("\n\n") && !yaml.endsWith("\n\n")) {
            output = output.substring(0, output.length() - 1);
        }
        return output;
    }
}
