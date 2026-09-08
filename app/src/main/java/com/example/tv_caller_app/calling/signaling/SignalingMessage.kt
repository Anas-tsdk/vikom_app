package com.example.tv_caller_app.calling.signaling

import kotlinx.serialization.Serializable

sealed class SignalingMessage {

    @Serializable
    data class CallOffer(
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
        val calleeId: String,
        val calleeName: String,
        val sdp: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

    @Serializable
    data class IceCandidate(
        val candidate: String,        
        val sdpMid: String?,
        val sdpMLineIndex: Int?,
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

    @Serializable
    data class CallRejected(
        val reason: String = "user_declined",
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

    @Serializable
    data class CallEnded(
        val reason: String,
        val duration: Long = 0,
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

    @Serializable
    data class CameraState(
        val enabled: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

    @Serializable
    data class CallAnsweredElsewhere(
        val timestamp: Long = System.currentTimeMillis()
    ) : SignalingMessage()

}

sealed class SignalingEvent {
    data class IncomingCall(
        val callerId: String,        
        val callerUserId: String,    
        val callerName: String,
        val callerUsername: String,
        val offer: String,
        val mediaType: String
    ) : SignalingEvent()

    data class CallAnswered(val answer: String) : SignalingEvent()

    data class NewIceCandidate(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int?
    ) : SignalingEvent()

    data class CallRejected(val reason: String) : SignalingEvent()

    data class CallEnded(val reason: String, val duration: Long) : SignalingEvent()

    data class RemoteCameraStateChanged(val enabled: Boolean) : SignalingEvent()

    data object CallAnsweredElsewhere : SignalingEvent()

    data class Error(val message: String, val exception: Throwable? = null) : SignalingEvent()

    data object Connected : SignalingEvent()

    data object Disconnected : SignalingEvent()
}
