package lab;

import java.nio.file.*;
import java.util.*;

public final class TestMain {
    private TestMain() {}
    public static void main(String[] args) throws Exception {
        List<Path> tests;
        try (var files = Files.list(Path.of("tests/lab"))) { tests = files.filter(path -> path.toString().endsWith("Checks.java")).sorted().toList(); }
        for (Path path : tests) {
            String name = path.getFileName().toString().replace(".java", "");
            try { Class.forName("lab." + name).getMethod("run").invoke(null); System.out.println("PASS " + name); }
            catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError(name + " failed", error.getCause()); }
        }
        System.out.println("Completed " + tests.size() + " test groups.");
    }
}
