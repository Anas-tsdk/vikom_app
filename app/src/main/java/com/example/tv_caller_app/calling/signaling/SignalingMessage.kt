package com.example.tv_caller_app.calling.signaling

import com.example.tv_caller_app.domain.AppointmentEvent
import kotlinx.serialization.Serializable

sealed class SignalingMessage {

    @Serializable
    data class CallOffer(
        val callId: String = "",
        val callerId: String,         
        val callerUserId: String,     
        val callerName: String,
        val callerUsername: String,
        val sdp: String,              
        val mediaType: String = "audio",  
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

    @Serializable
    data class CallAnswer(
        val callId: String = "",
        val calleeId: String,
        val calleeName: String,
        val sdp: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

    @Serializable
    data class IceCandidate(
        val callId: String = "",
        val candidate: String,        
        val sdpMid: String?,
        val sdpMLineIndex: Int?,
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

    @Serializable
    data class CallRejected(
        val callId: String = "",
        val reason: String = "user_declined",
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

    @Serializable
    data class CallEnded(
        val callId: String = "",
        val reason: String,
        val duration: Long = 0,
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

    @Serializable
    data class CameraState(
        val callId: String = "",
        val enabled: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

    @Serializable
    data class VideoUpgradeRequest(
        val callId: String = "",
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

    @Serializable
    data class VideoUpgradeResponse(
        val callId: String = "",
        val accepted: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

    @Serializable
    data class CallAnsweredElsewhere(
        val callId: String = "",
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

}

sealed class SignalingEvent {
    data class IncomingCall(
        val callId: String,
        val callerId: String,        
        val callerUserId: String,    
        val callerName: String,
        val callerUsername: String,
        val offer: String,
        val mediaType: String
    ) : SignalingEvent()

    data class CallAnswered(val callId: String, val answer: String) : SignalingEvent()

    data class NewIceCandidate(
        val callId: String,
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int?
    ) : SignalingEvent()

    data class CallRejected(val callId: String, val reason: String) : SignalingEvent()

    data class CallEnded(val callId: String, val reason: String, val duration: Long) : SignalingEvent()

    data class RemoteCameraStateChanged(val callId: String, val enabled: Boolean) : SignalingEvent()

    data class VideoUpgradeRequested(val callId: String) : SignalingEvent()

    data class VideoUpgradeResponded(val callId: String, val accepted: Boolean) : SignalingEvent()

    data class MissedCall(
        val callId: String,
        val callerName: String,
        val mediaType: String,
        val timestamp: Long
    ) : SignalingEvent()

    data class CallAnsweredElsewhere(val callId: String) : SignalingEvent()

    data class Error(val message: String, val exception: Throwable? = null) : SignalingEvent()

    data object Connected : SignalingEvent()

    data object Disconnected : SignalingEvent()

    data class AppointmentChanged(val appointment: AppointmentEvent) : SignalingEvent()
}
