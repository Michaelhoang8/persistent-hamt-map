package lab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static lab.Fixtures.*;

public final class Replay {
    private Replay() {}
    public record Change(int version, int parent, String operation, String key, String hashHex, Integer value, Hamt.Stats stats, Hamt.Sharing sharing, List<Hamt.Entry> snapshot) { public Change { snapshot = List.copyOf(snapshot); } }
    public record Result(List<Change> changes, Hamt.Inspection inspection, int historicalChecks) { public Result { changes = List.copyOf(changes); } }
    public static List<Operation> load(Path path) throws IOException {
        if (Files.size(path) > 262_144) throw new IllegalArgumentException("Trace exceeds byte limit");
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().equals("parent,operation,key_base64,value") || lines.size() > 129) throw new IllegalArgumentException("Expected trace header and at most128 operations");
        List<Operation> operations = new ArrayList<>();
        for (int row = 1; row < lines.size(); row++) {
            String[] fields = lines.get(row).split(",", -1); if (fields.length != 4 || !fields[0].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("Malformed trace row");
            int parent = Integer.parseInt(fields[0]); if (parent >= row) throw new IllegalArgumentException("Parent must be an existing earlier version");
            byte[] bytes = Base64.getDecoder().decode(fields[2]); String key = new String(bytes, StandardCharsets.UTF_8);
            if (!Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8)).equals(fields[2])) throw new IllegalArgumentException("Noncanonical Base64 or invalid UTF-8 key");
            Hamt.validateKey(key); Integer value = null;
            if (fields[1].equals("put")) { if (!fields[3].matches("0|-?[1-9][0-9]{0,6}")) throw new IllegalArgumentException("Invalid value"); value = Integer.valueOf(fields[3]); if (value < Hamt.MIN_VALUE || value > Hamt.MAX_VALUE) throw new IllegalArgumentException("Value outside bound"); }
            else if (!fields[1].equals("remove") || !fields[3].isEmpty()) throw new IllegalArgumentException("Expected put with value or remove without value");
            operations.add(new Operation(parent, fields[1], key, value));
        }
        return List.copyOf(operations);
    }
    public static Result run(List<Operation> operations) {
        if (operations == null || operations.size() > 128) throw new IllegalArgumentException("At most128 operations");
        List<Hamt> versions = new ArrayList<>(List.of(Hamt.empty())); List<Map<String, Integer>> oracle = new ArrayList<>(List.of(Map.of())); List<Change> changes = new ArrayList<>(); int historicalChecks = 0;
        for (Operation operation : operations) {
            if (operation == null || operation.parent() < 0 || operation.parent() >= versions.size()) throw new IllegalArgumentException("Invalid parent version");
            Hamt before = versions.get(operation.parent()); Hamt after; Map<String, Integer> expected = new HashMap<>(oracle.get(operation.parent()));
            if (operation.operation().equals("put") && operation.value() != null) { after = before.put(operation.key(), operation.value()); expected.put(operation.key(), operation.value()); }
            else if (operation.operation().equals("remove") && operation.value() == null) { after = before.remove(operation.key()); expected.remove(operation.key()); }
            else throw new IllegalArgumentException("Invalid operation/value combination");
            versions.add(after); oracle.add(Map.copyOf(expected));
            for (int version = 0; version < versions.size(); version++) { verify(versions.get(version), oracle.get(version)); historicalChecks++; }
            changes.add(new Change(versions.size() - 1, operation.parent(), operation.operation(), operation.key(), String.format(Locale.ROOT, "%08x", operation.key().hashCode()), operation.value(), after.stats(), before.sharingWith(after), after.entries()));
        }
        return new Result(changes, Hamt.inspect(versions), historicalChecks);
    }
    public static void verify(Hamt map, Map<String, Integer> expected) {
        if (map.size() != expected.size()) throw new IllegalStateException("Size differs from independent oracle");
        List<Hamt.Entry> entries = expected.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> new Hamt.Entry(entry.getKey(), entry.getValue())).toList();
        if (!map.entries().equals(entries)) throw new IllegalStateException("Snapshot differs from independent oracle");
        for (var entry : expected.entrySet()) if (!entry.getValue().equals(map.get(entry.getKey()))) throw new IllegalStateException("Lookup differs from independent oracle");
    }
}
