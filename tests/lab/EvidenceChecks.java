package lab;

import java.nio.file.*;
import java.util.*;
import static lab.CoreChecks.*;

public final class EvidenceChecks {
    private EvidenceChecks() {}
    public static void run() throws Exception {
        for (String file : List.of("source-input.sha256", "artifacts.sha256")) for (String line : Evidence.text(Path.of("results", file)).lines().toList()) {
            String[] fields = line.split("  ", 2); equal(Evidence.hash(file.startsWith("source") ? Path.of(fields[1]) : Path.of("results", fields[1])), fields[0]);
        }
        equal(Evidence.text(Path.of("data/benchmark-operations.csv")), Evidence.workloadCsv());
        Replay.Result replay = Replay.run(Replay.load(Path.of("data/version-trace.csv")));
        equal(Evidence.text(Path.of("results/version-replay.json")), Json.encode(replay) + "\n");
        StringBuilder sharingCsv = new StringBuilder("version,parent,operation,key_base64,size,nodes,shared_nodes,new_nodes,retired_nodes,same_root\n");
        for (var change : replay.changes()) sharingCsv.append(change.version()).append(',').append(change.parent()).append(',').append(change.operation()).append(',').append(Evidence.b64(change.key())).append(',').append(change.stats().size()).append(',').append(change.stats().nodes()).append(',').append(change.sharing().sharedNodes()).append(',').append(change.sharing().newNodes()).append(',').append(change.sharing().retiredNodes()).append(',').append(change.sharing().sameRoot()).append('\n');
        equal(Evidence.text(Path.of("results/version-sharing.csv")), sharingCsv.toString());
        Map<Integer, Hamt.NodeView> nodes = new HashMap<>(); replay.inspection().nodes().forEach(node -> nodes.put(node.id(), node));
        for (Hamt.VersionView version : replay.inspection().versions()) {
            Set<Integer> reachable = new TreeSet<>(); Set<String> keys = new HashSet<>();
            validate(version.root(), 0, 0L, 0L, nodes, reachable, keys);
            equal(new ArrayList<>(reachable), version.reachable()); equal(keys.size(), version.size());
        }
        for (var change : replay.changes()) {
            Set<Integer> parent = new HashSet<>(replay.inspection().versions().get(change.parent()).reachable());
            Set<Integer> child = new HashSet<>(replay.inspection().versions().get(change.version()).reachable());
            Set<Integer> intersection = new HashSet<>(parent); intersection.retainAll(child);
            equal(change.sharing().sharedNodes(), intersection.size()); equal(change.sharing().newNodes(), child.size() - intersection.size()); equal(change.sharing().retiredNodes(), parent.size() - intersection.size());
        }
        List<String[]> samples = Evidence.text(Path.of("results/timings.csv")).lines().skip(1).map(line -> line.split(",")).toList(); equal(samples.size(), 84);
        List<Evidence.WorkloadSummary> summaries = new ArrayList<>();
        for (var workload : Evidence.workloads()) {
            var inspected = Evidence.inspectWorkload(workload); summaries.add(inspected);
            equal(Evidence.text(Path.of("results/workloads", workload.id() + ".json")), Json.encode(inspected) + "\n");
            Map<String, Integer> base = new HashMap<>(); for (int i = 0; i < workload.base().size(); i++) base.put(workload.base().get(i), i);
            long readChecksum = workload.queries().stream().mapToLong(key -> base.getOrDefault(key, -1)).sum();
            for (String phase : List.of("retained-updates", "base-reads")) for (String implementation : List.of("hamt", "copy-hashmap")) {
                List<String[]> rows = samples.stream().filter(row -> row[0].equals(workload.id()) && row[1].equals(phase) && row[2].equals(implementation)).toList(); equal(rows.size(), 7);
                for (int i = 0; i < rows.size(); i++) { String[] row = rows.get(i); equal(Integer.parseInt(row[3]), i + 1); check(Long.parseLong(row[4]) >= 0); equal(Integer.parseInt(row[5]), phase.equals("base-reads") ? workload.queries().size() : workload.operations().size()); equal(Long.parseLong(row[6]), phase.equals("base-reads") ? readChecksum : inspected.baselineRetainedEntryReferences()); }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>(); summary.put("warmups", 2); summary.put("repetitions", 7); summary.put("historicalChecks", replay.historicalChecks()); summary.put("workloads", summaries);
        equal(Evidence.text(Path.of("results/summary.json")), Json.encode(summary) + "\n");
        Path presentation = Path.of("docs/figures/artifacts.sha256");
        if (Files.exists(presentation)) for (String line : Evidence.text(presentation).lines().toList()) { String[] fields = line.split("  ", 2); equal(Evidence.hash(Path.of(fields[1])), fields[0]); }
        System.out.println("  Complete trace, bitmap routing, identity intersections,3 workloads and84 timing rows reconcile");
    }
    private static void validate(Integer id, int shift, long mask, long prefix, Map<Integer, Hamt.NodeView> nodes, Set<Integer> visited, Set<String> keys) {
        if (id == null) return;
        check(visited.add(id)); Hamt.NodeView node = nodes.get(id); check(node != null);
        if (node.kind().equals("branch")) {
            check(shift <= 30); int bitmap = (int) Long.parseLong(node.bitmapHex(), 16); equal(Integer.bitCount(bitmap), node.children().size()); check(!node.children().isEmpty());
            if (node.children().size() == 1) check(nodes.get(node.children().getFirst()).kind().equals("branch"));
            int at = 0; for (int slot = 0; slot < 32; slot++) if ((bitmap & (1 << slot)) != 0) { if (shift == 30) check(slot < 4); long nextMask = mask | (31L << shift); validate(node.children().get(at++), shift + 5, nextMask, prefix | ((long) slot << shift), nodes, visited, keys); }
        } else {
            int hash = (int) Long.parseLong(node.hashHex(), 16); check((Integer.toUnsignedLong(hash) & mask) == prefix);
            check(node.children().isEmpty()); check(node.kind().equals("leaf") ? node.entries().size() == 1 : node.entries().size() >= 2);
            for (var entry : node.entries()) { equal(entry.key().hashCode(), hash); check(keys.add(entry.key())); }
        }
    }
}
