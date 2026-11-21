import javax.tools.ToolProvider;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;

/** Standard-JDK launcher: compile conventional source files, then run one command. */
public class Run {
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() < 21) throw new IllegalStateException("Java 21 or newer is required");
        Files.createDirectories(Path.of("build", "classes"));
        List<String> flags = new ArrayList<>(List.of("--release", "21", "-Xlint:all", "-Werror", "-d", "build/classes"));
        for (String folder : List.of("src", "tests")) try (var paths = Files.walk(Path.of(folder))) {
            paths.filter(path -> path.toString().endsWith(".java")).sorted().forEach(path -> flags.add(path.toString()));
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null || compiler.run(null, null, null, flags.toArray(String[]::new)) != 0) throw new IllegalStateException("Compilation failed");
        try (var loader = new URLClassLoader(new java.net.URL[] { Path.of("build/classes").toUri().toURL() })) {
            Class<?> main = loader.loadClass(args.length > 0 && args[0].equals("test") ? "lab.TestMain" : "lab.Main");
            String[] forwarded = args.length > 0 && args[0].equals("test") ? Arrays.copyOfRange(args, 1, args.length) : args;
            try { main.getMethod("main", String[].class).invoke(null, (Object) forwarded); }
            catch (java.lang.reflect.InvocationTargetException error) { throw new RuntimeException(error.getCause()); }
        }
    }
}
