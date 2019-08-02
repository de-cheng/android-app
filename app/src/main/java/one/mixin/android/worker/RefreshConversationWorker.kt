package one.mixin.android.worker

import android.content.Context
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import kotlinx.coroutines.runBlocking
import one.mixin.android.MixinApplication
import one.mixin.android.RxBus
import one.mixin.android.api.service.ConversationService
import one.mixin.android.db.ConversationDao
import one.mixin.android.db.ParticipantDao
import one.mixin.android.db.UserDao
import one.mixin.android.db.insertConversation
import one.mixin.android.di.worker.ChildWorkerFactory
import one.mixin.android.event.GroupEvent
import one.mixin.android.extension.enqueueAvatarWorkRequest
import one.mixin.android.extension.enqueueOneTimeNetworkWorkRequest
import one.mixin.android.extension.putBoolean
import one.mixin.android.extension.sharedPreferences
import one.mixin.android.util.Session
import one.mixin.android.vo.ConversationBuilder
import one.mixin.android.vo.ConversationCategory
import one.mixin.android.vo.ConversationStatus
import one.mixin.android.vo.Participant
import one.mixin.android.vo.ParticipantRole
import one.mixin.android.worker.AvatarWorker.Companion.GROUP_ID

class RefreshConversationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val conversationApi: ConversationService,
    private val conversationDao: ConversationDao,
    private val userDao: UserDao,
    private val participantDao: ParticipantDao
) : BaseWork(context, parameters) {

    companion object {
        const val CONVERSATION_ID = "conversation_id"
        const val PREFERENCES_CONVERSATION = "preferences_conversation"
    }

    override fun onRun(): Result {
        val conversationId = inputData.getString(CONVERSATION_ID) ?: return Result.failure()
        val call = conversationApi.getConversation(conversationId).execute()
        val response = call.body()
        if (response != null && response.isSuccess) {
            response.data?.let { data ->
                var ownerId: String = data.creatorId
                if (data.category == ConversationCategory.CONTACT.name) {
                    ownerId = data.participants.find { it.userId != Session.getAccountId() }!!.userId
                }
                var c = conversationDao.findConversationById(data.conversationId)
                if (c == null) {
                    val builder = ConversationBuilder(data.conversationId,
                        data.createdAt, ConversationStatus.SUCCESS.ordinal)
                    c = builder.setOwnerId(ownerId)
                        .setCategory(data.category)
                        .setName(data.name)
                        .setIconUrl(data.iconUrl)
                        .setAnnouncement(data.announcement)
                        .setCodeUrl(data.codeUrl).build()
                    if (c.announcement.isNullOrBlank()) {
                        RxBus.publish(GroupEvent(data.conversationId))
                        applicationContext.sharedPreferences(PREFERENCES_CONVERSATION)
                            .putBoolean(data.conversationId, true)
                    }
                    runBlocking {
                        conversationDao.insertConversation(c)
                    }
                } else {
                    val status = if (data.participants.find { Session.getAccountId() == it.userId } != null) {
                        ConversationStatus.SUCCESS.ordinal
                    } else {
                        ConversationStatus.QUIT.ordinal
                    }
                    if (!data.announcement.isNullOrBlank() && c.announcement != data.announcement) {
                        RxBus.publish(GroupEvent(data.conversationId))
                        applicationContext.sharedPreferences(PREFERENCES_CONVERSATION)
                            .putBoolean(data.conversationId, true)
                    }
                    conversationDao.updateConversation(data.conversationId, ownerId, data.category, data.name,
                        data.announcement, data.muteUntil, data.createdAt, status)
                }

                val participants = mutableListOf<Participant>()
                val userIdList = mutableListOf<String>()
                for (p in data.participants) {
                    val item = Participant(conversationId, p.userId, p.role, p.createdAt!!)
                    if (p.role == ParticipantRole.OWNER.name) {
                        participants.add(0, item)
                    } else {
                        participants.add(item)
                    }

                    val u = userDao.findUser(p.userId)
                    if (u == null) {
                        userIdList.add(p.userId)
                    }
                }
                val local = participantDao.getRealParticipants(data.conversationId)
                val remoteIds = participants.map { it.userId }
                val needRemove = local.filter { !remoteIds.contains(it.userId) }
                if (needRemove.isNotEmpty()) {
                    participantDao.deleteList(needRemove)
                }
                participantDao.insertList(participants)
                if (userIdList.isNotEmpty()) {
                    WorkManager.getInstance(MixinApplication.appContext).enqueueOneTimeNetworkWorkRequest<RefreshUserWorker>(
                        workDataOf(RefreshUserWorker.USER_IDS to userIdList.toTypedArray(),
                            RefreshUserWorker.CONVERSATION_ID to conversationId))
                } else {
                    WorkManager.getInstance(MixinApplication.appContext).enqueueAvatarWorkRequest(
                        workDataOf(GROUP_ID to conversationId))
                }
            }
            return Result.success()
        } else {
            return Result.failure()
        }
    }

    @AssistedInject.Factory
    interface Factory : ChildWorkerFactory
}
