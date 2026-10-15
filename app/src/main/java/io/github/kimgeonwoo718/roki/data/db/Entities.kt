package io.github.kimgeonwoo718.roki.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One chat in the archive (사이드바의 채팅 보관소). */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: Long,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** One exchange: the user's prompt and the model's answer (plus its reasoning and stats). */
@Entity(
    tableName = "turns",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversation_id")],
)
data class TurnEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    val prompt: String,
    val response: String,
    val thinking: String,
    val status: String,
    val error: String?,
    @ColumnInfo(name = "ttft_millis") val ttftMillis: Long?,
    @ColumnInfo(name = "thinking_millis") val thinkingMillis: Long?,
    @ColumnInfo(name = "tokens_per_second") val tokensPerSecond: Double?,
    val backend: String?,
    @ColumnInfo(name = "reasoning_level") val reasoningLevel: String?,
    val truncated: Boolean,
    val garbled: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
