package github.freshchromatic.freshplugins.formatting;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class FluentChainFormatter {

    private record Edit(int start, int end, String replacement) {}

    private record Range(int start, int end, int continuationColumn) {}

    static String format(String source, String lineSeparator, int lineWidth) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setCompilerOptions(Map.of(JavaCore.COMPILER_SOURCE, "25", JavaCore.COMPILER_COMPLIANCE, "25"));
        ASTNode root = parser.createAST(null);
        List<Edit> edits = new ArrayList<>();
        List<Range> chains = new ArrayList<>();
        root.accept(new ASTVisitor() {

            @Override
            public boolean visit(MethodInvocation invocation) {
                if (invocation.getParent() instanceof MethodInvocation parent && parent.getExpression() == invocation) {
                    return true;
                }
                List<MethodInvocation> chain = new ArrayList<>();
                MethodInvocation current = invocation;
                while (current.getExpression() instanceof MethodInvocation receiver) {
                    chain.add(current);
                    current = receiver;
                }
                if (chain.size() < 2) {
                    return true;
                }
                int start = invocation.getStartPosition();
                int end = start + invocation.getLength();
                int lineStart = source.lastIndexOf('\n', start - 1) + 1;
                boolean multiline = source.substring(start, end).contains("\n");
                if (!multiline && end - lineStart <= lineWidth) {
                    return true;
                }
                int indentEnd = lineStart;
                while (indentEnd < source.length() && (source.charAt(indentEnd) == ' ' || source.charAt(
                    indentEnd) == '\t')) {
                    indentEnd++;
                }
                String indent = source.substring(lineStart, indentEnd);
                if (invocation.getParent() instanceof LambdaExpression lambda && lambda.getBody() == invocation) {
                    int arrow = source.indexOf("->", lambda.getStartPosition());
                    if (arrow >= 0 && arrow + 2 <= start && source.substring(arrow + 2, start).isBlank()) {
                        edits.add(new Edit(arrow + 2, start, lineSeparator + indent + "    "));
                        indent += "    ";
                    }
                }
                chains.add(new Range(start, end, indent.length() + 4));
                String continuation = lineSeparator + indent + "    ";
                for (MethodInvocation part : chain) {
                    ASTNode receiver = part.getExpression();
                    int receiverEnd = receiver.getStartPosition() + receiver.getLength();
                    int nameStart = part.getName().getStartPosition();
                    int dot = source.lastIndexOf('.', nameStart);
                    if (dot >= receiverEnd && source.substring(receiverEnd, dot).isBlank()) {
                        edits.add(new Edit(receiverEnd, dot, continuation));
                    }
                }
                return true;
            }
        });
        root.accept(new ASTVisitor() {

            @Override
            public boolean visit(MethodInvocation invocation) {
                int start = invocation.getStartPosition();
                int end = start + invocation.getLength();
                var continuationColumn = chains.stream()
                    .filter(range -> start >= range.start()
                    && end <= range.end())
                    .mapToInt(Range::continuationColumn)
                    .max();
                if (continuationColumn.isEmpty()) {
                    return true;
                }
                List<?> arguments = invocation.arguments();
                if (arguments.isEmpty()) {
                    return true;
                }
                int nameStart = invocation.getName().getStartPosition();
                String call = source.substring(nameStart, end);
                if (!call.contains("\n") || continuationColumn.getAsInt() + 1 + call.replaceAll("\\s+",
                    " ").length() > lineWidth) {
                    return true;
                }
                int open = source.indexOf('(', nameStart + invocation.getName().getLength());
                if (open < 0 || source.charAt(end - 1) != ')') {
                    return true;
                }
                int gapStart = open + 1;
                for (int index = 0; index < arguments.size(); index++) {
                    ASTNode argument = (ASTNode) arguments.get(index);
                    int argumentStart = argument.getStartPosition();
                    String gap = source.substring(gapStart, argumentStart);
                    if (gap.contains("\n") && (index == 0 ? gap.isBlank() : gap.trim().equals(","))) {
                        edits.add(new Edit(gapStart, argumentStart, index == 0 ? "" : ", "));
                    }
                    gapStart = argumentStart + argument.getLength();
                }
                String trailing = source.substring(gapStart, end - 1);
                if (trailing.contains("\n") && trailing.isBlank()) {
                    edits.add(new Edit(gapStart, end - 1, ""));
                }
                return true;
            }
        });
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        var result = new StringBuilder(source);
        for (Edit edit : edits) {
            result.replace(edit.start(), edit.end(), edit.replacement());
        }
        return indentLambdaExpressions(indentLambdaBlocks(result.toString()));
    }

    private static String indentLambdaExpressions(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setCompilerOptions(Map.of(JavaCore.COMPILER_SOURCE, "25", JavaCore.COMPILER_COMPLIANCE, "25"));
        List<Edit> edits = new ArrayList<>();
        parser.createAST(null).accept(new ASTVisitor() {

            @Override
            public boolean visit(LambdaExpression lambda) {
                ASTNode body = lambda.getBody();
                if (body instanceof Block) {
                    return true;
                }
                int bodyStart = body.getStartPosition();
                int arrow = source.lastIndexOf("->", bodyStart);
                int arrowLine = source.lastIndexOf('\n', arrow - 1) + 1;
                int bodyLine = source.lastIndexOf('\n', bodyStart - 1) + 1;
                if (arrow < lambda.getStartPosition() || bodyLine <= arrowLine) {
                    return true;
                }
                String arrowPrefix = source.substring(arrowLine, arrow);
                int expectedIndent = arrowPrefix.length() - arrowPrefix.stripLeading().length() + 4;
                String bodyIndent = source.substring(bodyLine, bodyStart);
                if (!bodyIndent.isBlank() || bodyIndent.length() >= expectedIndent) {
                    return true;
                }
                boolean[] hasTextBlock = { false };
                body.accept(new ASTVisitor() {

                    @Override
                    public boolean visit(TextBlock textBlock) {
                        hasTextBlock[0] = true;
                        return false;
                    }
                });
                if (hasTextBlock[0]) {
                    return true;
                }
                String extraIndent = " ".repeat(expectedIndent - bodyIndent.length());
                int end = bodyStart + body.getLength();
                int line = bodyLine;
                while (line < end) {
                    int next = source.indexOf('\n', line);
                    int lineEnd = next < 0 ? source.length() : next;
                    if (!source.substring(line, lineEnd).isBlank()) {
                        edits.add(new Edit(line, line, extraIndent));
                    }
                    line = next < 0 ? end : next + 1;
                }
                return false;
            }
        });
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        var result = new StringBuilder(source);
        for (Edit edit : edits) {
            result.replace(edit.start(), edit.end(), edit.replacement());
        }
        return result.toString();
    }

    private static String indentLambdaBlocks(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setCompilerOptions(Map.of(JavaCore.COMPILER_SOURCE, "25", JavaCore.COMPILER_COMPLIANCE, "25"));
        List<Edit> edits = new ArrayList<>();
        parser.createAST(null).accept(new ASTVisitor() {

            @Override
            public boolean visit(LambdaExpression lambda) {
                if (!(lambda.getBody() instanceof Block block)) {
                    return true;
                }
                int blockStart = block.getStartPosition();
                int blockEnd = blockStart + block.getLength() - 1;
                int openingLine = source.lastIndexOf('\n', blockStart - 1) + 1;
                int closingLine = source.lastIndexOf('\n', blockEnd - 1) + 1;
                String openingIndent = source.substring(openingLine, blockStart).stripLeading();
                int expectedIndent = blockStart - openingLine - openingIndent.length();
                String closingIndent = source.substring(closingLine, blockEnd);
                if (!closingIndent.isBlank() || closingIndent.length() >= expectedIndent) {
                    return true;
                }
                boolean[] hasTextBlock = { false };
                block.accept(new ASTVisitor() {

                    @Override
                    public boolean visit(TextBlock textBlock) {
                        hasTextBlock[0] = true;
                        return false;
                    }
                });
                if (hasTextBlock[0]) {
                    return true;
                }
                String extraIndent = " ".repeat(expectedIndent - closingIndent.length());
                int line = source.indexOf('\n', blockStart) + 1;
                while (line > 0 && line <= closingLine) {
                    if (line == closingLine || !source.substring(line, source.indexOf('\n', line) < 0 ? source.length()
                        : source.indexOf('\n', line)).isBlank()) {
                        edits.add(new Edit(line, line, extraIndent));
                    }
                    int next = source.indexOf('\n', line);
                    line = next < 0 ? -1 : next + 1;
                }
                return false;
            }
        });
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        var result = new StringBuilder(source);
        for (Edit edit : edits) {
            result.replace(edit.start(), edit.end(), edit.replacement());
        }
        return result.toString();
    }

    private FluentChainFormatter() {
    }
}
