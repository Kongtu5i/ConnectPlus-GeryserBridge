import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Compile a supplied source list in a short-lived JVM using the standard JDK compiler. */
public final class CompileJava {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required");
        StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ENGLISH, StandardCharsets.UTF_8);
        List<String> all = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        int separator = all.indexOf("--sources--");
        if (separator < 0) throw new IllegalArgumentException("Missing --sources-- separator");
        boolean success = compiler.getTask(null, manager, null, all.subList(0, separator), null,
                manager.getJavaFileObjectsFromStrings(all.subList(separator + 1, all.size()))).call();
        // This process owns the manager and exits immediately. The OS releases its handles.
        // Avoid ZipFileSystem.close's realpath operation denied by some desktop sandboxes.
        System.exit(success ? 0 : 1);
    }
}
