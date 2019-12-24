package one.mixin.android.ui.media.pager

import android.content.Context
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.updateLayoutParams
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shizhefei.view.largeimage.LargeImageView
import one.mixin.android.R
import one.mixin.android.extension.displayRatio
import one.mixin.android.extension.inflate
import one.mixin.android.vo.MessageCategory
import one.mixin.android.vo.MessageItem
import one.mixin.android.widget.CircleProgress
import one.mixin.android.widget.PhotoView.DismissFrameLayout
import one.mixin.android.widget.PhotoView.PhotoView
import one.mixin.android.widget.PhotoView.PhotoViewAttacher
import one.mixin.android.widget.gallery.MimeType

class MediaPagerAdapter(
    private val context: Context,
    private val onDismissListener: DismissFrameLayout.OnDismissListener,
    private val onMediaPagerAdapterListener: MediaPagerAdapterListener
) : PagedListAdapter<MessageItem, MediaPagerHolder>(MessageItem.DIFF_CALLBACK) {

    var initialPos: Int = 0

    private val videoStatusCache = LruCache<String, String>(100)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaPagerHolder {
        val layout = DismissFrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        layout.setDismissListener(onDismissListener)
        val circleProgress = layout.inflate(R.layout.view_circle_progress) as CircleProgress
        circleProgress.updateLayoutParams<FrameLayout.LayoutParams> {
            gravity = Gravity.CENTER
        }
        circleProgress.id = R.id.circle_progress
        return when (viewType) {
            MediaItemType.LargeImage.ordinal -> {
                layout.addView(createLargeImageView(parent))
                layout.addView(circleProgress)
                LargeImageHolder(layout)
            }
            MediaItemType.Video.ordinal -> {
                layout.addView(createVideoView(parent))
                layout.addView(circleProgress)
                VideoHolder(layout, onMediaPagerAdapterListener)
            }
            else -> {
                layout.addView(createPhotoView(parent))
                layout.addView(circleProgress)
                PhotoHolder(layout)
            }
        }
    }

    override fun onBindViewHolder(holder: MediaPagerHolder, position: Int) {
        getItem(position)?.let { messageItem ->
            when (holder) {
                is PhotoHolder -> {
                    holder.bind(messageItem, position == initialPos, onMediaPagerAdapterListener)
                }
                is LargeImageHolder -> {
                    holder.bind(messageItem, position == initialPos, onMediaPagerAdapterListener)
                }
                is VideoHolder -> {
                    val videoHolder = holder.bind(messageItem, position == initialPos, videoStatusCache)
                    videoStatusCache.put(messageItem.messageId, messageItem.mediaStatus)
                    videoHolder
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        val messageItem = getItem(position) ?: return MediaItemType.Invalid.ordinal
        return if (messageItem.type == MessageCategory.SIGNAL_IMAGE.name ||
            messageItem.type == MessageCategory.PLAIN_IMAGE.name
        ) {
            if (!messageItem.mediaMimeType.equals(
                    MimeType.GIF.toString(),
                    true
                ) && messageItem.mediaHeight != null && messageItem.mediaWidth != null &&
                messageItem.mediaHeight / messageItem.mediaWidth.toFloat() > context.displayRatio() * 1.5f
            ) {
                MediaItemType.LargeImage.ordinal
            } else {
                MediaItemType.Image.ordinal
            }
        } else {
            MediaItemType.Video.ordinal
        }
    }

    private fun createVideoView(container: ViewGroup) = container.inflate(R.layout.item_pager_video_layout)

    private fun createLargeImageView(container: ViewGroup) = LargeImageView(container.context)

    private fun createPhotoView(parent: ViewGroup): PhotoView {
        val imageView = PhotoView(parent.context)
        val photoViewAttacher = PhotoViewAttacher(imageView)
        photoViewAttacher.isZoomable = false
        imageView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        return imageView
    }
}

interface MediaPagerAdapterListener {
    fun onClick(messageItem: MessageItem)

    fun onLongClick(messageItem: MessageItem, view: View)

    fun onCircleProgressClick(messageItem: MessageItem)

    fun onReadyPostTransition(view: View)

    fun switchToPin(messageItem: MessageItem, view: View)

    fun finishAfterTransition()
}

abstract class MediaPagerHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

enum class MediaItemType {
    Image, LargeImage, Video, Invalid
}