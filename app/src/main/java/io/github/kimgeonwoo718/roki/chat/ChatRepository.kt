package io.github.kimgeonwoo718.roki.chat

import android.util.Log
import io.github.kimgeonwoo718.roki.data.db.ChatDao
import io.github.kimgeonwoo718.roki.data.db.ConversationEntity
import io.github.kimgeonwoo718.roki.data.db.TurnEntity
import io.github.kimgeonwoo718.roki.llm.BackendKind
import io.github.kimgeonwoo718.roki.llm.LlmError
import io.github.kimgeonwoo718.roki.llm.ReasoningLevel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Persists the chat archive. Writes go through one serial dispatcher, so a later save of the same
 * turn can never be overtaken by an earlier one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepository(private val dao: ChatDao, private val scope: CoroutineScope) {
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)

    val conversations: Flow<List<ConversationEntity>> = dao.observeConversations()

    init {
        write { dao.markUnfinishedTurnsStopped() }
    }

    fun createConversation(id: Long, title: String, now: Long) {
        write { dao.insertConversation(ConversationEntity(id, title, now, now)) }
    }

    fun saveTurn(conversationId: Long, turn: ChatTurn, touch: Boolean) {
        val entity = turn.toEntity(conversationId)
        write { dao.saveTurnIfConversationExists(entity, if (touch) System.currentTimeMillis() else null) }
    }

    fun rename(id: Long, title: String) {
        write { dao.renameConversation(id, title) }
    }

    fun delete(id: Long) {
        write { dao.deleteConversation(id) }
    }

    /** The archive is best-effort: a failed write (e.g. a full disk) is logged, never a crash. */
    private fun write(block: suspend () -> Unit) {
        scope.launch(dbDispatcher) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Archive write failed", e)
            }
        }
    }

    suspend fun loadTurns(conversationId: Long): List<ChatTurn> =
        withContext(dbDispatcher) { dao.turns(conversationId).map { it.toTurn() } }

    private fun ChatTurn.toEntity(conversationId: Long) =
        TurnEntity(
            id = id,
            conversationId = conversationId,
            prompt = prompt,
            response = response,
            thinking = thinking,
            status = status.name,
            error = error?.name,
            ttftMillis = ttftMillis,
            thinkingMillis = thinkingMillis,
            tokensPerSecond = tokensPerSecond,
            backend = backend?.name,
            reasoningLevel = reasoningLevel?.name,
            truncated = truncated,
            garbled = garbled,
            createdAt = createdAt,
        )

    private fun TurnEntity.toTurn(): ChatTurn {
        val restoredStatus = runCatching { TurnStatus.valueOf(status) }.getOrDefault(TurnStatus.STOPPED)
        return ChatTurn(
            id = id,
            prompt = prompt,
            response = response,
            thinking = thinking,
            // A turn can only be active in the session that started it.
            status = if (restoredStatus == TurnStatus.WAITING || restoredStatus == TurnStatus.STREAMING) {
                TurnStatus.STOPPED
            } else restoredStatus,
            error = error?.let { runCatching { LlmError.valueOf(it) }.getOrNull() },
            ttftMillis = ttftMillis,
            thinkingMillis = thinkingMillis,
            tokensPerSecond = tokensPerSecond,
            backend = backend?.let { runCatching { BackendKind.valueOf(it) }.getOrNull() },
            reasoningLevel = reasoningLevel?.let { runCatching { ReasoningLevel.valueOf(it) }.getOrNull() },
            truncated = truncated,
            garbled = garbled,
            createdAt = createdAt,
        )
    }
}

private const val TAG = "ChatRepository"
