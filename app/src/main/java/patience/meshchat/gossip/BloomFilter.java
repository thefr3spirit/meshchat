package patience.meshchat.gossip;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * ============================================================================
 * BloomFilter — Space-efficient Probabilistic Set Membership Test
 * ============================================================================
 *
 * HOW A BLOOM FILTER WORKS (for beginners):
 * ──────────────────────────────────────────
 * A Bloom filter is like a very compact "I've seen this before" detector.
 *
 * Instead of storing every message ID you've received (which would take
 * a lot of memory), a Bloom filter uses a fixed-size bit array and
 * multiple hash functions:
 *
 *   ADD("msg-123"):
 *     hash1("msg-123") → bit 42   →  set bit 42 to 1
 *     hash2("msg-123") → bit 107  →  set bit 107 to 1
 *     hash3("msg-123") → bit 5    →  set bit 5 to 1
 *
 *   CONTAINS("msg-123"):
 *     hash1 → bit 42 = 1? ✓
 *     hash2 → bit 107 = 1? ✓
 *     hash3 → bit 5 = 1? ✓
 *     All set → "probably yes"
 *
 *   CONTAINS("msg-999"):
 *     hash1 → bit 200 = 0? ✗
 *     → "definitely no"
 *
 * KEY PROPERTIES:
 *   - False positives possible (says "yes" when answer is "no")
 *   - FALSE NEGATIVES IMPOSSIBLE (if it says "no", it's truly absent)
 *   - Very compact: ~1.2 KB for 1000 items at 1% false-positive rate
 *
 * WHY THIS MATTERS FOR MESHCHAT:
 * ──────────────────────────────
 * Each node periodically sends its Bloom filter (compact!) to all peers.
 * A receiving peer checks its own message IDs against the filter:
 *   - If the filter says "no" → the sender is MISSING that message
 *   - The receiver can then push the missing message to the sender
 *
 * This achieves eventual consistency without expensive full-set comparison.
 *
 * SIZING:
 *   expectedItems = 1024, falsePositiveRate = 0.01 (1%)
 *   → bitSize ≈ 9830 bits ≈ 1.2 KB  (very compact over BLE/WiFi)
 *   → numHashes = 7
 *
 * ============================================================================
 */
public class BloomFilter implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The bit array stored as a byte array */
    private final byte[] bits;

    /** Total number of bits in the filter */
    private final int bitSize;

    /** Number of hash functions to use (k) */
    private final int numHashes;

    /** Number of items added so far */
    private int count;

    // ═══════════════════════════════════════════════════════════════════
    // CONSTRUCTION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates a Bloom filter with optimal sizing.
     *
     * OPTIMAL FORMULAS (from the Bloom filter math):
     *   m = -(n * ln(p)) / (ln(2))²     (optimal bit count)
     *   k = (m / n) * ln(2)              (optimal hash count)
     *
     * where n = expectedItems, p = falsePositiveRate
     *
     * @param expectedItems       how many unique items you plan to add
     * @param falsePositiveRate   acceptable false-positive rate (e.g. 0.01 = 1%)
     */
    public BloomFilter(int expectedItems, double falsePositiveRate) {
        // Calculate optimal bit size
        this.bitSize = optimalBitSize(expectedItems, falsePositiveRate);

        // Calculate optimal number of hash functions
        this.numHashes = optimalNumHashes(expectedItems, bitSize);

        // Allocate the bit array (ceiling division for bytes)
        this.bits = new byte[(bitSize + 7) / 8];
        this.count = 0;
    }

    /**
     * Creates a Bloom filter from raw bytes (for deserialization).
     *
     * @param bits       the bit array
     * @param bitSize    total number of valid bits
     * @param numHashes  number of hash functions
     */
    public BloomFilter(byte[] bits, int bitSize, int numHashes) {
        this.bits = bits.clone();
        this.bitSize = bitSize;
        this.numHashes = numHashes;
        this.count = -1; // unknown when reconstructed from bytes
    }

    // ═══════════════════════════════════════════════════════════════════
    // CORE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Adds an item (message ID) to the filter.
     *
     * Sets k bits to 1, one for each hash function.
     * Once a bit is set, it can never be unset (no removal support).
     */
    public void add(String item) {
        long hash64 = hash(item);
        int h1 = (int) hash64;
        int h2 = (int) (hash64 >>> 32);

        for (int i = 0; i < numHashes; i++) {
            // Double hashing: combines two independent hashes
            // to generate k hash values without computing k separate hashes.
            // hash_i = (h1 + i * h2) mod m
            int combinedHash = h1 + (i * h2);
            int bitIndex = (combinedHash & Integer.MAX_VALUE) % bitSize;
            setBit(bitIndex);
        }
        count++;
    }

    /**
     * Tests whether an item MIGHT be in the filter.
     *
     * @return true  if all k bits are set → "probably contains" (may be false positive)
     *         false if any bit is 0     → "definitely does NOT contain"
     */
    public boolean mightContain(String item) {
        long hash64 = hash(item);
        int h1 = (int) hash64;
        int h2 = (int) (hash64 >>> 32);

        for (int i = 0; i < numHashes; i++) {
            int combinedHash = h1 + (i * h2);
            int bitIndex = (combinedHash & Integer.MAX_VALUE) % bitSize;
            if (!getBit(bitIndex)) {
                return false; // Definitely not in the set
            }
        }
        return true; // Probably in the set
    }

    // ═══════════════════════════════════════════════════════════════════
    // SERIALIZATION — for network transmission
    // ═══════════════════════════════════════════════════════════════════

    /** Returns the raw bit array for network transmission */
    public byte[] toByteArray() { return bits.clone(); }

    /** Returns total number of bits */
    public int getBitSize() { return bitSize; }

    /** Returns number of hash functions */
    public int getNumHashes() { return numHashes; }

    /** Returns number of items added (or -1 if reconstructed from bytes) */
    public int getCount() { return count; }

    // ═══════════════════════════════════════════════════════════════════
    // INTERNAL — Bit manipulation and hashing
    // ═══════════════════════════════════════════════════════════════════

    private void setBit(int index) {
        bits[index / 8] |= (1 << (index % 8));
    }

    private boolean getBit(int index) {
        return (bits[index / 8] & (1 << (index % 8))) != 0;
    }

    /**
     * Produces a 64-bit hash using SHA-256 (truncated to 8 bytes).
     *
     * SHA-256 provides excellent distribution and avalanche properties,
     * ensuring minimal correlation between the two 32-bit halves we
     * extract for double hashing.
     */
    private long hash(String item) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(item.getBytes(StandardCharsets.UTF_8));
            // Extract first 8 bytes as a long
            return ((long)(digest[0] & 0xFF) << 56)
                 | ((long)(digest[1] & 0xFF) << 48)
                 | ((long)(digest[2] & 0xFF) << 40)
                 | ((long)(digest[3] & 0xFF) << 32)
                 | ((long)(digest[4] & 0xFF) << 24)
                 | ((long)(digest[5] & 0xFF) << 16)
                 | ((long)(digest[6] & 0xFF) <<  8)
                 | ((long)(digest[7] & 0xFF));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available on Android
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPTIMAL SIZING FORMULAS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Optimal bit count: m = -(n * ln(p)) / (ln(2))²
     *
     * For n=1024, p=0.01: m ≈ 9830 → ~1.2 KB
     */
    private static int optimalBitSize(int n, double p) {
        return (int) Math.ceil(-(n * Math.log(p)) / (Math.log(2) * Math.log(2)));
    }

    /**
     * Optimal hash count: k = (m/n) * ln(2)
     *
     * For n=1024, m=9830: k ≈ 7
     */
    private static int optimalNumHashes(int n, int m) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }
}
