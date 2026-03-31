package patience.meshchat;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * ============================================================================
 * MeshChatDatabase — Room Database for MeshChat Persistent Storage
 * ============================================================================
 *
 * WHAT IS ROOM? (for beginners)
 * ─────────────────────────────
 * Room is Google's recommended SQLite wrapper for Android. It provides:
 *   - Compile-time SQL verification (typos become build errors)
 *   - Automatic mapping between Java objects and database rows
 *   - Thread-safe access patterns
 *
 * This database currently holds one table:
 *   - queued_messages: Store-and-forward queue for undelivered messages
 *
 * SINGLETON PATTERN:
 * ──────────────────
 * Only ONE database instance should ever exist per process. Multiple
 * instances would cause locking conflicts and data corruption.
 * getInstance() uses double-checked locking to ensure thread safety.
 *
 * ============================================================================
 */
@Database(entities = {QueuedMessage.class, ChatMessageEntity.class}, version = 2, exportSchema = false)
public abstract class MeshChatDatabase extends RoomDatabase {

    /** The single DAO for queued message operations */
    public abstract QueuedMessageDao queuedMessageDao();

    /** DAO for chat messages — exposes Flow queries for Compose collectAsState() */
    public abstract ChatMessageDao chatMessageDao();

    // ─── Singleton ──────────────────────────────────────────────────────

    private static volatile MeshChatDatabase INSTANCE;

    /**
     * Returns the singleton database instance, creating it if needed.
     *
     * Uses double-checked locking for thread-safe lazy initialization.
     * The database file is stored at: /data/data/patience.meshchat/databases/meshchat_db
     *
     * @param context Application or Service context (NOT Activity — to avoid leaks)
     * @return The singleton MeshChatDatabase
     */
    @NonNull
    public static MeshChatDatabase getInstance(@NonNull Context context) {
        if (INSTANCE == null) {
            synchronized (MeshChatDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    MeshChatDatabase.class,
                                    "meshchat_db")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
