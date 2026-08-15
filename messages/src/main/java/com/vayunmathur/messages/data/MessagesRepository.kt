package com.vayunmathur.messages.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single owner of [MessagesDatabase] (messages.db).
 *
 * The single [buildMessagesDatabase] call site now lives here (via [RoomRepository.db]);
 * the top-level [buildMessagesDatabase] function delegates to this repository so existing
 * consumers keep compiling unchanged. New code should prefer [MessagesRepository.get] +
 * the suspend/Flow wrappers below instead of holding a DAO directly.
 */
class MessagesRepository private constructor(context: Context) :
    RoomRepository<MessagesDatabase>(context, MessagesDatabase::class, "messages.db") {

    private val conversationDao get() = db.conversationDao()
    private val messageDao get() = db.messageDao()

    // ------------------------------------------------------------------
    // ConversationDao wrappers
    // ------------------------------------------------------------------

    fun observeAll(): Flow<List<ConversationWithLastMessage>> = conversationDao.observeAll()

    fun observeConversation(id: String): Flow<Conversation?> = conversationDao.observe(id)

    suspend fun getConversation(id: String): Conversation? = conversationDao.get(id)

    suspend fun upsertConversation(conversation: Conversation) = conversationDao.upsert(conversation)

    suspend fun upsertConversations(conversations: List<Conversation>) = conversationDao.upsertAll(conversations)

    suspend fun markRead(id: String) = conversationDao.markRead(id)

    suspend fun deleteConversationById(id: String) = conversationDao.deleteById(id)

    suspend fun deleteConversationsForSource(source: MessageSource) = conversationDao.deleteAllForSource(source)

    // ------------------------------------------------------------------
    // MessageDao wrappers
    // ------------------------------------------------------------------

    fun observeForConversation(conversationId: String): Flow<List<Message>> =
        messageDao.observeForConversation(conversationId)

    suspend fun getMessage(id: String): Message? = messageDao.get(id)

    suspend fun upsertMessage(message: Message) = messageDao.upsert(message)

    suspend fun upsertMessages(messages: List<Message>) = messageDao.upsertAll(messages)

    suspend fun updateState(id: String, state: MessageState) = messageDao.updateState(id, state)

    suspend fun updateReactions(id: String, json: String?) = messageDao.updateReactions(id, json)

    suspend fun deleteMessageById(id: String) = messageDao.deleteById(id)

    suspend fun deleteMessagesForConvPrefix(prefix: String) = messageDao.deleteAllForConvPrefix(prefix)

    /** Direct database access for legacy call sites; prefer the wrappers above for new code. */
    fun database(): MessagesDatabase = db

    companion object {
        @Volatile
        private var instance: MessagesRepository? = null

        fun get(context: Context): MessagesRepository =
            instance ?: synchronized(this) {
                instance ?: MessagesRepository(context).also { instance = it }
            }
    }
}
