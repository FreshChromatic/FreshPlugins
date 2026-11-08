package github.freshchromatic.freshplugins.formatting;

import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.BadLocationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;

public final class JavaFormatter {

    private final CodeFormatter formatter;
    private final int           lineWidth;

    JavaFormatter(Path profile) throws IOException {
        Map<String, String> options = DefaultCodeFormatterConstants.getEclipseDefaultSettings();
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(profile)) {
            properties.load(reader);
        }
        properties.forEach((key, value) -> options.put(key.toString(), value.toString()));
        this.formatter = ToolFactory.createCodeFormatter(options);
        this.lineWidth = Integer.parseInt(options.get(DefaultCodeFormatterConstants.FORMATTER_LINE_SPLIT));
    }

    String format(String source) throws BadLocationException {
        String lineSeparator = source.contains("\r\n") ? "\r\n" : "\n";
        var edit = this.formatter.format(CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS, source, 0,
            source.length(), 0, lineSeparator);
        if (edit == null) {
            throw new IllegalArgumentException("The formatter could not parse this Java source");
        }
        var document = new Document(source);
        edit.apply(document);
        return FluentChainFormatter.format(document.get(), lineSeparator, lineWidth);
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2) {
            throw new IllegalArgumentException("Expected a formatter profile and Java source paths");
        }
        var formatter = new JavaFormatter(Path.of(arguments[0]));
        boolean check = arguments[1].equals("--check");
        var changed = new ArrayList<Path>();
        int firstSource = check ? 2 : 1;
        var sources = new ArrayList<Path>();
        for (int index = firstSource; index < arguments.length; index++) {
            if (arguments[index].equals("--files-from")) {
                sources.addAll(Files.readAllLines(Path.of(arguments[++index])).stream().map(Path::of).toList());
            } else {
                sources.add(Path.of(arguments[index]));
            }
        }
        for (Path path : sources) {
            String source = Files.readString(path);
            String formatted;
            try {
                formatted = formatter.format(source);
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot format " + path, exception);
            }
            if (formatted.equals(source)) {
                continue;
            }
            changed.add(path);
            if (check) {
                System.err.println(path);
            } else {
                Files.writeString(path, formatted);
            }
        }
        if (check && !changed.isEmpty()) {
            throw new IllegalStateException(changed.size() + " Java files need formatting");
        }
        System.out.println(check ? "Java formatting check passed" : "Formatted " + changed.size() + " Java files");
    }
}
