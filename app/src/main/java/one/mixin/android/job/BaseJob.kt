package one.mixin.android.job

import com.birbit.android.jobqueue.CancelReason
import com.birbit.android.jobqueue.Job
import com.birbit.android.jobqueue.Params
import com.birbit.android.jobqueue.RetryConstraint
import com.bugsnag.android.Bugsnag
import com.bugsnag.android.MetaData
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import one.mixin.android.api.ClientErrorException
import one.mixin.android.api.LocalJobException
import one.mixin.android.api.NetworkException
import one.mixin.android.api.ServerErrorException
import one.mixin.android.api.WebSocketException
import one.mixin.android.api.service.AccountService
import one.mixin.android.api.service.AddressService
import one.mixin.android.api.service.AssetService
import one.mixin.android.api.service.ContactService
import one.mixin.android.api.service.ConversationService
import one.mixin.android.api.service.MessageService
import one.mixin.android.api.service.SignalKeyService
import one.mixin.android.api.service.UserService
import one.mixin.android.crypto.SignalProtocol
import one.mixin.android.db.AddressDao
import one.mixin.android.db.AssetDao
import one.mixin.android.db.ConversationDao
import one.mixin.android.db.HyperlinkDao
import one.mixin.android.db.JobDao
import one.mixin.android.db.MessageDao
import one.mixin.android.db.MessageHistoryDao
import one.mixin.android.db.MixinDatabase
import one.mixin.android.db.OffsetDao
import one.mixin.android.db.ParticipantDao
import one.mixin.android.db.SentSenderKeyDao
import one.mixin.android.db.SnapshotDao
import one.mixin.android.db.StickerAlbumDao
import one.mixin.android.db.StickerDao
import one.mixin.android.db.StickerRelationshipDao
import one.mixin.android.db.TopAssetDao
import one.mixin.android.db.UserDao
import one.mixin.android.di.AppComponent
import one.mixin.android.di.Injectable
import one.mixin.android.repository.AssetRepository
import one.mixin.android.repository.UserRepository
import one.mixin.android.vo.LinkState
import one.mixin.android.websocket.ChatWebSocket

abstract class BaseJob(params: Params) : Job(params), Injectable {

    @Inject
    @Transient
    lateinit var jobManager: MixinJobManager
    @Inject
    @Transient
    lateinit var conversationApi: ConversationService
    @Inject
    @Transient
    lateinit var userService: UserService
    @Inject
    @Transient
    lateinit var contactService: ContactService
    @Inject
    @Transient
    lateinit var signalKeyService: SignalKeyService
    @Inject
    @Transient
    lateinit var messageService: MessageService
    @Inject
    @Transient
    lateinit var assetService: AssetService
    @Inject
    @Transient
    lateinit var accountService: AccountService
    @Inject
    @Transient
    lateinit var addressService: AddressService
    @Inject
    @Transient
    lateinit var messageDao: MessageDao
    @Inject
    @Transient
    lateinit var messageHistoryDao: MessageHistoryDao
    @Inject
    @Transient
    lateinit var userDao: UserDao
    @Inject
    @Transient
    lateinit var conversationDao: ConversationDao
    @Inject
    @Transient
    lateinit var participantDao: ParticipantDao
    @Inject
    @Transient
    lateinit var offsetDao: OffsetDao
    @Inject
    @Transient
    lateinit var assetDao: AssetDao
    @Inject
    @Transient
    lateinit var snapshotDao: SnapshotDao
    @Inject
    @Transient
    lateinit var sentSenderKeyDao: SentSenderKeyDao
    @Inject
    @Transient
    lateinit var chatWebSocket: ChatWebSocket
    @Inject
    @Transient
    lateinit var userRepo: UserRepository
    @Inject
    @Transient
    lateinit var assetRepo: AssetRepository
    @Inject
    @Transient
    lateinit var stickerDao: StickerDao
    @Inject
    @Transient
    lateinit var hyperlinkDao: HyperlinkDao
    @Inject
    @Transient
    lateinit var stickerAlbumDao: StickerAlbumDao
    @Inject
    @Transient
    lateinit var stickerRelationshipDao: StickerRelationshipDao
    @Inject
    @Transient
    lateinit var addressDao: AddressDao
    @Inject
    @Transient
    lateinit var topAssetDao: TopAssetDao
    @Inject
    @Transient
    lateinit var jobDao: JobDao
    @Inject
    @Transient
    lateinit var signalProtocol: SignalProtocol
    @Transient
    @Inject
    lateinit var appDatabase: MixinDatabase
    @Transient
    @Inject
    lateinit var linkState: LinkState

    open fun shouldRetry(throwable: Throwable): Boolean {
        if (throwable is SocketTimeoutException) {
            return true
        }
        if (throwable is IOException) {
            return true
        }
        return (throwable as? ServerErrorException)?.shouldRetry()
            ?: ((throwable as? ClientErrorException)?.shouldRetry()
                ?: ((throwable as? NetworkException)?.shouldRetry()
                ?: ((throwable as? WebSocketException)?.shouldRetry()
                    ?: ((throwable as? LocalJobException)?.shouldRetry() ?: false))))
    }

    fun inject(appComponent: AppComponent) {
        appComponent.inject(this)
    }

    public override fun shouldReRunOnThrowable(throwable: Throwable, runCount: Int, maxRunCount: Int): RetryConstraint {
        if (runCount >= 100) {
            val metaData = MetaData()
            metaData.addToTab("Job", "shouldReRunOnThrowable", "Retry max count:$runCount")
            Bugsnag.notify(throwable, metaData)
        }
        return if (shouldRetry(throwable)) {
            RetryConstraint.RETRY
        } else {
            RetryConstraint.CANCEL
        }
    }

    override fun onAdded() {
    }

    override fun onCancel(cancelReason: Int, throwable: Throwable?) {
        if (cancelReason == CancelReason.REACHED_RETRY_LIMIT) {
            throwable?.let {
                val metaData = MetaData()
                metaData.addToTab("Job", "CancelReason", "REACHED_RETRY_LIMIT")
                Bugsnag.notify(it, metaData)
            }
        }
    }

    override fun getRetryLimit(): Int {
        return Integer.MAX_VALUE
    }

    companion object {
        private const val serialVersionUID = 1L

        const val PRIORITY_UI_HIGH = 20
        const val PRIORITY_SEND_MESSAGE = 18
        const val PRIORITY_SEND_ATTACHMENT_MESSAGE = 17
        const val PRIORITY_SEND_SESSION_MESSAGE = 16
        const val PRIORITY_RECEIVE_MESSAGE = 15
        const val PRIORITY_BACKGROUND = 10
        const val PRIORITY_DELIVERED_ACK_MESSAGE = 7
        const val PRIORITY_ACK_MESSAGE = 5
    }
}
