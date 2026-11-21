package lab;

import java.util.*;

/** Immutable 32-bit String.hashCode trie, consuming low five-bit chunks first. */
public final class Hamt {
    public static final int MAX_ENTRIES = 16_384;
    public static final int MIN_VALUE = -1_000_000, MAX_VALUE = 1_000_000;
    private sealed interface Node permits Leaf, Collision, Branch {}
    private record Leaf(int hash, String key, int value) implements Node {}
    private static final class Collision implements Node {
        final int hash; final Entry[] entries;
        Collision(int hash, Entry[] entries) { this.hash = hash; this.entries = entries.clone(); }
    }
    private static final class Branch implements Node {
        final int bitmap; final Node[] children;
        Branch(int bitmap, Node[] children) { this.bitmap = bitmap; this.children = children.clone(); }
    }
    public record Entry(String key, int value) {}
    public record Stats(int size, int nodes, int branches, int leaves, int collisions, int maxDepth) {}
    public record Sharing(int beforeNodes, int afterNodes, int sharedNodes, int newNodes, int retiredNodes, boolean sameRoot) {}
    public record NodeView(int id, String kind, String hashHex, String bitmapHex, List<Entry> entries, List<Integer> children) {
        public NodeView { entries = List.copyOf(entries); children = List.copyOf(children); }
    }
    public record VersionView(int version, int size, Integer root, List<Integer> reachable) { public VersionView { reachable = List.copyOf(reachable); } }
    public record Inspection(List<NodeView> nodes, List<VersionView> versions) { public Inspection { nodes = List.copyOf(nodes); versions = List.copyOf(versions); } }
    private final Node root; private final int size;
    private Hamt(Node root, int size) { this.root = root; this.size = size; }
    public static Hamt empty() { return new Hamt(null, 0); }
    public int size() { return size; }
    public static void validateKey(String key) {
        if (key == null || key.length() > 128) throw new IllegalArgumentException("Keys must be non-null, at most 128 UTF-16 code units");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i >= key.length() || !Character.isLowSurrogate(key.charAt(i))) throw new IllegalArgumentException("Key must contain well-formed UTF-16"); }
            else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Key must contain well-formed UTF-16");
        }
    }
    public Integer get(String key) { validateKey(key); return lookup(root, key.hashCode(), key, 0); }
    private static Integer lookup(Node node, int hash, String key, int shift) {
        if (node == null) return null;
        if (node instanceof Leaf leaf) return leaf.hash == hash && leaf.key.equals(key) ? leaf.value : null;
        if (node instanceof Collision collision) { if (collision.hash == hash) for (Entry entry : collision.entries) if (entry.key.equals(key)) return entry.value; return null; }
        Branch branch = (Branch) node; int bit = bit(hash, shift);
        return (branch.bitmap & bit) == 0 ? null : lookup(branch.children[index(branch.bitmap, bit)], hash, key, shift + 5);
    }
    public Hamt put(String key, int value) {
        validateKey(key); if (value < MIN_VALUE || value > MAX_VALUE) throw new IllegalArgumentException("Value outside [-1000000,1000000]");
        Integer prior = lookup(root, key.hashCode(), key, 0);
        if (prior != null && prior == value) return this;
        if (prior == null && size == MAX_ENTRIES) throw new IllegalStateException("Entry limit reached");
        return new Hamt(put(root, key.hashCode(), key, value, 0), size + (prior == null ? 1 : 0));
    }
    private static Node put(Node node, int hash, String key, int value, int shift) {
        if (node == null) return new Leaf(hash, key, value);
        if (node instanceof Leaf leaf) {
            if (leaf.key.equals(key)) return new Leaf(hash, key, value);
            if (leaf.hash == hash) return new Collision(hash, new Entry[] { new Entry(leaf.key, leaf.value), new Entry(key, value) });
            return merge(leaf, leaf.hash, new Leaf(hash, key, value), hash, shift);
        }
        if (node instanceof Collision collision) {
            if (collision.hash != hash) return merge(collision, collision.hash, new Leaf(hash, key, value), hash, shift);
            for (int i = 0; i < collision.entries.length; i++) if (collision.entries[i].key.equals(key)) { Entry[] changed = collision.entries.clone(); changed[i] = new Entry(key, value); return new Collision(hash, changed); }
            Entry[] extended = Arrays.copyOf(collision.entries, collision.entries.length + 1); extended[extended.length - 1] = new Entry(key, value); return new Collision(hash, extended);
        }
        Branch branch = (Branch) node; int bit = bit(hash, shift), at = index(branch.bitmap, bit);
        if ((branch.bitmap & bit) == 0) {
            Node[] children = new Node[branch.children.length + 1]; System.arraycopy(branch.children, 0, children, 0, at); children[at] = new Leaf(hash, key, value); System.arraycopy(branch.children, at, children, at + 1, branch.children.length - at);
            return new Branch(branch.bitmap | bit, children);
        }
        Node[] changed = branch.children.clone(); changed[at] = put(changed[at], hash, key, value, shift + 5); return new Branch(branch.bitmap, changed);
    }
    private static Node merge(Node left, int leftHash, Node right, int rightHash, int shift) {
        if (shift > 30 || leftHash == rightHash) throw new IllegalStateException("Distinct terminal hash merge invariant");
        int leftSlot = (leftHash >>> shift) & 31, rightSlot = (rightHash >>> shift) & 31;
        if (leftSlot == rightSlot) return new Branch(1 << leftSlot, new Node[] { merge(left, leftHash, right, rightHash, shift + 5) });
        return new Branch((1 << leftSlot) | (1 << rightSlot), leftSlot < rightSlot ? new Node[] { left, right } : new Node[] { right, left });
    }
    public Hamt remove(String key) {
        validateKey(key); if (lookup(root, key.hashCode(), key, 0) == null) return this;
        return new Hamt(remove(root, key.hashCode(), key, 0), size - 1);
    }
    private static Node remove(Node node, int hash, String key, int shift) {
        if (node instanceof Leaf) return null; // Public prelookup establishes presence.
        if (node instanceof Collision collision) {
            Entry[] kept = Arrays.stream(collision.entries).filter(entry -> !entry.key.equals(key)).toArray(Entry[]::new);
            return kept.length == 1 ? new Leaf(hash, kept[0].key, kept[0].value) : new Collision(hash, kept);
        }
        Branch branch = (Branch) node; int bit = bit(hash, shift), at = index(branch.bitmap, bit);
        Node child = remove(branch.children[at], hash, key, shift + 5); int bitmap = branch.bitmap; Node[] children;
        if (child == null) {
            bitmap &= ~bit; children = new Node[branch.children.length - 1]; System.arraycopy(branch.children, 0, children, 0, at); System.arraycopy(branch.children, at + 1, children, at, children.length - at);
        } else { children = branch.children.clone(); children[at] = child; }
        if (children.length == 0) return null;
        // A terminal carries the full hash and can move upward; a branch cannot skip a hash chunk.
        if (children.length == 1 && !(children[0] instanceof Branch)) return children[0];
        return new Branch(bitmap, children);
    }
    private static int bit(int hash, int shift) { if (shift > 30) throw new IllegalStateException("Hash depth exceeded"); return 1 << ((hash >>> shift) & 31); }
    private static int index(int bitmap, int bit) { return Integer.bitCount(bitmap & (bit - 1)); }
    public List<Entry> entries() {
        List<Entry> entries = new ArrayList<>(); collectEntries(root, entries); entries.sort(Comparator.comparing(Entry::key)); return List.copyOf(entries);
    }
    private static void collectEntries(Node node, List<Entry> out) {
        if (node instanceof Leaf leaf) out.add(new Entry(leaf.key, leaf.value));
        else if (node instanceof Collision collision) out.addAll(List.of(collision.entries));
        else if (node instanceof Branch branch) for (Node child : branch.children) collectEntries(child, out);
    }
    private static void collectNodes(Node node, Set<Node> out) { if (node == null || !out.add(node)) return; if (node instanceof Branch branch) for (Node child : branch.children) collectNodes(child, out); }
    private static Set<Node> identities(Node root) { Set<Node> nodes = Collections.newSetFromMap(new IdentityHashMap<>()); collectNodes(root, nodes); return nodes; }
    public Sharing sharingWith(Hamt after) {
        Objects.requireNonNull(after); Set<Node> beforeNodes = identities(root), afterNodes = identities(after.root); int shared = 0;
        for (Node node : beforeNodes) if (afterNodes.contains(node)) shared++;
        return new Sharing(beforeNodes.size(), afterNodes.size(), shared, afterNodes.size() - shared, beforeNodes.size() - shared, root == after.root);
    }
    public Stats stats() {
        Set<Node> nodes = identities(root); int branches = 0, leaves = 0, collisions = 0;
        for (Node node : nodes) { if (node instanceof Branch) branches++; else if (node instanceof Leaf) leaves++; else collisions++; }
        return new Stats(size, nodes.size(), branches, leaves, collisions, depth(root, 0));
    }
    private static int depth(Node node, int level) { if (node == null) return 0; int maximum = level; if (node instanceof Branch branch) for (Node child : branch.children) maximum = Math.max(maximum, depth(child, level + 1)); return maximum; }
    public static Inspection inspect(List<Hamt> maps) {
        if (maps == null || maps.isEmpty() || maps.size() > 256 || maps.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Inspect 1–256 non-null versions");
        IdentityHashMap<Node, Integer> ids = new IdentityHashMap<>(); List<Node> ordered = new ArrayList<>();
        for (Hamt map : maps) assign(map.root, ids, ordered);
        List<NodeView> nodes = new ArrayList<>();
        for (Node node : ordered) {
            int id = ids.get(node);
            if (node instanceof Leaf leaf) nodes.add(new NodeView(id, "leaf", hex(leaf.hash), "", List.of(new Entry(leaf.key, leaf.value)), List.of()));
            else if (node instanceof Collision collision) nodes.add(new NodeView(id, "collision", hex(collision.hash), "", List.of(collision.entries), List.of()));
            else { Branch branch = (Branch) node; nodes.add(new NodeView(id, "branch", "", hex(branch.bitmap), List.of(), Arrays.stream(branch.children).map(ids::get).toList())); }
        }
        List<VersionView> versions = new ArrayList<>();
        for (int i = 0; i < maps.size(); i++) { Hamt map = maps.get(i); versions.add(new VersionView(i, map.size, ids.get(map.root), identities(map.root).stream().map(ids::get).sorted().toList())); }
        return new Inspection(nodes, versions);
    }
    private static void assign(Node node, IdentityHashMap<Node, Integer> ids, List<Node> ordered) { if (node == null || ids.containsKey(node)) return; ids.put(node, ordered.size()); ordered.add(node); if (node instanceof Branch branch) for (Node child : branch.children) assign(child, ids, ordered); }
    private static String hex(int value) { return String.format(Locale.ROOT, "%08x", value); }
}
