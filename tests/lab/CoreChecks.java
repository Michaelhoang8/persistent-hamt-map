package lab;

import java.util.*;

public final class CoreChecks {
    private CoreChecks() {}
    public static void check(boolean value) { if (!value) throw new AssertionError("Assertion failed"); }
    public static void equal(Object actual, Object expected) { if (!Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + ", got " + actual); }
    public static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; } throw new AssertionError("Expected rejection"); }
    public static void run() {
        Hamt empty = Hamt.empty(); equal(empty.get("missing"), null); check(empty.remove("missing") == empty);
        Hamt one = empty.put("", 0); equal(one.get(""), 0); check(one.put("", 0) == one); equal(one.remove("").size(), 0);
        rejects(() -> empty.get(null)); rejects(() -> empty.put(null, 1)); rejects(() -> empty.remove(null)); rejects(() -> empty.put("x", 1_000_001)); rejects(() -> empty.put("x", -1_000_001)); rejects(() -> empty.put("\ud800", 1)); rejects(() -> empty.put("x".repeat(129), 1));
        Hamt collision = empty.put("Aa", 1).put("BB", 2); equal("Aa".hashCode(), "BB".hashCode()); equal(collision.stats().collisions(), 1);
        Hamt expanded = collision.put("Cc", 3); equal(expanded.get("Aa"), 1); equal(expanded.get("BB"), 2); equal(expanded.remove("Cc").stats().collisions(), 1);
        Hamt leaf = expanded.remove("Cc").remove("Aa"); equal(leaf.stats().leaves(), 1); equal(leaf.stats().branches(), 0); equal(leaf.get("BB"), 2); equal(collision.get("Aa"), 1);
        String h1 = Fixtures.forHash(1), h2 = Fixtures.forHash(0x40000001), hn = Fixtures.forHash(0x80000001);
        Hamt deep = empty.put(h1, 1).put(h2, 2).put(hn, 3); equal(deep.stats().maxDepth(), 7); check(hn.hashCode() < 0);
        Hamt contracted = deep.remove(h2); equal(contracted.get(hn), 3); equal(contracted.stats().maxDepth(), 7);
        Hamt terminal = contracted.remove(hn); equal(terminal.stats().maxDepth(), 0); equal(terminal.get(h1), 1);
        Hamt signedBitmap = empty.put(Fixtures.forHash(0), 0).put(Fixtures.forHash(31), 31).put(Fixtures.forHash(30), 30); equal(signedBitmap.get(Fixtures.forHash(31)), 31); equal(signedBitmap.remove(Fixtures.forHash(30)).get(Fixtures.forHash(31)), 31);
        Replay.Result trace = Replay.run(Fixtures.trace()); equal(trace.changes().size(), 23); check(trace.changes().get(6).sharing().sameRoot()); check(trace.changes().get(7).sharing().sameRoot());
        // Keep every historical root and independently copied oracle, with random forks.
        int comparisons = 0;
        for (int seed = 0; seed < 12; seed++) {
            Random random = new Random(seed); List<Hamt> versions = new ArrayList<>(List.of(empty)); List<Map<String, Integer>> references = new ArrayList<>(List.of(Map.of()));
            List<String> keys = new ArrayList<>(Fixtures.collisions(4)); for (int i = 0; i < 40; i++) keys.add("key" + i); keys.addAll(List.of(h1, h2, hn, "", "🧪"));
            for (int step = 0; step < 120; step++) {
                int parent = random.nextInt(versions.size()); String key = keys.get(random.nextInt(keys.size())); Hamt previous = versions.get(parent); Map<String, Integer> expected = new HashMap<>(references.get(parent)); Hamt next;
                if (random.nextInt(4) == 0) { next = previous.remove(key); expected.remove(key); } else { int value = random.nextInt(100) - 50; next = previous.put(key, value); expected.put(key, value); }
                versions.add(next); references.add(Map.copyOf(expected));
                for (int i = 0; i < versions.size(); i++) { Replay.verify(versions.get(i), references.get(i)); comparisons++; }
            }
        }
        equal(comparisons, 88_560);
        System.out.println("  88560 retained-version/oracle checks across12 seeds; collisions, signed bits, depth7 and root compression");
    }
}
