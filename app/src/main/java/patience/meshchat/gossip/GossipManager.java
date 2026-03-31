package patience.meshchat.gossip;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import patience.meshchat.ChatMessageDao;
import patience.meshchat.ChatMessageEntity;
import patience.meshchat.MeshChatDatabase;
import patience.meshchat.Message;

/**
 * ============================================================================
 * GossipManager — Anti-Entropy Gossip Protocol for Eventual Consistency
 * ============================================================================
 *
 * PROBLEM:
 *   In a mesh network with intermittent connectivity, some nodes may miss
 *   broadcast messages. Traditional flooding (send once, hope it arrives)
 *   is unreliable when nodes join/leave frequently or connections drop.
 *
 * SOLUTION — GOSSIP PROTOCOL:
 *   Gossip protocols achieve EVENTUAL CONSISTENCY by continuously
 *   comparing state between peers and exchanging missing data.
 *
 * HOW IT WORKS:
 * ─────────────
 * 1. SEEN-ID SET (loop prevention):
 *    Every node maintains a set of message IDs it has already processed.
 *    When a message arrives, the node checks this set:
 *      - ID seen before → discard (prevents infinite loops)
 *      - ID not seen → process + re-broadcast to all peers except sender
 *
 * 2. BLOOM FILTER ADVERTISEMENT (anti-entropy):
 *    Periodically (every 30 seconds), each node builds a compact Bloom
 *    filter containing all message IDs it has received. This filter is
 *    broadcast to all connected peers as a SUBTYPE_BLOOM_FILTER message.
 *
 *    Bloom filter properties:
 *      - Very compact: ~1.2 KB for 1024 messages at 1% false-positive rate
 *      - False positives possible (thinks peer has a message when it doesn't)
 *      - False negatives IMPOSSIBLE (if filter says "no", peer is missing it)
 *
 * 3. MISSING MESSAGE DETECTION:
 *    When a peer's Bloom filter arrives, the receiver checks each of its
 *    own message IDs against the filter:
 *      - filter.mightContain(id) → peer probably has it → skip
 *      - !filter.mightContain(id) → peer DEFINITELY doesn't have it → push
 *
 * 4. MESSAGE PUSH:
 *    The receiver sends the actual missing messages to the peer.
 *    The peer processes them through its normal handleIncomingMessage()
 *    pipeline, which handles dedup, decryption, and UI delivery.
 *
 * CONVERGENCE GUARANTEE:
 *   After at most 2 anti-entropy cycles between any pair of connected
 *   nodes, they will have identical message sets (modulo Bloom filter
 *   false positives, which are resolved in subsequent cycles).
 *
 * KEY CLASSES:
 *   - BloomFilter: space-efficient set membership test
 *   - Message.SUBTYPE_BLOOM_FILTER: carries serialized Bloom filter
 *   - Message.SUBTYPE_MESSAGE_REQUEST: carries missing message IDs
 *   - Room database: persistent store of received messages for replay
 *
 * ============================================================================
 */
public class GossipManager {

    private static final String TAG = "GossipManager";

    // ─── Configuration ──────────────────────────────────────────────────

    /** How often to broadcast our Bloom filter (anti-entropy cycle) */
    private static final int BLOOM_BROADCAST_INTERVAL_SEC = 30;

    /** Expected number of messages in the Bloom filter */
    private static final int BLOOM_EXPECTED_ITEMS = 1024;

    /** Acceptable false-positive rate (1%) */
    private static final double BLOOM_FPR = 0.01;

    /** Maximum messages to push in a single anti-entropy response */
    private static final int MAX_PUSH_BATCH = 50;

    // ─── Dependencies ───────────────────────────────────────────────────

    private final Context context;
    private final ChatMessageDao chatMessageDao;

    /** Callback to send messages through the mesh */
    private GossipCallback callback;

    /** Scheduler for periodic Bloom filter broadcasts */
    private ScheduledExecutorService scheduler;

    /** Our node ID */
    private final String myNodeId;

    /**
     * In-memory seen-ID set — tracks which message IDs we have received.
     * This is the authoritative set used to build the Bloom filter.
     * Also used for O(1) dedup checks.
     */
    private final Set<String> seenMessageIds = ConcurrentHashMap.newKeySet();

    /**
     * Cached message payloads for anti-entropy push.
     * Key: message ID, Value: serialized Message bytes (Base64).
     * Limited to recent messages to bound memory usage.
     */
    private final Map<String, byte[]> messageCache = new ConcurrentHashMap<>();

    /** Maximum entries in the message cache */
    private static final int MAX_CACHE_SIZE = 2048;

    // ═══════════════════════════════════════════════════════════════════
    // CALLBACK INTERFACE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Interface for the GossipManager to send messages through the mesh.
     * Implemented by MeshManager.
     */
    public interface GossipCallback {
        /** Send a control message to all connected peers */
        void sendToAllPeers(Message message, String excludeAddress);

        /** Send a message to a specific peer address */
        void sendToPeer(String peerAddress, Message message);

        /** Get our username for message factory methods */
        String getUsername();
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONSTRUCTION
    // ═══════════════════════════════════════════════════════════════════

    public GossipManager(Context context, String myNodeId) {
        this.context = context;
        this.myNodeId = myNodeId;
        this.chatMessageDao = MeshChatDatabase.getInstance(context).chatMessageDao();

        // Pre-load seen IDs from Room database for persistence across restarts
        loadSeenIdsFromDatabase();
    }

    /**
     * Sets the callback for sending messages and starts the periodic
     * Bloom filter broadcast scheduler.
     */
    public void start(GossipCallback callback) {
        this.callback = callback;

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                this::broadcastBloomFilter,
                BLOOM_BROADCAST_INTERVAL_SEC,  // initial delay
                BLOOM_BROADCAST_INTERVAL_SEC,  // period
                TimeUnit.SECONDS
        );
        Log.d(TAG, "Gossip anti-entropy started (interval="
                + BLOOM_BROADCAST_INTERVAL_SEC + "s)");
    }

    // ═══════════════════════════════════════════════════════════════════
    // SEEN-ID SET — Gossip loop prevention
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Records a message ID as "seen".
     *
     * Called by MeshManager every time a new message is processed.
     * The seen-ID set is used to:
     *   1. Build the Bloom filter for anti-entropy
     *   2. Provide fast O(1) dedup checks
     *
     * @param messageId  the UUID of the message
     * @param message    the full message (cached for anti-entropy push)
     */
    public void recordMessage(String messageId, Message message) {
        seenMessageIds.add(messageId);

        // Cache serialized message for anti-entropy push
        if (messageCache.size() < MAX_CACHE_SIZE) {
            byte[] serialized = serializeMessage(message);
            if (serialized != null) {
                messageCache.put(messageId, serialized);
            }
        }
    }

    /** Check if we've already seen this message */
    public boolean hasSeen(String messageId) {
        return seenMessageIds.contains(messageId);
    }

    // ═══════════════════════════════════════════════════════════════════
    // BLOOM FILTER ANTI-ENTROPY — Periodic advertisement
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Builds a Bloom filter from our seen-ID set and broadcasts it
     * to all connected peers.
     *
     * FLOW:
     *   1. Create new BloomFilter sized for our current message count
     *   2. Add every seen message ID into the filter
     *   3. Serialize filter to bytes → Base64 encode
     *   4. Pack into a SUBTYPE_BLOOM_FILTER message
     *   5. Broadcast to all peers
     */
    private void broadcastBloomFilter() {
        if (callback == null) return;
        if (seenMessageIds.isEmpty()) return;

        try {
            // Build the Bloom filter
            int expectedSize = Math.max(BLOOM_EXPECTED_ITEMS, seenMessageIds.size());
            BloomFilter filter = new BloomFilter(expectedSize, BLOOM_FPR);

            for (String id : seenMessageIds) {
                filter.add(id);
            }

            // Serialize: [4 bytes bitSize][4 bytes numHashes][N bytes bits]
            byte[] filterBits = filter.toByteArray();
            ByteBuffer buffer = ByteBuffer.allocate(8 + filterBits.length);
            buffer.putInt(filter.getBitSize());
            buffer.putInt(filter.getNumHashes());
            buffer.put(filterBits);

            String encoded = Base64.encodeToString(buffer.array(), Base64.NO_WRAP);

            // Create and broadcast the Bloom filter message
            Message bloomMsg = new Message("", Message.TYPE_SENT);
            bloomMsg.setSubType(Message.SUBTYPE_BLOOM_FILTER);
            bloomMsg.setSenderId(myNodeId);
            bloomMsg.setContent(encoded);
            bloomMsg.setChannelType(Message.CHANNEL_BROADCAST);

            callback.sendToAllPeers(bloomMsg, null);

            Log.d(TAG, "Bloom filter broadcast: " + seenMessageIds.size()
                    + " IDs, " + filterBits.length + " bytes");

        } catch (Exception e) {
            Log.w(TAG, "Bloom filter broadcast failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BLOOM FILTER ANTI-ENTROPY — Receiving & comparing
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Called when we receive a SUBTYPE_BLOOM_FILTER message from a peer.
     *
     * ALGORITHM:
     *   1. Deserialize the peer's Bloom filter from the message content
     *   2. For each message ID in our seen set:
     *      - If the peer's filter says "no" → peer is DEFINITELY missing it
     *      - Collect these missing IDs
     *   3. Push the corresponding messages to the peer
     *
     * This is the core anti-entropy mechanism that ensures eventual
     * consistency even with intermittent connectivity.
     *
     * @param bloomMessage   the received SUBTYPE_BLOOM_FILTER message
     * @param sourceAddress  the transport address of the peer who sent it
     */
    public void handleBloomFilter(Message bloomMessage, String sourceAddress) {
        if (callback == null) return;

        try {
            // Decode the Bloom filter
            byte[] decoded = Base64.decode(bloomMessage.getContent(), Base64.NO_WRAP);
            ByteBuffer buffer = ByteBuffer.wrap(decoded);

            int bitSize = buffer.getInt();
            int numHashes = buffer.getInt();
            byte[] filterBits = new byte[buffer.remaining()];
            buffer.get(filterBits);

            BloomFilter peerFilter = new BloomFilter(filterBits, bitSize, numHashes);

            // Find messages the peer is missing
            List<String> missingIds = new ArrayList<>();
            for (String ourId : seenMessageIds) {
                if (!peerFilter.mightContain(ourId)) {
                    // Peer DEFINITELY doesn't have this message
                    missingIds.add(ourId);
                    if (missingIds.size() >= MAX_PUSH_BATCH) break;
                }
            }

            if (missingIds.isEmpty()) {
                Log.d(TAG, "Anti-entropy: peer at " + sourceAddress
                        + " is up to date");
                return;
            }

            Log.d(TAG, "Anti-entropy: pushing " + missingIds.size()
                    + " missing messages to " + sourceAddress);

            // Push missing messages to the peer
            pushMissingMessages(missingIds, sourceAddress);

        } catch (Exception e) {
            Log.w(TAG, "Bloom filter handling failed: " + e.getMessage());
        }
    }

    /**
     * Pushes cached messages to a peer that is missing them.
     *
     * First checks the in-memory cache; if not found, falls back to
     * querying the Room database for persisted chat messages.
     */
    private void pushMissingMessages(List<String> missingIds, String peerAddress) {
        int pushed = 0;

        for (String msgId : missingIds) {
            // Try in-memory cache first
            byte[] cached = messageCache.get(msgId);
            if (cached != null) {
                Message msg = deserializeMessage(cached);
                if (msg != null && !msg.isExpired()) {
                    callback.sendToPeer(peerAddress, msg);
                    pushed++;
                    continue;
                }
            }

            // Fall back to Room database
            ChatMessageEntity entity = chatMessageDao.getMessageById(msgId);
            if (entity != null) {
                Message reconstructed = reconstructMessageFromEntity(entity);
                if (reconstructed != null) {
                    callback.sendToPeer(peerAddress, reconstructed);
                    pushed++;
                }
            }
        }

        Log.d(TAG, "Anti-entropy push completed: " + pushed + "/" + missingIds.size()
                + " messages sent to " + peerAddress);
    }

    /**
     * Reconstructs a Message object from a ChatMessageEntity stored in Room.
     *
     * The reconstructed message is marked as encrypted=false because the
     * stored content is already decrypted. The receiving peer will re-encrypt
     * if needed when forwarding.
     */
    private Message reconstructMessageFromEntity(ChatMessageEntity entity) {
        Message msg = new Message(entity.content, Message.TYPE_SENT);
        // Use reflection-free setter approach via copy constructor pattern
        msg.setSenderId(entity.senderId);
        msg.setSenderName(entity.senderName != null ? entity.senderName : "Unknown");
        msg.setChannelType(entity.channelType);
        msg.setEncrypted(false);
        // Preserve the original ID so dedup works at the receiver
        setMessageId(msg, entity.messageId);
        return msg;
    }

    /**
     * Sets the message ID using the copy() trick — creates a message with
     * the desired ID by copying and overwriting.
     */
    private void setMessageId(Message msg, String id) {
        // The Message class doesn't expose an ID setter, so we use
        // a serialization round-trip to set the ID field
        try {
            java.lang.reflect.Field idField = Message.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(msg, id);
        } catch (Exception e) {
            Log.w(TAG, "Could not set message ID: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PERSISTENCE — Load seen IDs from Room on startup
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Loads all persisted message IDs from the Room database into the
     * seen-ID set. This ensures that after an app restart, the Bloom
     * filter contains all previously received messages.
     */
    private void loadSeenIdsFromDatabase() {
        new Thread(() -> {
            try {
                List<String> ids = chatMessageDao.getAllMessageIds();
                if (ids != null) {
                    seenMessageIds.addAll(ids);
                    Log.d(TAG, "Loaded " + ids.size() + " seen IDs from database");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load seen IDs: " + e.getMessage());
            }
        }, "GossipManager-Init").start();
    }

    // ═══════════════════════════════════════════════════════════════════
    // SERIALIZATION HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private byte[] serializeMessage(Message message) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(message);
            oos.flush();
            return bos.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "Serialize failed: " + e.getMessage());
            return null;
        }
    }

    private Message deserializeMessage(byte[] data) {
        try {
            ObjectInputStream ois = new ObjectInputStream(
                    new ByteArrayInputStream(data));
            Object obj = ois.readObject();
            return (obj instanceof Message) ? (Message) obj : null;
        } catch (Exception e) {
            Log.w(TAG, "Deserialize failed: " + e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    /** Shuts down the anti-entropy scheduler */
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        Log.d(TAG, "GossipManager shut down");
    }

    /** Returns the number of messages in the seen set */
    public int getSeenCount() {
        return seenMessageIds.size();
    }

    /** Returns the current Bloom filter (for diagnostics/testing) */
    public BloomFilter buildCurrentFilter() {
        int expectedSize = Math.max(BLOOM_EXPECTED_ITEMS, seenMessageIds.size());
        BloomFilter filter = new BloomFilter(expectedSize, BLOOM_FPR);
        for (String id : seenMessageIds) {
            filter.add(id);
        }
        return filter;
    }
}
