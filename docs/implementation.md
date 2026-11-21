# Trie invariants and measurement contract

## Immutable representation

`Hamt` owns a final root reference and size. A null root is empty. Nodes are private:

- Leaf: complete signed 32-bit hash, String key and integer value.
- Collision: one complete hash and a private copied Entry array containing at least two distinct keys with that hash.
- Branch: a 32-bit occupancy bitmap and private copied child array in ascending slot order.

Leaf/Entry records contain immutable Strings and primitive values. No internal array is returned. Branch and collision constructors copy supplied arrays. Updates may allocate temporary arrays as well as new nodes; the diagnostic counts concern reachable node objects, not all allocations.

The structure itself does not use a general-purpose map for key storage. IdentityHashMap-backed sets are diagnostic tools. HashMaps in replay/tests/measurement are separate reference maps or an explicit baseline.

## Routing

At shift `0,5,10,15,20,25,30`, compute `(hash >>> shift)&31`. A bitmap bit represents a logical slot; its packed index is the count of occupied lower slots. Slot 31 works even though the bit is negative as a signed int. Shifting beyond 30 is an invariant violation, not a wraparound to shift zero.

Each terminal's full hash must match every prefix chunk along its branch path. At shift 30 only slots 0–3 are possible. Collision nodes stop routing because all entries share the complete hash; equality still compares complete keys.

## Insertion and replacement

Public insertion validates the key/value and looks up the prior value first. An unchanged value returns the same Hamt instance. A missing key at capacity fails before modification. Otherwise a new wrapper receives a recursively copied path.

A leaf with the same key becomes a replacement leaf. A different key with the same hash creates a collision node. Distinct hashes merge through unary branches until their next chunks differ; that divergence produces a two-child bitmap node. A collision node merged with a different hash follows the same terminal-merge procedure. Its existing bucket can be shared intact below the new branch path.

At a branch, a new slot copies and expands the packed array. An existing slot copies the array and replaces only that child's path. Other child objects retain identity. Lookup-before-update means some paths are traversed twice; this cost is part of measured public operations.

## Removal and compression

Absent removal returns the original instance. Presence is established by the public lookup, so recursive removal follows a known matching path. Removing a leaf produces null. A collision bucket with one remaining entry becomes a leaf; its two-or-more case remains a collision node.

Empty branches disappear. A branch with one terminal child can return that terminal directly because the terminal validates its entire hash and key. A branch with one branch child remains: promoting that child would change its implicit hash shift. This rule is essential when retained keys share many low bits.

Removing the last key yields an empty root in a new map wrapper. An old version still references its original immutable nodes. There is no explicit reclamation or garbage-collection algorithm; ordinary JVM reachability determines object lifetime.

## Identity diagnostics

`sharingWith(after)` independently enumerates node identities reachable from each root. It records:

```text
shared  = |before identities ∩ after identities|
new     = |after identities| − shared
retired = |before identities| − shared
```

This verifies structural sharing directly. It is not inferred from matching key/value snapshots. No-op operations can share the exact root/map instance. “New” is relative to the selected parent: a node reachable in an independently constructed sibling is not automatically counted as shared.

`inspect(versions)` assigns one deterministic traversal label per actual object identity across that supplied version list. Public views contain immutable child-ID and Entry lists. Versions retain their exact root label and sorted reachable ID set. Recorded tests validate bitmap population count, slot-to-child order, hash prefix consistency, complete-hash collision equality and identity intersections.

Node counts exclude arrays, Entry objects, Strings, Integer boxes, Hamt wrappers and retaining lists. In particular a large collision bucket is one collision node but can contain many entries and require linear scans/copies. A small node count does not imply small byte usage or low latency.

## Reference and retained-version behavior

The reference oracle copies a Java HashMap from the same parent version and applies the corresponding operation. Every current and historical map is compared by size, sorted complete entries and lookup values. Random histories fork from prior versions, so testing covers sibling branches as well as linear update chains.

The recorded trace has 23 operations. The seed grid has 12×120 operations and retains 121 roots per seed. The benchmark histories retain 201 roots for each of three workloads. These finite checks do not prove correctness for every possible String or operation history.

## Benchmark conditions

The synthetic definitions and original CSV must agree before measurement. The three starting maps contain 128 ordinary keys, 1,024 ordinary keys and 64 full-hash-collision keys. Each update stream has 200 operations, including deterministic new keys, replacements and removes; every operation produces a retained logical version even when the root is unchanged. Reads target initial maps and include one deliberate miss per eight queries.

The full-copy baseline copies every parent HashMap, mutates the fresh copy once, and wraps it as unmodifiable for retention. It does not modify a parent and is not a persistent-map implementation. Copying on no-ops is an explicit workload policy, not a claim that it is the optimal HashMap-based strategy.

Both implementations retain all results through the timed loop. The result list then escapes through a volatile reference and its sizes contribute to an observed checksum. The previous batch reference is cleared before the next timing interval; no forced GC, CPU pinning, JVM-fork isolation or compilation control is used. GC can still affect later samples. Read checksums are consumed separately.

Identity graph enumeration, complete correctness checks and initial-map construction are outside measured intervals. Timing includes actual public HAMT validation/traversal/copying and baseline full-copy costs. Seven repetitions after two warm-ups, with alternating implementation order, provide local observations only. No confidence intervals, asymptotic inference, heap-byte estimates or production claims are derived from them.

## Input and provenance

The map's key contract is well-formed UTF-16 with at most 128 code units; exact equality/hash behavior follows Java String. There is no hash randomization, mixing or adversarial collision mitigation. Values are bounded primitive integers. All original operation keys are preserved as canonical Base64 of UTF-8 so embedded controls remain unambiguous.

Raw evidence, JVM timing samples and core source/input/test hashes are created by the Java driver. Optional browser/media files are outside those roots. A separate standard-library Python renderer hashes its own source, the consumed evidence and generated HTML/SVGs after LF normalization. A presentation-only edit does not replace a measurement run.
