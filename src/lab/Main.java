package lab;

import java.nio.file.*;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        try {
            if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) { System.out.println("Usage: java tools/Run.java replay <trace.csv> | generate | measure [results-local] | test\nExit0: completed; exit2: invalid input/error. Values[-1000000,1000000], keys≤128 UTF-16 units, no nulls."); }
            else if (args.length == 2 && args[0].equals("replay")) System.out.println(Json.encode(Replay.run(Replay.load(Path.of(args[1])))));
            else if (args.length == 1 && args[0].equals("generate")) Evidence.generate();
            else if ((args.length == 1 || args.length == 2) && args[0].equals("measure")) Evidence.measure(args.length == 2 ? args[1] : "results");
            else throw new IllegalArgumentException("Invalid arguments; use --help");
        } catch (Exception error) { System.err.println("Error: " + error.getMessage()); System.exit(2); }
    }
}
