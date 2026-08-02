package github.freshchromatic.freshlib.config.configurate;

import github.freshchromatic.freshlib.config.Config;
import github.freshchromatic.freshlib.config.configurate.annotation.Constants;
import github.freshchromatic.freshlib.config.configurate.annotation.Order;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Automatic configuration documentation generator.
 *
 * <p>Generates comprehensive documentation from @Configurable classes including:
 * <ul>
 *   <li>Field descriptions from @Comment annotations</li>
 *   <li>Type information and default values</li>
 *   <li>Field ordering from @Order annotations</li>
 *   <li>Nested class documentation</li>
 *   <li>Constants reference</li>
 * </ul></p>
 *
 * <p>Output formats supported:
 * <ul>
 *   <li>Markdown (.md)</li>
 *   <li>Plain text</li>
 * </ul></p>
 */
public final class ConfigDocGenerator {

    private ConfigDocGenerator() {
    }

    /**
     * Generates documentation for a configuration class in Markdown format.
     *
     * @param configClass the configuration class to document
     * @param outputPath where to write the documentation
     */
    public static void generateMarkdown(Class<? extends Config> configClass, Path outputPath) {
        StringBuilder md = new StringBuilder();

        String className = configClass.getSimpleName();
        md.append("# ").append(className).append(" Configuration\n\n");
        md.append("## Overview\n\n");
        md.append("Configuration file: `").append(getFileName(configClass)).append("`\n\n");

        Constants constants = configClass.getAnnotation(Constants.class);
        if (constants != null) {
            appendConstantsSection(md, constants);
        }

        md.append("## Configuration Options\n\n");
        generateClassDocumentation(md, configClass, 0);

        try {
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
                writer.write(md.toString());
            }
            System.out.println("Generated documentation: " + outputPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write documentation", e);
        }
    }

    /**
     * Returns the configured file name for a config class.
     */
    public static String getFileName(Class<? extends Config> configClass) {
        try {
            Config instance = configClass.getDeclaredConstructor().newInstance();
            return instance.getFileName();
        } catch (Exception e) {
            return configClass.getSimpleName().replaceAll("Config$", "").toLowerCase() + ".yml";
        }
    }

    private static void generateClassDocumentation(StringBuilder md, Class<?> clazz, int depth) {
        String indent = "  ".repeat(depth);

        List<Field> fields = getOrderedFields(clazz);
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;

            Class<?> fieldType = field.getType();
            Comment comment = field.getDeclaredAnnotation(Comment.class);
            Order order = field.getDeclaredAnnotation(Order.class);

            md.append(indent).append("### `").append(field.getName()).append("`\n\n");
            md.append(indent).append("- **Type**: `").append(fieldType.getSimpleName()).append("`\n");
            md.append(indent).append("- **Default**: `").append(formatDefaultValue(field)).append("`");

            if (order != null) {
                md.append("\n").append(indent).append("- **Order**: ").append(order.value());
            }

            if (comment != null) {
                String[] commentLines = comment.value().split("\n");
                if (commentLines.length > 0) {
                    md.append("\n").append(indent).append("- **Description**:\n");
                    for (String line : commentLines) {
                        md.append(indent).append("  > ").append(line).append("\n");
                    }
                }
            }

            md.append("\n\n");

            if (fieldType.isAnnotationPresent(ConfigSerializable.class)) {
                md.append(indent).append("**Nested Configuration:**\n\n");
                generateClassDocumentation(md, fieldType, depth + 1);
            }
        }

        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && superClass.isAnnotationPresent(ConfigSerializable.class)) {
            md.append(indent).append("**Inherited Fields:**\n\n");
            generateClassDocumentation(md, superClass, depth + 1);
        }
    }

    private static List<Field> getOrderedFields(Class<?> clazz) {
        Field[] fields = clazz.getDeclaredFields();
        List<Field> result = new ArrayList<>();

        for (Field field : fields) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) continue;
            result.add(field);
        }

        result.sort((f1, f2) -> {
            Order o1 = f1.getDeclaredAnnotation(Order.class);
            Order o2 = f2.getDeclaredAnnotation(Order.class);
            int order1 = o1 != null ? o1.value() : Integer.MAX_VALUE;
            int order2 = o2 != null ? o2.value() : Integer.MAX_VALUE;
            return Integer.compare(order1, order2);
        });

        return result;
    }

    private static String formatDefaultValue(Field field) {
        try {
            field.setAccessible(true);
            Object instance;
            try {
                instance = field.getDeclaringClass().getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                return "(requires instance)";
            }

            Object value = field.get(instance);
            if (value == null) return "null";
            if (value instanceof String) return "\"" + value + "\"";
            if (value.getClass().isArray()) {
                return Arrays.toString((Object[]) value);
            }
            if (value instanceof Collection) {
                return ((Collection<?>) value).toString();
            }
            if (value instanceof Map) {
                return ((Map<?, ?>) value).toString();
            }
            return value.toString();
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    private static void appendConstantsSection(StringBuilder md, Constants constants) {
        md.append("## Constants Reference\n\n");
        md.append("| Placeholder | Value |\n");
        md.append("|-------------|-------|\n");

        int index = 1;
        for (String def : constants.value()) {
            int eqIdx = def.indexOf('=');
            if (eqIdx > 0) {
                String key = def.substring(0, eqIdx).trim();
                String value = def.substring(eqIdx + 1).trim();
                md.append("| `%").append(index).append("` | `").append(value).append("` (").append(key).append(") |\n");
            }
            index++;
        }
        md.append("\n");
    }

    /**
     * Generates a YAML template with all comments for a configuration class.
     *
     * @param configClass the configuration class
     * @return YAML string with all defaults and comments
     */
    public static String generateYamlTemplate(Class<? extends Config> configClass) {
        StringBuilder yaml = new StringBuilder();

        Constants constants = configClass.getAnnotation(Constants.class);
        if (constants != null) {
            yaml.append("# Constants:\n");
            int index = 1;
            for (String def : constants.value()) {
                int eqIdx = def.indexOf('=');
                if (eqIdx > 0) {
                    yaml.append("# %").append(index).append(" = ")
                            .append(def.substring(eqIdx + 1).trim()).append("\n");
                }
                index++;
            }
            yaml.append("\n");
        }

        generateYamlForClass(yaml, configClass, 0);
        return yaml.toString();
    }

    private static void generateYamlForClass(StringBuilder yaml, Class<?> clazz, int indent) {
        String prefix = " ".repeat(indent * 2);
        List<Field> fields = getOrderedFields(clazz);

        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;

            Comment comment = field.getDeclaredAnnotation(Comment.class);
            if (comment != null) {
                for (String line : comment.value().split("\n")) {
                    yaml.append(prefix).append("# ").append(line).append("\n");
                }
            }

            String fieldName = toYamlName(field.getName());
            Class<?> fieldType = field.getType();

            if (fieldType.isAnnotationPresent(ConfigSerializable.class)) {
                yaml.append(prefix).append(fieldName).append(":\n");
                generateYamlForClass(yaml, fieldType, indent + 1);
            } else {
                Object defaultValue = formatDefaultValue(field);
                yaml.append(prefix).append(fieldName).append(": ").append(defaultValue).append("\n");
            }
            yaml.append("\n");
        }
    }

    private static String toYamlName(String fieldName) {
        StringBuilder result = new StringBuilder();
        result.append(Character.toLowerCase(fieldName.charAt(0)));

        for (int i = 1; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (Character.isUpperCase(c)) {
                result.append('-').append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    /**
     * Validates that all fields have proper documentation.
     *
     * @param configClass the configuration class to check
     * @return list of undocumented fields
     */
    public static List<String> findUndocumentedFields(Class<? extends Config> configClass) {
        List<String> undocumented = new ArrayList<>();
        findUndocumentedFieldsRecursive(configClass, "", undocumented);
        return undocumented;
    }

    private static void findUndocumentedFieldsRecursive(Class<?> clazz, String prefix, List<String> results) {
        for (Field field : getOrderedFields(clazz)) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;

            Comment comment = field.getDeclaredAnnotation(Comment.class);
            if (comment == null || comment.value().isEmpty()) {
                String path = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();
                results.add(path);
            }

            Class<?> fieldType = field.getType();
            if (fieldType.isAnnotationPresent(ConfigSerializable.class)) {
                findUndocumentedFieldsRecursive(
                        fieldType,
                        prefix.isEmpty() ? field.getName() : prefix + "." + field.getName(),
                        results
                );
            }
        }
    }
}
