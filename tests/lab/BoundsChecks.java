package lab;

import java.util.*;
import static lab.CoreChecks.*;

public final class BoundsChecks {
    private BoundsChecks() {}
    public static void run() {
        Hamt allSlots = Hamt.empty(); for (int slot = 0; slot < 32; slot++) allSlots = allSlots.put(Fixtures.forHash(slot), slot);
        Hamt.Inspection inspection = Hamt.inspect(List.of(allSlots)); Hamt.NodeView root = inspection.nodes().get(inspection.versions().getFirst().root());
        equal(root.bitmapHex(), "ffffffff"); equal(root.children().size(), 32);
        for (int slot = 0; slot < 32; slot++) equal(allSlots.get(Fixtures.forHash(slot)), slot);
        Hamt atBound = Hamt.empty(); for (int i = 0; i < Hamt.MAX_ENTRIES; i++) atBound = atBound.put("capacity-" + i, i);
        equal(atBound.size(), Hamt.MAX_ENTRIES); final Hamt full = atBound;
        rejects(() -> full.put("new-entry", 1)); equal(full.put("capacity-0", -1).get("capacity-0"), -1); equal(full.get("capacity-0"), 0);
        equal(full.remove("capacity-0").put("new-entry", 1).size(), Hamt.MAX_ENTRIES);
        try { full.entries().clear(); throw new AssertionError("Mutable entry snapshot"); } catch (UnsupportedOperationException expected) { /* Snapshot remains immutable. */ }
        try { root.children().clear(); throw new AssertionError("Mutable node diagnostic"); } catch (UnsupportedOperationException expected) { /* Internal arrays are not exposed. */ }
        System.out.println("  All32 bitmap slots and16384-entry capacity, immutable diagnostic lists and replacement at capacity");
    }
}
