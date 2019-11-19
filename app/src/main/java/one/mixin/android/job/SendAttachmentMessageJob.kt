package one.mixin.android.job

import android.net.Uri
import android.util.Log
import com.birbit.android.jobqueue.Params
import com.bugsnag.android.Bugsnag
import com.crashlytics.android.Crashlytics
import io.reactivex.disposables.Disposable
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import one.mixin.android.MixinApplication
import one.mixin.android.RxBus
import one.mixin.android.api.response.AttachmentResponse
import one.mixin.android.crypto.Base64
import one.mixin.android.crypto.Util
import one.mixin.android.crypto.attachment.AttachmentCipherOutputStreamFactory
import one.mixin.android.crypto.attachment.PushAttachmentData
import one.mixin.android.event.ProgressEvent
import one.mixin.android.util.GsonHelper
import one.mixin.android.util.Session
import one.mixin.android.vo.MediaStatus
import one.mixin.android.vo.Message
import one.mixin.android.vo.isVideo
import one.mixin.android.websocket.AttachmentMessagePayload
import one.mixin.android.widget.CircleProgress.Companion.STATUS_LOADING

class SendAttachmentMessageJob(val message: Message) : MixinJob(Params(PRIORITY_SEND_ATTACHMENT_MESSAGE)
    .addTags(message.id).groupBy("send_media_job").requireNetwork().persist(), message.id) {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Transient
    private var disposable: Disposable? = null

    override fun cancel() {
        isCancel = true
        connection?.disconnect()
        messageDao.updateMediaStatus(MediaStatus.CANCELED.name, message.id)
        disposable?.let {
            if (!it.isDisposed) {
                it.dispose()
            }
        }
        removeJob()
    }

    private val TAG = SendAttachmentMessageJob::class.java.simpleName

    override fun onAdded() {
        super.onAdded()
        if (message.isVideo()) {
            val mId = messageDao.findMessageIdById(message.id)
            if (mId != null) {
                messageDao.updateMediaSize(message.mediaSize ?: 0, mId)
            } else {
                messageDao.insert(message)
            }
        } else {
            messageDao.insert(message)
            messageDao.updateMediaStatus(MediaStatus.PENDING.name, message.id)
        }
    }

    override fun onCancel(cancelReason: Int, throwable: Throwable?) {
        super.onCancel(cancelReason, throwable)
        messageDao.updateMediaStatus(MediaStatus.CANCELED.name, message.id)
        removeJob()
    }

    override fun onRun() {
        if (isCancel) {
            removeJob()
            return
        }
        jobManager.saveJob(this)
        disposable = conversationApi.requestAttachment().map {
            if (it.isSuccess && !isCancel) {
                val result = it.data!!
                processAttachment(result)
            } else {
                false
            }
        }.subscribe({
            if (it) {
                messageDao.updateMediaStatus(MediaStatus.DONE.name, message.id)
                removeJob()
            } else {
                messageDao.updateMediaStatus(MediaStatus.CANCELED.name, message.id)
                removeJob()
            }
        }, {
            Log.e(TAG, "upload attachment error", it)
            Bugsnag.notify(it)
            Crashlytics.logException(it)
            messageDao.updateMediaStatus(MediaStatus.CANCELED.name, message.id)
            removeJob()
        })
    }

    private fun isPlain(): Boolean {
        return message.category.startsWith("PLAIN_")
    }

    private fun processAttachment(attachResponse: AttachmentResponse): Boolean {
        val key = if (isPlain()) {
            null
        } else {
            Util.getSecretBytes(64)
        }
        val inputStream = MixinApplication.appContext.contentResolver.openInputStream(Uri.parse(message.mediaUrl))
        val attachmentData =
            PushAttachmentData(message.mediaMimeType,
                inputStream,
                message.mediaSize!!,
                if (isPlain()) {
                    null
                } else {
                    AttachmentCipherOutputStreamFactory(key)
                },
                PushAttachmentData.ProgressListener { total, progress ->
                    RxBus.publish(ProgressEvent(message.id, progress.toFloat() / total.toFloat(), STATUS_LOADING))
                })
        val digest = try {
            if (isPlain()) {
                uploadPlainAttachment(attachResponse.upload_url!!, message.mediaSize, attachmentData)
                null
            } else {
                uploadAttachment(attachResponse.upload_url!!, attachmentData) // SHA256
            }
        } catch (e: Exception) {
            Bugsnag.notify(e)
            Crashlytics.logException(e)
            return false
        }
        if (isCancel) {
            removeJob()
            return true
        }
        val attachmentId = attachResponse.attachment_id
        val width = message.mediaWidth
        val height = message.mediaHeight
        val size = message.mediaSize
        val thumbnail = message.thumbImage
        val name = message.name
        val mimeType = message.mediaMimeType!!
        val duration = if (message.mediaDuration == null) null else message.mediaDuration.toLong()
        val waveform = message.mediaWaveform
        val transferMediaData = AttachmentMessagePayload(key, digest, attachmentId,
            mimeType, mimeType, size, name, width, height, thumbnail, duration, waveform)
        val plainText = GsonHelper.customGson.toJson(transferMediaData)
        val encoded = Base64.encodeBytes(plainText.toByteArray())
        message.content = encoded
        messageDao.updateMessageContent(encoded, message.id)
        jobManager.addJobInBackground(SendMessageJob(message, null, true))
        if (Session.getExtensionSessionId() != null) {
            jobManager.addJobInBackground(SendSessionMessageJob(message))
        }
        return true
    }

    @Transient
    private var connection: HttpsURLConnection? = null

    private fun uploadPlainAttachment(url: String, size: Long, attachment: PushAttachmentData) {
        connection = URL(url).openConnection() as HttpsURLConnection
        Util.uploadAttachment("PUT", connection, attachment.data,
            size, attachment.outputStreamFactory, attachment.listener)
    }

    private fun uploadAttachment(url: String, attachment: PushAttachmentData): ByteArray {
        val dataSize = attachment.outputStreamFactory.getCipherTextLength(attachment.dataSize)
        connection = URL(url).openConnection() as HttpsURLConnection
        return Util.uploadAttachment("PUT", connection, attachment.data,
            dataSize, attachment.outputStreamFactory, attachment.listener)
    }
}
