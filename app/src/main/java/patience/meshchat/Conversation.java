package patience.meshchat;

/**
 * Conversation — Represents a chat thread (group or private).
 *
 * There is always exactly one group conversation (id = GROUP_ID).
 * Private conversations are keyed by the peer's node UUID.
 * Conversations are held in memory in MainActivity and accessed by fragments.
 */
public class Conversation {

    /** Special ID for the always-present group/broadcast channel */
    public static final String GROUP_ID = "group";

    /** Unique ID: GROUP_ID for group chat, or peer's node UUID for private */
    public final String id;

    /** UUID of the peer (null for group conversations) */
    public final String peerId;

    /** Display name shown in the conversation list */
    public String name;

    /** Whether this is the broadcast group or a private one-on-one thread */
    public final boolean isGroup;

    /** Preview of the last message sent or received in this thread */
    public String lastMessage;

    /** Timestamp of the last message (ms since epoch) */
    public long lastTimestamp;

    /** Number of messages received since the user last viewed this conversation */
    public int unreadCount;

    private Conversation(String id, String name, boolean isGroup) {
        this.id = id;
        this.peerId = isGroup ? null : id;
        this.name = name;
        this.isGroup = isGroup;
        this.lastMessage = "";
        this.lastTimestamp = System.currentTimeMillis();
        this.unreadCount = 0;
    }

    /** Creates the group broadcast conversation */
    public static Conversation createGroup() {
        return new Conversation(GROUP_ID, "Group Chat", true);
    }

    /** Creates a private conversation with a specific peer */
    public static Conversation createPrivate(String peerId, String peerName) {
        return new Conversation(peerId, peerName, false);
    }
}
