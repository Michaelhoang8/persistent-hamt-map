package lab;

import java.util.*;

public final class Fixtures {
    private Fixtures() {}
    public static String forHash(int hash) {
        long value = Integer.toUnsignedLong(hash); StringBuilder reversed = new StringBuilder();
        do { reversed.append((char) (value % 31)); value /= 31; } while (value != 0);
        String key = reversed.reverse().toString(); if (key.hashCode() != hash) throw new IllegalStateException("Hash fixture error"); return key;
    }
    public static List<String> collisions(int pairs) {
        if (pairs < 1 || pairs > 8) throw new IllegalArgumentException("Collision pair count must be 1–8");
        List<String> keys = new ArrayList<>();
        for (int mask = 0; mask < 1 << pairs; mask++) { StringBuilder key = new StringBuilder(); for (int bit = 0; bit < pairs; bit++) key.append((mask & (1 << bit)) == 0 ? "Aa" : "BB"); keys.add(key.toString()); }
        return List.copyOf(keys);
    }
    public record Operation(int parent, String operation, String key, Integer value) {}
    public static List<Operation> trace() {
        List<Operation> trace = new ArrayList<>();
        trace.add(new Operation(0, "put", "alpha", 1));
        trace.add(new Operation(1, "put", "beta", 2));
        trace.add(new Operation(2, "put", "gamma", 3));
        trace.add(new Operation(3, "put", "beta", 20));
        trace.add(new Operation(3, "put", "delta", 4));
        trace.add(new Operation(4, "remove", "alpha", null));
        trace.add(new Operation(3, "remove", "absent", null));
        trace.add(new Operation(3, "put", "beta", 2));
        trace.add(new Operation(0, "put", "Aa", 10));
        trace.add(new Operation(9, "put", "BB", 11));
        trace.add(new Operation(10, "put", "Aa", 12));
        trace.add(new Operation(10, "put", "Cc", 13));
        trace.add(new Operation(12, "remove", "Cc", null));
        trace.add(new Operation(13, "remove", "Aa", null));
        trace.add(new Operation(14, "remove", "BB", null));
        trace.add(new Operation(0, "put", forHash(1), 101));
        trace.add(new Operation(16, "put", forHash(0x40000001), 102));
        trace.add(new Operation(17, "put", forHash(0x80000001), 103));
        trace.add(new Operation(18, "remove", forHash(0x40000001), null));
        trace.add(new Operation(19, "remove", forHash(0x80000001), null));
        trace.add(new Operation(20, "remove", forHash(1), null));
        trace.add(new Operation(3, "put", "", -1));
        trace.add(new Operation(22, "put", "🧪", 7));
        return List.copyOf(trace);
    }
}
