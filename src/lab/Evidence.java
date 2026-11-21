package lab;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

public final class Evidence {
    private Evidence() {}
    public static String text(Path path) throws Exception { return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n"); }
    public static String hash(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text(path).getBytes(StandardCharsets.UTF_8))); }
    public static void write(Path path, String value) throws Exception { Files.createDirectories(path.toAbsolutePath().getParent()); Files.writeString(path, value.replace("\r\n", "\n"), StandardCharsets.UTF_8); }
    public static String b64(String key) { return Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8)); }
    public static void generate() throws Exception {
        StringBuilder trace = new StringBuilder("parent,operation,key_base64,value\n");
        for (var operation : Fixtures.trace()) trace.append(operation.parent()).append(',').append(operation.operation()).append(',').append(b64(operation.key())).append(',').append(operation.value() == null ? "" : operation.value()).append('\n');
        write(Path.of("data/version-trace.csv"), trace.toString());
        write(Path.of("data/benchmark-operations.csv"), workloadCsv());
    }
    public static String workloadCsv() {
        StringBuilder workloads = new StringBuilder("workload,kind,index,key_base64,value\n");
        for (Workload workload : workloads()) {
            for (int i = 0; i < workload.base().size(); i++) workloads.append(workload.id()).append(",base,").append(i).append(',').append(b64(workload.base().get(i))).append(',').append(i).append('\n');
            for (int i = 0; i < workload.operations().size(); i++) { var op = workload.operations().get(i); workloads.append(workload.id()).append(',').append(op.operation()).append(',').append(i).append(',').append(b64(op.key())).append(',').append(op.value() == null ? "" : op.value()).append('\n'); }
            for (int i = 0; i < workload.queries().size(); i++) workloads.append(workload.id()).append(",read,").append(i).append(',').append(b64(workload.queries().get(i))).append(",\n");
        }
        return workloads.toString();
    }
    public record Workload(String id, List<String> base, List<Fixtures.Operation> operations, List<String> queries) { public Workload { base = List.copyOf(base); operations = List.copyOf(operations); queries = List.copyOf(queries); } }
    public static List<Workload> workloads() {
        List<Workload> workloads = new ArrayList<>();
        for (String id : List.of("ordinary128", "ordinary1024", "collision64")) {
            List<String> base = id.equals("collision64") ? Fixtures.collisions(6) : java.util.stream.IntStream.range(0, id.equals("ordinary128") ? 128 : 1024).mapToObj(i -> "key-" + i).toList();
            Random random = new Random(731_029); List<Fixtures.Operation> operations = new ArrayList<>();
            for (int i = 0; i < 200; i++) { String key = i % 17 == 0 ? "new-" + i : base.get(random.nextInt(base.size())); boolean remove = i % 5 == 0; operations.add(new Fixtures.Operation(i, remove ? "remove" : "put", key, remove ? null : 10_000 + i)); }
            List<String> queries = new ArrayList<>(); for (int i = 0; i < 4096; i++) queries.add(i % 8 == 0 ? "absent-" + i : base.get(random.nextInt(base.size())));
            workloads.add(new Workload(id, base, operations, queries));
        }
        return List.copyOf(workloads);
    }
    private static volatile long sink;
    private static volatile Object retainedSink;
    public record WorkloadSummary(String workload, int baseEntries, int updates, int reads, int retainedVersions, int distinctHamtNodes, long sumPerVersionHamtNodes, long baselineRetainedEntryReferences, List<Hamt.Sharing> sharing, List<Hamt.Entry> finalSnapshot) { public WorkloadSummary { sharing = List.copyOf(sharing); finalSnapshot = List.copyOf(finalSnapshot); } }
    private static List<Hamt> updateHamt(Hamt base, Workload workload) {
        List<Hamt> retained = new ArrayList<>(List.of(base)); Hamt current = base;
        for (var operation : workload.operations()) { current = operation.operation().equals("remove") ? current.remove(operation.key()) : current.put(operation.key(), operation.value()); retained.add(current); }
        return retained;
    }
    private static List<Map<String, Integer>> updateCopy(Map<String, Integer> base, Workload workload) {
        List<Map<String, Integer>> retained = new ArrayList<>(List.of(base)); Map<String, Integer> current = base;
        for (var operation : workload.operations()) {
            Map<String, Integer> copy = new HashMap<>(current);
            if (operation.operation().equals("remove")) copy.remove(operation.key()); else copy.put(operation.key(), operation.value());
            current = Collections.unmodifiableMap(copy); retained.add(current);
        }
        return retained;
    }
    public static WorkloadSummary inspectWorkload(Workload workload) {
        Hamt base = Hamt.empty(); Map<String, Integer> reference = new HashMap<>();
        for (int i = 0; i < workload.base().size(); i++) { base = base.put(workload.base().get(i), i); reference.put(workload.base().get(i), i); }
        List<Hamt> versions = updateHamt(base, workload); List<Map<String, Integer>> copied = updateCopy(Collections.unmodifiableMap(reference), workload);
        List<Hamt.Sharing> sharing = new ArrayList<>(); long nodeSum = 0, entries = 0;
        for (int i = 0; i < versions.size(); i++) { Replay.verify(versions.get(i), copied.get(i)); nodeSum += versions.get(i).stats().nodes(); entries += copied.get(i).size(); if (i > 0) sharing.add(versions.get(i - 1).sharingWith(versions.get(i))); }
        return new WorkloadSummary(workload.id(), base.size(), workload.operations().size(), workload.queries().size(), versions.size(), Hamt.inspect(versions).nodes().size(), nodeSum, entries, sharing, versions.getLast().entries());
    }
    public static void measure(String directory) throws Exception {
        if (!directory.matches("results(?:-[a-z0-9-]+)?")) throw new IllegalArgumentException("Use results or results-<name>");
        Path output = Path.of(directory); Files.createDirectories(output); List<String> files = new ArrayList<>();
        if (!text(Path.of("data/benchmark-operations.csv")).equals(workloadCsv())) throw new IllegalArgumentException("Benchmark fixture differs from deterministic workload definition; regenerate intentionally");
        Replay.Result replay = Replay.run(Replay.load(Path.of("data/version-trace.csv"))); put(output, files, "version-replay.json", Json.encode(replay) + "\n");
        StringBuilder sharing = new StringBuilder("version,parent,operation,key_base64,size,nodes,shared_nodes,new_nodes,retired_nodes,same_root\n");
        for (var change : replay.changes()) sharing.append(change.version()).append(',').append(change.parent()).append(',').append(change.operation()).append(',').append(b64(change.key())).append(',').append(change.stats().size()).append(',').append(change.stats().nodes()).append(',').append(change.sharing().sharedNodes()).append(',').append(change.sharing().newNodes()).append(',').append(change.sharing().retiredNodes()).append(',').append(change.sharing().sameRoot()).append('\n');
        put(output, files, "version-sharing.csv", sharing.toString());
        List<WorkloadSummary> summaries = new ArrayList<>(); StringBuilder timings = new StringBuilder("workload,phase,implementation,repetition,elapsed_nanos,operations,checksum\n");
        int warmups = 2, repetitions = 7;
        for (Workload workload : workloads()) {
            Hamt base = Hamt.empty(); Map<String, Integer> reference = new HashMap<>();
            for (int i = 0; i < workload.base().size(); i++) { base = base.put(workload.base().get(i), i); reference.put(workload.base().get(i), i); }
            WorkloadSummary inspected = inspectWorkload(workload); summaries.add(inspected);
            put(output, files, "workloads/" + workload.id() + ".json", Json.encode(inspected) + "\n");
            for (int round = -warmups; round < repetitions; round++) {
                // Alternate implementation order; setup and validation stay outside intervals.
                for (String implementation : round % 2 == 0 ? List.of("hamt", "copy-hashmap") : List.of("copy-hashmap", "hamt")) {
                    retainedSink = null; long start = System.nanoTime(); long checksum;
                    if (implementation.equals("hamt")) { List<Hamt> versions = updateHamt(base, workload); long elapsed = System.nanoTime() - start; retainedSink = versions; checksum = versions.stream().mapToLong(Hamt::size).sum(); if (round >= 0) timings.append(row(workload.id(), "retained-updates", implementation, round + 1, elapsed, workload.operations().size(), checksum)); }
                    else { List<Map<String, Integer>> versions = updateCopy(reference, workload); long elapsed = System.nanoTime() - start; retainedSink = versions; checksum = versions.stream().mapToLong(Map::size).sum(); if (round >= 0) timings.append(row(workload.id(), "retained-updates", implementation, round + 1, elapsed, workload.operations().size(), checksum)); }
                    sink = checksum;
                    long sum = 0; start = System.nanoTime();
                    if (implementation.equals("hamt")) for (String key : workload.queries()) { Integer value = base.get(key); sum += value == null ? -1 : value; }
                    else for (String key : workload.queries()) { Integer value = reference.get(key); sum += value == null ? -1 : value; }
                    long elapsed = System.nanoTime() - start; sink = sum;
                    if (round >= 0) timings.append(row(workload.id(), "base-reads", implementation, round + 1, elapsed, workload.queries().size(), sum));
                }
            }
            System.out.println(workload.id() + ": " + inspected.distinctHamtNodes() + " distinct retained nodes / " + inspected.sumPerVersionHamtNodes() + " summed version-node references");
        }
        Map<String, Object> summary = new LinkedHashMap<>(); summary.put("warmups", warmups); summary.put("repetitions", repetitions); summary.put("historicalChecks", replay.historicalChecks()); summary.put("workloads", summaries);
        put(output, files, "summary.json", Json.encode(summary) + "\n"); put(output, files, "timings.csv", timings.toString());
        Map<String, String> hashes = new TreeMap<>();
        for (String root : List.of("src", "tools", "data", "tests/lab")) try (var paths = Files.walk(Path.of(root))) { for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) if (path.toString().endsWith(".java") || path.toString().endsWith(".csv")) hashes.put(path.toString().replace('\\', '/'), hash(path)); }
        Map<String, Object> environment = new LinkedHashMap<>(); environment.put("generatedAt", Instant.now().toString()); environment.put("javaVersion", System.getProperty("java.version")); environment.put("javaVendor", System.getProperty("java.vendor")); environment.put("os", System.getProperty("os.name")); environment.put("architecture", System.getProperty("os.arch")); environment.put("logicalProcessors", Runtime.getRuntime().availableProcessors()); environment.put("sourceAndInputSha256", hashes); environment.put("normalization", "CRLF to LF, UTF-8"); environment.put("provenance", "AI-assisted implementation; owned synthetic operations; actual JVM timings and identity-based logical node counts, no JVM byte-size measurements");
        put(output, files, "environment.json", Json.encode(environment) + "\n");
        StringBuilder source = new StringBuilder(); hashes.forEach((path, digest) -> source.append(digest).append("  ").append(path).append('\n')); put(output, files, "source-input.sha256", source.toString());
        StringBuilder manifest = new StringBuilder(); for (String file : files.stream().sorted().toList()) manifest.append(hash(output.resolve(file))).append("  ").append(file).append('\n'); write(output.resolve("artifacts.sha256"), manifest.toString());
    }
    private static String row(String workload, String phase, String implementation, int repetition, long nanos, int operations, long checksum) { return String.join(",", workload, phase, implementation, Integer.toString(repetition), Long.toString(nanos), Integer.toString(operations), Long.toString(checksum)) + "\n"; }
    private static void put(Path root, List<String> files, String file, String content) throws Exception { write(root.resolve(file), content); files.add(file); }
}
