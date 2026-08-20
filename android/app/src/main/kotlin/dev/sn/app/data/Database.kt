package dev.sn.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// -- entities -------------------------------------------------------------

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val title: String = "",
)

/**
 * One message, stored as its wire form.
 *
 * `payload` is the serialized [dev.sn.core.ChatMessage], which keeps tool calls
 * and results intact for replay; the flat columns exist so the UI can render a
 * conversation without deserializing every row.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val timestamp: Long,
    val role: String,
    val text: String,
    val payload: String,
)

/** A notification as captured from the status bar. */
@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    /** Set once the proactive pass has considered this notification. */
    val triagedAt: Long? = null,
    /** True when triage decided it was worth interrupting the user. */
    val flagged: Boolean = false,
)

/** Every tool call the agent made, and what came of it. */
@Entity(tableName = "audit")
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val conversationId: Long,
    val tool: String,
    val arguments: String,
    val decision: String,
    val summary: String,
    val failed: Boolean,
)

// -- data access ----------------------------------------------------------

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("SELECT * FROM conversations ORDER BY id DESC LIMIT 1")
    suspend fun latest(): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<ConversationEntity>>

    @Query("UPDATE conversations SET title = :title WHERE id = :id AND title = ''")
    suspend fun setTitleIfUnset(id: Long, title: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun stream(conversationId: Long): Flow<List<MessageEntity>>

    /** Newest first; callers reverse. Used to build the model's replay window. */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY id DESC LIMIT :limit",
    )
    suspend fun recent(conversationId: Long, limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clear(conversationId: Long)
}

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(notification: NotificationEntity): Long

    @Query(
        "SELECT * FROM notifications WHERE postedAt >= :since " +
            "AND (:packageName IS NULL OR packageName LIKE '%' || :packageName || '%') " +
            "ORDER BY postedAt DESC LIMIT :limit",
    )
    suspend fun since(since: Long, packageName: String?, limit: Int): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE triagedAt IS NULL AND postedAt >= :since ORDER BY postedAt ASC")
    suspend fun untriaged(since: Long): List<NotificationEntity>

    @Query("UPDATE notifications SET triagedAt = :now, flagged = :flagged WHERE id IN (:ids)")
    suspend fun markTriaged(ids: List<Long>, now: Long, flagged: Boolean)

    @Query("DELETE FROM notifications WHERE postedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM notifications")
    suspend fun count(): Int
}

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(entry: AuditEntity)

    @Query("SELECT * FROM audit ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<AuditEntity>>

    @Query("DELETE FROM audit WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        NotificationEntity::class,
        AuditEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SnDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun notifications(): NotificationDao
    abstract fun audit(): AuditDao

    companion object {
        @Volatile
        private var instance: SnDatabase? = null

        fun get(context: Context): SnDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SnDatabase::class.java,
                "sn.db",
            ).build().also { instance = it }
        }
    }
}
