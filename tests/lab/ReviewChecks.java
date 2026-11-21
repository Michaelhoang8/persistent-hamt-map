package lab;

import java.util.*;

/** Independent collision family: empty and NUL-only strings all have hash zero. */
public final class ReviewChecks {
    private ReviewChecks() {}
    public static void run() {
        List<Hamt> retained = new ArrayList<>();
        Hamt current = Hamt.empty();
        retained.add(current);
        for (int length = 0; length <= 128; length++) {
            String key = "\0".repeat(length);
            if (key.hashCode() != 0) throw new AssertionError("Independent hash fixture");
            current = current.put(key, length - 64);
            retained.add(current);
        }
        for (int version = 0; version < retained.size(); version++) {
            Hamt map = retained.get(version);
            if (map.size() != version) throw new AssertionError("Version size");
            for (int length = 0; length <= 128; length++) {
                Integer expected = length < version ? length - 64 : null;
                if (!Objects.equals(map.get("\0".repeat(length)), expected))
                    throw new AssertionError("Retained full-hash collision version");
            }
        }
        Hamt original = current;
        Hamt replaced = current.put("", 999);
        if (replaced.size() != 129 || original.get("") != -64 || replaced.get("") != 999)
            throw new AssertionError("Replacement must retain old collision array");
        for (int length = 128; length >= 1; length--) current = current.remove("\0".repeat(length));
        if (current.size() != 1 || current.stats().leaves() != 1 || current.stats().collisions() != 0)
            throw new AssertionError("Collision must collapse into a leaf");
        Hamt expanded = current.put("\0", 7);
        if (expanded.size() != 2 || current.get("\0") != null || expanded.get("\0") != 7)
            throw new AssertionError("Re-expansion must preserve old leaf");
        Hamt empty = current.remove("");
        if (empty.size() != 0 || empty.stats().nodes() != 0 || empty.get("") != null)
            throw new AssertionError("Final removal must produce an empty root");
        if (original.size() != 129 || original.get("\0".repeat(128)) != 64)
            throw new AssertionError("Deletion mutated a retained version");
        System.out.println("  Independent zero-hash family:129 keys,130 retained versions, replacement/collapse/re-expansion");
    }
}
