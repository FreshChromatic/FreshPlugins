import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

public final class ReadableJava {

    private record Insertion(int offset, String text) {}

    public static void main(String[] arguments) throws Exception {
        boolean check = arguments.length > 0 && arguments[0].equals("--check");
        var compiler = ToolProvider.getSystemJavaCompiler();
        boolean changes = false;
        var nestedTernaries = new ArrayList<String>();
        for (String argument : arguments) {
            if (argument.equals("--check")) {
                continue;
            }
            Path path = Path.of(argument);
            String source = Files.readString(path);
            var insertions = new ArrayList<Insertion>();
            try (var manager = compiler.getStandardFileManager(null, null, null)) {
                var input = new SimpleJavaFileObject(URI.create("string:///" + path.getFileName()),
                    JavaFileObject.Kind.SOURCE) {

                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return source;
                    }
                };
                var files = List.of(input);
                var task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null, files);
                var positions = Trees.instance(task).getSourcePositions();
                for (var unit : task.parse()) {
                    new TreeScanner<Void, Void>() {

                        private boolean conditional(ExpressionTree expression) {
                            while (expression instanceof ParenthesizedTree parentheses) {
                                expression = parentheses.getExpression();
                            }
                            return expression instanceof ConditionalExpressionTree;
                        }

                        @Override
                        public Void visitConditionalExpression(ConditionalExpressionTree tree, Void unused) {
                            if (conditional(tree.getTrueExpression()) || conditional(tree.getFalseExpression())) {
                                long offset = positions.getStartPosition(unit, tree);
                                long line = unit.getLineMap().getLineNumber(offset);
                                nestedTernaries.add(path + ":" + line + ": nested conditional expression");
                            }
                            return super.visitConditionalExpression(tree, unused);
                        }

                        private void wrap(StatementTree body) {
                            if (body == null || body instanceof BlockTree) {
                                return;
                            }
                            int start = (int) positions.getStartPosition(unit, body);
                            int end = (int) positions.getEndPosition(unit, body);
                            if (start < 0 || end < start) {
                                throw new IllegalStateException("Missing source position: " + path);
                            }
                            insertions.add(new Insertion(start, "{\n"));
                            insertions.add(new Insertion(end, "\n}"));
                        }

                        @Override
                        public Void visitIf(IfTree tree, Void unused) {
                            wrap(tree.getThenStatement());
                            if (!(tree.getElseStatement() instanceof IfTree)) {
                                wrap(tree.getElseStatement());
                            }
                            return super.visitIf(tree, unused);
                        }

                        @Override
                        public Void visitForLoop(ForLoopTree tree, Void unused) {
                            wrap(tree.getStatement());
                            return super.visitForLoop(tree, unused);
                        }

                        @Override
                        public Void visitEnhancedForLoop(EnhancedForLoopTree tree, Void unused) {
                            wrap(tree.getStatement());
                            return super.visitEnhancedForLoop(tree, unused);
                        }

                        @Override
                        public Void visitWhileLoop(WhileLoopTree tree, Void unused) {
                            wrap(tree.getStatement());
                            return super.visitWhileLoop(tree, unused);
                        }

                        @Override
                        public Void visitDoWhileLoop(DoWhileLoopTree tree, Void unused) {
                            wrap(tree.getStatement());
                            return super.visitDoWhileLoop(tree, unused);
                        }
                    }.scan(unit, null);
                }
            }
            if (insertions.isEmpty()) {
                continue;
            }
            changes = true;
            if (check) {
                System.out.println(path);
                continue;
            }
            insertions.sort(Comparator.comparingInt(Insertion::offset).reversed());
            var result = new StringBuilder(source);
            for (var insertion : insertions) {
                result.insert(insertion.offset(), insertion.text());
            }
            Files.writeString(path, result);
        }
        nestedTernaries.forEach(System.err::println);
        if ((check && changes) || !nestedTernaries.isEmpty()) {
            System.exit(1);
        }
    }
}
