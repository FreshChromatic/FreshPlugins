package github.freshchromatic.freshplugins.formatting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class JavaFormatterTest {

    private final JavaFormatter formatter = new JavaFormatter(Path.of("../java-format.properties"));

    JavaFormatterTest() throws Exception {
    }

    @Test
    void alignsRelatedFieldsWithoutExtendingAcrossBlankLines() throws Exception {
        String source = """
            class Example {
                public static final String DIR_WORLDS = "/worlds/";
                public static final String DIR_INVENTORIES = "/inventories/";

                private final Map<String, ChunkGenerator> generatorMap;
                private final Map<String, WorldInventories> inventoryMap;
            }
            """;
        String result = this.formatter.format(source);
        assertTrue(result.contains("public static final String DIR_WORLDS      = \"/worlds/\";"));
        assertTrue(result.contains("private final Map<String, ChunkGenerator>   generatorMap;"));
        assertTrue(result.contains("private final Map<String, WorldInventories> inventoryMap;"));
        assertEquals(result, this.formatter.format(result));
    }

    @Test
    void keepsSimpleInitializersAndOuterCallsTogether() throws Exception {
        String source = """
            class Example {
                void render() {
                    LevelChunk chunk =
                        level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    rows.add(
                        SettingsAddon.StatusRow.info(tr("phase"), () -> tr("phase." + session.phaseKey())));
                }
            }
            """;
        String result = this.formatter.format(source);
        assertTrue(result.contains(
            "LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);"));
        assertTrue(result.contains("rows.add(SettingsAddon.StatusRow.info("));
        assertFalse(result.contains("rows.add(\n"));
    }

    @Test
    void keepsMultilineInitializationWithItsDeclaration() throws Exception {
        String source = """
            class Example {
                void configure() {
                    var property =
                        this.addProperty(ConfigTypes.STRING_LIST,
                            "PlayerInfo.Format",
                            List.of("first", "second"),
                            () -> {
                                updateState();
                                publishState();
                            });
                }
            }
            """;
        String result = this.formatter.format(source);
        assertTrue(result.contains("var property = this.addProperty("));
        assertEquals(syntax(source), syntax(result));
        assertEquals(result, this.formatter.format(result));
    }

    @Test
    void preservesModernJavaSyntaxCommentsAndLineEndings() throws Exception {
        String source = """
            record Example(int count, String label) {
                // Why this value matters.
                String value() {
                    return switch (count) {
                        case 0 -> "empty";
                        default -> label + "=" + count;
                    };
                }
            }
            """.replace("\n", "\r\n");
        String result = this.formatter.format(source);
        assertEquals(syntax(source), syntax(result));
        assertTrue(result.contains("// Why this value matters."));
        assertFalse(result.replace("\r\n", "").contains("\n"));
        assertEquals(result, this.formatter.format(result));
    }

    @Test
    void wrapsLongConstructorWithoutBreakingShortMethodCalls() throws Exception {
        String source = """
            class Example {
                Example(FirstDependency firstDependency, SecondDependency secondDependency,
                    ThirdDependency thirdDependency, FourthDependency fourthDependency,
                    FifthDependency fifthDependency) {}
                void render() {
                    sender.sendMessage(messages.scan.radiusTooLarge.withPlaceholders(
                        Components.placeholder("radius", String.valueOf(radius)),
                        Components.placeholder("maximum", String.valueOf(maximumRadius))));
                }
            }
            """;
        String result = this.formatter.format(source);
        assertTrue(result.lines().filter(line -> line.contains("Example(")).findFirst().orElseThrow().length() <= 120);
        assertTrue(result.contains("String.valueOf(radius)"));
        assertTrue(result.contains("String.valueOf(maximumRadius)"));
        assertEquals(syntax(source), syntax(result));
        assertEquals(result, this.formatter.format(result));
    }

    @Test
    void wrapsLongChainsAndLambdaWithoutChangingSyntax() throws Exception {
        String source = """
            class Example {
                void register() {
                    manager.exceptionController().registerHandler(CommandExecutionException.class, this::commandExecution)
                        .registerHandler(CommandCompleted.class, this::commandCompleted)
                        .registerHandler(NoPermissionException.class, this::noPermission)
                        .registerHandler(ArgumentParseException.class, this::argumentParsing);
                    this.commands.registerSubcommand(builder -> builder.literal("reload")
                        .commandDescription(richDescription(configManager.messages().command.description.reload))
                        .permission("freshlib.command.reload").handler(this::execute));
                }
            }
            """;
        String result = formatter.format(source);
        assertTrue(result.contains("exceptionController()\n            .registerHandler("), result);
        assertTrue(result.contains("builder ->\n            builder.literal(\"reload\")"), result);
        assertTrue(result.contains("\n                .commandDescription("), result);
        assertTrue(result.contains("\n                .permission("), result);
        assertTrue(result.contains("\n                .handler(this::execute)"), result);
        assertTrue(result.contains(
            ".commandDescription(richDescription(configManager.messages().command.description.reload))"), result);
        assertTrue(result.contains(".registerHandler(CommandCompleted.class, this::commandCompleted)"), result);
        assertEquals(syntax(source), syntax(result));
        assertEquals(result, formatter.format(result));
    }

    @Test
    void indentsLambdaChainAfterAnOuterFluentCall() throws Exception {
        String source = """
            class Example {
                void run() {
                    values.entrySet().stream().flatMap(entry -> entry.getValue().stream()
                        .map(value -> convert(entry.getKey(), value))).toList();
                }
            }
            """;
        String result = formatter.format(source);
        assertTrue(result.contains(".flatMap(entry ->\n                entry.getValue()"), result);
        assertTrue(result.contains("\n                    .stream()"), result);
        assertEquals(syntax(source), syntax(result));
        assertEquals(result, formatter.format(result));
    }

    @Test
    void indentsBlocksWhenFluentCallsAreSplit() throws Exception {
        String source = """
            class Example {
                void run() {
                    Thread.ofVirtual().name("worker").start(() -> {
                        try {
                            work();
                        } finally {
                            finish();
                        }
                    });
                }
            }
            """;
        String result = formatter.format(source);
        assertTrue(result.contains(".start(() -> {\n                try {\n                    work();"), result);
        assertTrue(result.contains("\n            });"), result);
        assertEquals(syntax(source), syntax(result));
        assertEquals(result, formatter.format(result));
    }

    @Test
    void preservesCommentsStringsAndLineEndingsInFluentChains() throws Exception {
        String source = """
            class Example {
                void register() {
                    var text = "not.a.chain().and.not.an.arrow -> here";
                    commands.registerSubcommand(builder -> builder.literal("reload")
                        /* Keep the explanatory comment. */ .description("a.b")
                        .permission("example.reload").handler(this::execute));
                }
            }
            """.replace("\n", "\r\n");
        String result = formatter.format(source);
        assertTrue(result.contains("/* Keep the explanatory comment. */"));
        assertTrue(result.contains("not.a.chain().and.not.an.arrow -> here"));
        assertFalse(result.replace("\r\n", "").contains("\n"));
        assertEquals(syntax(source), syntax(result));
        assertEquals(result, formatter.format(result));
    }

    @Test
    void respectsConfiguredLineWidthForFluentChains(@TempDir Path directory) throws Exception {
        Path profile = directory.resolve("java-format.properties");
        String settings = Files.readString(Path.of("../java-format.properties"));
        Files.writeString(profile, settings.replace("lineSplit=120", "lineSplit=60"));
        var narrowFormatter = new JavaFormatter(profile);
        String source = """
            class Example {
                void run() {
                    client.connect().authenticate(user).fetchRecords().close();
                }
            }
            """;
        String narrow = narrowFormatter.format(source);
        assertTrue(narrow.contains("connect()\n"), narrow);
        assertEquals(narrow, narrowFormatter.format(narrow));
        assertEquals(syntax(source), syntax(narrow));
    }

    @Test
    void fluentChainUsesTheSuppliedWidthBeforeJdtWrapping() {
        String source = "class E { void run() { client.connect().authenticate(user).fetchRecords().close(); } }\n";
        assertTrue(FluentChainFormatter.format(source, "\n", 60).contains("connect()\n"));
        assertFalse(FluentChainFormatter.format(source, "\n", 120).contains("connect()\n"));
    }

    private String syntax(String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var input = new SimpleJavaFileObject(URI.create("string:///Example.java"), JavaFileObject.Kind.SOURCE) {

                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            var task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null, List.of(input));
            return task.parse().iterator().next().toString();
        }
    }

    @Test
    void formatsSourcesListedInManifest(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("Example.java");
        Path manifest = directory.resolve("sources.txt");
        Files.writeString(source, "class Example{int value;}\n");
        Files.writeString(manifest, source.toString());

        JavaFormatter.main(new String[] { "../java-format.properties", "--files-from", manifest.toString() });

        assertTrue(Files.readString(source).contains("int value;"));
        JavaFormatter.main(new String[] { "../java-format.properties", "--check", "--files-from",
            manifest.toString() });
    }
}
