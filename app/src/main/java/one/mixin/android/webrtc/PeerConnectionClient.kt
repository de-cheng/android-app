package one.mixin.android.webrtc

import android.content.Context
import android.util.Log
import com.crashlytics.android.Crashlytics
import java.util.concurrent.Executors
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.StatsReport
import timber.log.Timber

class PeerConnectionClient(private val context: Context, private val events: PeerConnectionEvents) {
    private val executor = Executors.newSingleThreadExecutor()
    private var factory: PeerConnectionFactory? = null
    private var isError = false

    init {
        executor.execute {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
            )
        }
    }

    private val pcObserver = PCObserver()
    private val iceServers = arrayListOf<PeerConnection.IceServer>()
    var isInitiator = false
    private var remoteCandidateCache = arrayListOf<IceCandidate>()
    private var remoteSdpCache: SessionDescription? = null
    private var peerConnection: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private val sdpConstraint = MediaConstraints()

    fun createPeerConnectionFactory(options: PeerConnectionFactory.Options) {
        if (factory != null) {
            reportError("PeerConnectionFactory has already been constructed")
            return
        }
        executor.execute { createPeerConnectionFactoryInternal(options) }
    }

    fun createOffer(iceServerList: List<PeerConnection.IceServer>) {
        iceServers.addAll(iceServerList)
        executor.execute {
            val peerConnection = createPeerConnectionInternal()
            isInitiator = true
            val offerSdpObserver = object : SdpObserverWrapper() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    peerConnection?.setLocalDescription(object : SdpObserverWrapper() {
                        override fun onSetFailure(error: String?) {
                            reportError("createOffer setLocalSdp onSetFailure error: $error")
                        }

                        override fun onSetSuccess() {
                            Timber.d("createOffer setLocalSdp onSetSuccess")
                            events.onLocalDescription(sdp)
                        }
                    }, sdp)
                }

                override fun onCreateFailure(error: String?) {
                    reportError("createOffer onCreateFailure error: $error")
                }
            }
            peerConnection?.createOffer(offerSdpObserver, sdpConstraint)
        }
    }

    fun createAnswer(iceServerList: List<PeerConnection.IceServer>, remoteSdp: SessionDescription) {
        iceServers.addAll(iceServerList)
        executor.execute {
            val peerConnection = createPeerConnectionInternal()
            peerConnection?.setRemoteDescription(remoteSdpObserver, remoteSdp)
            isInitiator = false
            val answerSdpObserver = object : SdpObserverWrapper() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    peerConnection?.setLocalDescription(object : SdpObserverWrapper() {
                        override fun onSetFailure(error: String?) {
                            reportError("createAnswer setLocalSdp onSetFailure error: $error")
                        }

                        override fun onSetSuccess() {
                            Timber.d("createAnswer setLocalSdp onSetSuccess")
                            events.onLocalDescription(sdp)
                        }
                    }, sdp)
                }

                override fun onCreateFailure(error: String?) {
                    reportError("createAnswer onCreateFailure error: $error")
                }
            }
            peerConnection?.createAnswer(answerSdpObserver, sdpConstraint)
        }
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        executor.execute {
            if (peerConnection != null && peerConnection!!.remoteDescription != null) {
                peerConnection!!.addIceCandidate(candidate)
            } else {
                remoteCandidateCache.add(candidate)
            }
        }
    }

    fun removeRemoteIcetCandidate(candidates: Array<IceCandidate>) {
        executor.execute {
            if (peerConnection == null || isError) return@execute

            drainCandidatesAndSdp()
            peerConnection!!.removeIceCandidates(candidates)
        }
    }

    fun setAnswerSdp(sdp: SessionDescription) {
        executor.execute {
            if (peerConnection != null) {
                peerConnection!!.setRemoteDescription(remoteSdpObserver, sdp)
            }
        }
    }

    fun setAudioEnable(enable: Boolean) {
        executor.execute {
            if (peerConnection == null || audioTrack == null || isError) return@execute

            audioTrack!!.setEnabled(enable)
        }
    }

    fun enableCommunication() {
        executor.execute {
            if (peerConnection == null || isError) return@execute

            peerConnection!!.setAudioPlayout(true)
            peerConnection!!.setAudioRecording(true)
        }
    }

    fun hasLocalSdp(): Boolean {
        if (peerConnection == null) return false

        return peerConnection!!.localDescription != null
    }

    fun close() {
        executor.execute {
            peerConnection?.dispose()
            peerConnection = null
            audioSource?.dispose()
            audioSource = null
            factory?.dispose()
            factory = null
            events.onPeerConnectionClosed()
        }
    }

    private fun reportError(error: String) {
        executor.execute {
            Crashlytics.log(Log.ERROR, "WebRTC connection error", error)
            peerConnection?.let { pc ->
                val localSdp = "{ localDescription: { description: ${pc.localDescription.description}, type: ${pc.localDescription.type} }"
                val remoteSdp = "{ remoteDescription: { description: ${pc.remoteDescription.description}, type: ${pc.remoteDescription.type} }"
                pc.getStats { report ->
                    Crashlytics.log(Log.ERROR, "WebRTC peer connection error",
                        """
                            { stats: $report },
                            $localSdp,
                            $remoteSdp
                        """
                    )
                }
            }
            if (!isError) {
                events.onPeerConnectionError(error)
                isError = true
            }
        }
    }

    private fun createPeerConnectionInternal(): PeerConnection? {
        if (factory == null || isError) {
            reportError("PeerConnectionFactory is not created")
            return null
        }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            enableDtlsSrtp = true
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peerConnection = factory!!.createPeerConnection(rtcConfig, pcObserver)
        if (peerConnection == null) {
            reportError("PeerConnection is not created")
            return null
        }
        peerConnection!!.setAudioPlayout(false)
        peerConnection!!.setAudioRecording(false)

        peerConnection!!.addTrack(createAudioTrack())
        return peerConnection
    }

    private fun createPeerConnectionFactoryInternal(options: PeerConnectionFactory.Options) {
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    private fun drainCandidatesAndSdp() {
        if (peerConnection == null) return

        remoteCandidateCache.forEach {
            peerConnection!!.addIceCandidate(it)
            remoteCandidateCache.clear()
        }
        if (remoteSdpCache != null && peerConnection!!.remoteDescription == null) {
            peerConnection!!.setRemoteDescription(remoteSdpObserver, remoteSdpCache)
            remoteSdpCache = null
        }
    }

    private fun createAudioTrack(): AudioTrack {
        audioSource = factory!!.createAudioSource(MediaConstraints())
        audioTrack = factory!!.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        audioTrack!!.setEnabled(true)
        return audioTrack!!
    }

    private val remoteSdpObserver = object : SdpObserverWrapper() {
        override fun onSetFailure(error: String?) {
            reportError("setRemoteSdp onSetFailure error: $error")
        }

        override fun onSetSuccess() {
            remoteCandidateCache.forEach {
                peerConnection?.addIceCandidate(it)
            }
            remoteCandidateCache.clear()
            Timber.d("setRemoteSdp onSetSuccess")
        }
    }

    private inner class PCObserver : PeerConnection.Observer {

        override fun onIceCandidate(candidate: IceCandidate) {
            executor.execute { events.onIceCandidate(candidate) }
        }

        override fun onDataChannel(dataChannel: DataChannel?) {
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            Timber.d("onIceConnectionChange: $newState")
            executor.execute {
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED -> events.onIceConnected()
                    PeerConnection.IceConnectionState.DISCONNECTED -> events.onIceDisconnected()
                    PeerConnection.IceConnectionState.FAILED -> events.onIceConnectedFailed()
                    else -> {
                    }
                }
            }
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
            Timber.d("onIceGatheringChange: $newState")
        }

        override fun onAddStream(stream: MediaStream) {
            Timber.d("onAddStream")
            stream.audioTracks.forEach { it.setEnabled(true) }
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) {
            Timber.d("SignalingState: $newState")
        }

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
            Timber.d("onIceCandidatesRemoved")
            executor.execute { events.onIceCandidatesRemoved(candidates) }
        }

        override fun onRemoveStream(stream: MediaStream) {
            stream.videoTracks[0].dispose()
        }

        override fun onRenegotiationNeeded() {
        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
        }
    }

    private open class SdpObserverWrapper : SdpObserver {
        override fun onSetFailure(error: String?) {
        }

        override fun onSetSuccess() {
        }

        override fun onCreateSuccess(sdp: SessionDescription) {
        }

        override fun onCreateFailure(error: String?) {
        }
    }

    /**
     * Peer connection events.
     */
    interface PeerConnectionEvents {
        /**
         * Callback fired once local SDP is created and set.
         */
        fun onLocalDescription(sdp: SessionDescription)

        /**
         * Callback fired once local Ice candidate is generated.
         */
        fun onIceCandidate(candidate: IceCandidate)

        /**
         * Callback fired once local ICE candidates are removed.
         */
        fun onIceCandidatesRemoved(candidates: Array<IceCandidate>)

        /**
         * Callback fired once connection is established (IceConnectionState is
         * CONNECTED).
         */
        fun onIceConnected()

        /**
         * Callback fired once connection is closed (IceConnectionState is
         * DISCONNECTED).
         */
        fun onIceDisconnected()

        /**
         * Callback fired once connection is closed (IceConnectionState is
         * FAILED).
         */
        fun onIceConnectedFailed()

        /**
         * Callback fired once peer connection is closed.
         */
        fun onPeerConnectionClosed()

        /**
         * Callback fired once peer connection statistics is ready.
         */
        fun onPeerConnectionStatsReady(reports: Array<StatsReport>)

        /**
         * Callback fired once peer connection error happened.
         */
        fun onPeerConnectionError(description: String)
    }

    companion object {
        const val TAG = "PeerConnectionClient"

        private const val AUDIO_TRACK_ID = "ARDAMSa0"
    }
}
