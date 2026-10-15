package io.github.kimgeonwoo718.roki.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun renameConversation(id: Long, title: String)

    @Query("UPDATE conversations SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: Long, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("SELECT * FROM turns WHERE conversation_id = :conversationId ORDER BY created_at, id")
    suspend fun turns(conversationId: Long): List<TurnEntity>

    @Upsert
    suspend fun upsertTurn(turn: TurnEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id = :id)")
    suspend fun conversationExists(id: Long): Boolean

    /** Skips the write when the chat was deleted meanwhile (avoids a foreign-key failure). */
    @Transaction
    suspend fun saveTurnIfConversationExists(turn: TurnEntity, touchAt: Long?) {
        if (!conversationExists(turn.conversationId)) return
        upsertTurn(turn)
        if (touchAt != null) touchConversation(turn.conversationId, touchAt)
    }

    /** Turns left mid-generation when the process died: they can no longer finish. */
    @Query("UPDATE turns SET status = 'STOPPED' WHERE status IN ('WAITING', 'STREAMING')")
    suspend fun markUnfinishedTurnsStopped()
}
