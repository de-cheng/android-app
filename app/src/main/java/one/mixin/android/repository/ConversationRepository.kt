package one.mixin.android.repository

import android.annotation.SuppressLint
import androidx.lifecycle.LiveData
import io.reactivex.Observable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import one.mixin.android.api.request.ConversationRequest
import one.mixin.android.api.service.ConversationService
import one.mixin.android.db.AppDao
import one.mixin.android.db.ConversationDao
import one.mixin.android.db.JobDao
import one.mixin.android.db.MessageDao
import one.mixin.android.db.MessageProvider
import one.mixin.android.db.MixinDatabase
import one.mixin.android.db.ParticipantDao
import one.mixin.android.db.batchMarkReadAndTake
import one.mixin.android.db.insertConversation
import one.mixin.android.util.SINGLE_DB_THREAD
import one.mixin.android.util.Session
import one.mixin.android.vo.ChatMinimal
import one.mixin.android.vo.Conversation
import one.mixin.android.vo.ConversationCategory
import one.mixin.android.vo.ConversationItem
import one.mixin.android.vo.ConversationStatus
import one.mixin.android.vo.Job
import one.mixin.android.vo.Message
import one.mixin.android.vo.MessageItem
import one.mixin.android.vo.MessageMinimal
import one.mixin.android.vo.Participant
import one.mixin.android.vo.SearchMessageItem
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository
@Inject
internal constructor(
    private val appDatabase: MixinDatabase,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val participantDao: ParticipantDao,
    private val appDao: AppDao,
    private val jobDao: JobDao,
    private val conversationService: ConversationService
) {

    @SuppressLint("RestrictedApi")
    fun getMessages(conversationId: String) = MessageProvider.getMessages(conversationId, appDatabase)

    fun conversation(): LiveData<List<ConversationItem>> = conversationDao.conversationList()

    fun successConversationList(): LiveData<List<ConversationItem>> = conversationDao.successConversationList()

    fun insertConversation(conversation: Conversation, participants: List<Participant>) {
        GlobalScope.launch(Dispatchers.IO) {
            conversationDao.insertConversation(conversation) {
                participantDao.insertList(participants)
            }
        }
    }

    fun syncInsertConversation(conversation: Conversation, participants: List<Participant>) {
        GlobalScope.launch(Dispatchers.IO) {
            conversationDao.insertConversation(conversation) {
                participantDao.insertList(participants)
            }
        }
    }

    fun getConversationById(conversationId: String): LiveData<Conversation> =
        conversationDao.getConversationById(conversationId)

    fun findConversationById(conversationId: String): Observable<Conversation> = Observable.just(conversationId).map {
        conversationDao.findConversationById(conversationId)
    }

    fun searchConversationById(conversationId: String) = conversationDao.searchConversationById(conversationId)

    fun findMessageById(messageId: String) = messageDao.findMessageById(messageId)

    suspend fun saveDraft(conversationId: String, draft: String) {
        conversationDao.saveDraft(conversationId, draft)
    }

    fun getConversation(conversationId: String) = conversationDao.getConversation(conversationId)

    suspend fun fuzzySearchMessage(query: String, limit: Int): List<SearchMessageItem> =
        messageDao.fuzzySearchMessage(query, limit)

    fun fuzzySearchMessageDetail(query: String, conversationId: String) =
        messageDao.fuzzySearchMessageByConversationId(query, conversationId)

    suspend fun fuzzySearchChat(query: String): List<ChatMinimal> = conversationDao.fuzzySearchChat(query)

    suspend fun indexUnread(conversationId: String) = conversationDao.indexUnread(conversationId)

    suspend fun getMediaMessages(conversationId: String): List<MessageItem> =
        messageDao.getMediaMessages(conversationId)

    fun getConversationIdIfExistsSync(recipientId: String) =
        conversationDao.getConversationIdIfExistsSync(recipientId)

    fun getUnreadMessage(conversationId: String, accountId: String): List<MessageMinimal>? {
        return messageDao.getUnreadMessage(conversationId, accountId)
    }

    fun updateCodeUrl(conversationId: String, codeUrl: String) {
        GlobalScope.launch(SINGLE_DB_THREAD) {
            conversationDao.updateCodeUrl(conversationId, codeUrl)
        }
    }

    fun getGroupParticipants(conversationId: String) = participantDao.getParticipants(conversationId)

    fun getGroupParticipantsLiveData(conversationId: String) =
        participantDao.getGroupParticipantsLiveData(conversationId)

    suspend fun updateMediaStatus(status: String, messageId: String) =
        messageDao.updateMediaStatusSuspend(status, messageId)

    fun deleteMessage(id: String) = messageDao.deleteMessage(id)

    fun deleteConversationById(conversationId: String) {
        GlobalScope.launch(SINGLE_DB_THREAD) {
            conversationDao.deleteConversationById(conversationId)
        }
    }

    fun updateConversationPinTimeById(conversationId: String, pinTime: String?) {
        GlobalScope.launch(SINGLE_DB_THREAD) {
            conversationDao.updateConversationPinTimeById(conversationId, pinTime)
        }
    }

    fun deleteMessageByConversationId(conversationId: String) {
        GlobalScope.launch(SINGLE_DB_THREAD) {
            messageDao.deleteMessageByConversationId(conversationId)
        }
    }

    suspend fun getRealParticipants(conversationId: String) = participantDao.getRealParticipantsSuspend(conversationId)

    fun getGroupConversationApp(conversationId: String) =
        appDao.getGroupConversationApp(conversationId)

    fun getConversationApp(userId: String?) = appDao.getConversationApp(userId)

    fun updateAsync(conversationId: String, request: ConversationRequest) =
        conversationService.updateAsync(conversationId, request)

    fun updateAnnouncement(conversationId: String, announcement: String) {
        GlobalScope.launch(SINGLE_DB_THREAD) {
            conversationDao.updateConversationAnnouncement(conversationId, announcement)
        }
    }

    fun getLimitParticipants(conversationId: String, limit: Int) =
        participantDao.getLimitParticipants(conversationId, limit)

    fun findParticipantByIds(conversationId: String, userId: String) =
        participantDao.findParticipantByIds(conversationId, userId)

    fun getParticipantsCount(conversationId: String) = participantDao.getParticipantsCount(conversationId)

    fun getStorageUsage(conversationId: String) = conversationDao.getStorageUsage(conversationId)

    fun getConversationStorageUsage() = conversationDao.getConversationStorageUsage()

    fun getMediaByConversationIdAndCategory(conversationId: String, category: String) = messageDao
        .getMediaByConversationIdAndCategory(conversationId, category)

    suspend fun findMessageIndex(conversationId: String, messageId: String) =
        messageDao.findMessageIndex(conversationId, messageId)

    fun findUnreadMessagesSync(conversationId: String) = messageDao.findUnreadMessagesSync(conversationId)

    fun batchMarkReadAndTake(conversationId: String, userId: String, createdAt: String) {
        GlobalScope.launch(Dispatchers.IO) {
            messageDao.batchMarkReadAndTake(conversationId, userId, createdAt)
        }
    }

    fun insertList(it: List<Job>) {
        jobDao.insertList(it)
    }

    fun refreshConversation(conversationId: String) {
        try {
            val call = conversationService.getConversation(conversationId).execute()
            val response = call.body()
            if (response != null && response.isSuccess) {
                response.data?.let { conversationData ->
                    val status =
                        if (conversationData.participants.find { Session.getAccountId() == it.userId } != null) {
                            ConversationStatus.SUCCESS.ordinal
                        } else {
                            ConversationStatus.QUIT.ordinal
                        }
                    var ownerId: String = conversationData.creatorId
                    if (conversationData.category == ConversationCategory.CONTACT.name) {
                        ownerId = conversationData.participants.find { it.userId != Session.getAccountId() }!!.userId
                    }
                    conversationDao.updateConversation(
                        conversationData.conversationId, ownerId, conversationData.category, conversationData.name,
                        conversationData.announcement, conversationData.muteUntil, conversationData.createdAt, status
                    )
                }
            }
        } catch (e: IOException) {
        }
    }

    fun insertMessage(message: Message) {
        messageDao.insert(message)
    }

    suspend fun findFirstUnreadMessageId(conversationId: String, userId: String): String? =
        messageDao.findFirstUnreadMessageId(conversationId, userId)

    suspend fun findLastMessage(conversationId: String) = messageDao.findLastMessage(conversationId)

    suspend fun findUnreadMessageByMessageId(conversationId: String, userId: String, messageId: String) =
        messageDao.findUnreadMessageByMessageId(conversationId, userId, messageId)

    suspend fun isSilence(conversationId: String, userId: String): Int = messageDao.isSilence(conversationId, userId)

    suspend fun findNextAudioMessage(conversationId: String, createdAt: String, messageId: String) =
        messageDao.findNextAudioMessage(conversationId, createdAt, messageId)
}
