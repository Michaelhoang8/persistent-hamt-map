package lab;

import java.nio.file.*;
import java.util.*;
import static lab.CoreChecks.*;

public final class CliChecks {
    private CliChecks() {}
    private record Output(int status, String text) {}
    private static Output command(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", "build/classes", "lab.Main")); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start(); String text = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); int status = process.waitFor(); check(!text.contains("\tat ")); return new Output(status, text);
    }
    public static void run() throws Exception {
        equal(command("--help").status(), 0); equal(command("-h").status(), 0); Output replay = command("replay", "data/version-trace.csv"); equal(replay.status(), 0); check(replay.text().contains("\"historicalChecks\":299"));
        equal(command("replay").status(), 2); equal(command("replay", "missing.csv").status(), 2); equal(command("unknown").status(), 2);
        Path temporary = Files.createTempFile("hamt-input-", ".csv");
        try {
            for (String row : List.of("1,put,eA==,1", "0,put,eA==,1000001", "0,put,/w==,1", "0,put,eA,1", "0,remove,eA==,1", "0,put,eA==,-0")) {
                Files.writeString(temporary, "parent,operation,key_base64,value\n" + row + "\n"); equal(command("replay", temporary.toString()).status(), 2);
            }
        } finally { Files.deleteIfExists(temporary); }
        System.out.println(" 12 CLI/help/error cases, Base64/UTF-8/parent/value validation");
    }
}
