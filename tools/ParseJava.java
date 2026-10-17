import javax.tools.*;
import com.sun.source.util.JavacTask;
import java.util.*;

/** Syntax check only; does not replace Android SDK compilation. */
class ParseJava {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    Arrays.asList("-proc:none", "-encoding", "UTF-8"), null, files.getJavaFileObjects(args));
            task.parse();
            int errors = 0;
            for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) { System.err.println(d); errors++; }
            }
            if (errors > 0) System.exit(1);
            System.out.println("PASS: Java syntax (" + args.length + " files). Android API resolution not checked.");
        }
    }
}
