package com.example.tv_caller_app.calling.webrtc

import org.webrtc.PeerConnection

object WebRTCConfig {

    data class ManagedIceServer(
        val urls: List<String>,
        val username: String? = null,
        val credential: String? = null
    )

    @Volatile private var managedIceServers: List<ManagedIceServer> = emptyList()

    fun updateManagedIceServers(servers: List<ManagedIceServer>) {
        managedIceServers = servers.filter { it.urls.isNotEmpty() }
        android.util.Log.i("WebRTCConfig", "Loaded ${managedIceServers.size} managed ICE server groups")
    }

    val stunServers = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun2.l.google.com:19302",
        "stun:stun3.l.google.com:19302",
        "stun:stun4.l.google.com:19302"
    )

    object TurnServerConfig {
        const val USERNAME = "openrelayproject"
        const val CREDENTIAL = "openrelayproject"

        val servers = listOf(
            "turn:openrelay.metered.ca:80?transport=udp",
            "turn:openrelay.metered.ca:80?transport=tcp",
            "turn:openrelay.metered.ca:443?transport=tcp",
            "turns:openrelay.metered.ca:443?transport=tcp"
        )
    }

    fun createRTCConfiguration(): PeerConnection.RTCConfiguration {
        val iceServers = mutableListOf<PeerConnection.IceServer>()

        val managed = managedIceServers
        if (managed.isNotEmpty()) {
            managed.forEach { server ->
                server.urls.forEach { url ->
                    val builder = PeerConnection.IceServer.builder(url)
                    server.username?.takeIf(String::isNotBlank)?.let(builder::setUsername)
                    server.credential?.takeIf(String::isNotBlank)?.let(builder::setPassword)
                    iceServers.add(builder.createIceServer())
                }
            }
            android.util.Log.i("WebRTCConfig", "Using managed TURN/STUN credentials")
        } else {
            // Development fallback. Production should deploy the Edge Function
            // and configure Cloudflare TURN so credentials are short-lived.
            stunServers.forEach { url ->
            iceServers.add(
                PeerConnection.IceServer.builder(url)
                    .createIceServer()
            )
            android.util.Log.d("WebRTCConfig", "Added STUN server: $url")
            }

            TurnServerConfig.servers.forEach { url ->
                iceServers.add(
                    PeerConnection.IceServer.builder(url)
                        .setUsername(TurnServerConfig.USERNAME)
                        .setPassword(TurnServerConfig.CREDENTIAL)
                        .createIceServer()
                )
                android.util.Log.d("WebRTCConfig", "Added development TURN server: $url")
            }
        }

        android.util.Log.i("WebRTCConfig", "Total ICE endpoints configured: ${iceServers.size}")

        return PeerConnection.RTCConfiguration(iceServers).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

            iceConnectionReceivingTimeout = Timeouts.CONNECTION_TIMEOUT_MS.toInt()
        }
    }

    object AudioConstraints {
        const val ECHO_CANCELLATION = true
        const val AUTO_GAIN_CONTROL = true
        const val NOISE_SUPPRESSION = true
        const val HIGH_PASS_FILTER = true
        const val TYPED_NOISE_DETECTION = true
    }

    object Timeouts {
        const val CONNECTION_TIMEOUT_MS = 30000L  
        const val ANSWER_TIMEOUT_MS = 60000L      
    }

    object StreamLabels {
        const val AUDIO_TRACK_ID = "audio_track"
        const val VIDEO_TRACK_ID = "video_track"
        const val STREAM_ID = "tv_caller_stream"
    }

    object VideoConstraints {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val FPS = 30
        // WebRTC remains free to lower bitrate/resolution on weak networks; this
        // is only the quality ceiling for hardware that can sustain Full HD.
        const val MAX_BITRATE_BPS = 5_000_000
    }
}
