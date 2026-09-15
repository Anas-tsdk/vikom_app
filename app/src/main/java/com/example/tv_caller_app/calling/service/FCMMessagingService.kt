package com.example.tv_caller_app.calling.service

import android.util.Log
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.domain.AppointmentEvent
import com.example.tv_caller_app.domain.IncomingCallOffer
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Receives high-priority, data-only Firebase messages when realtime signaling
 * cannot wake the process. Calls are injected into the normal CallRepository /
 * CallViewModel path; this service never creates a second WebRTC stack.
 */
class FCMMessagingService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "FCMMessagingService"
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "Firebase issued a refreshed device token")
        (application as? TVCallerApplication)?.updateFcmToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as? TVCallerApplication ?: return
        val payload = PushFields.from(message.data)
        val type = payload.value("type", "event", "event_type")
            ?.trim()
            ?.lowercase()

        if (!isForSignedInUser(app, payload)) return

        when (type) {
            "incoming_call", "call_offer" -> {
                val offer = payload.toIncomingCallOffer()
                if (offer == null) {
                    // Preferred cold-start payloads are deliberately small: FCM
                    // wakes the listener, then the SDP arrives over the normal
                    // signaling channel. Full offers remain supported for
                    // senders that can safely fit one in a data message.
                    Log.i(TAG, "Received an incoming-call wake signal")
                    app.wakeCallListenerFromPush()
                    return
                }
                Log.i(TAG, "Received an incoming ${offer.mediaType} call push")
                app.enqueueIncomingCallFromPush(offer)
            }

            "appointment_event", "appointment_update", "appointment_invitation" -> {
                val event = payload.toAppointmentEvent()
                if (event == null) {
                    Log.w(TAG, "Ignoring malformed appointment push")
                    return
                }
                Log.i(TAG, "Received appointment push for appointment ${event.appointmentId}")
                app.handleAppointmentPush(event)
            }

            "missed_call" -> {
                app.handleMissedCallPush(
                    callId = payload.value("call_id", "callId") ?: "missed_${System.currentTimeMillis()}",
                    callerName = payload.value("caller_name", "callerName") ?: "",
                    mediaType = payload.value("media_type", "mediaType") ?: "audio"
                )
            }

            else -> Log.d(TAG, "Ignoring unsupported Firebase data message type")
        }
    }

    private fun isForSignedInUser(app: TVCallerApplication, fields: PushFields): Boolean {
        val sessionManager = SessionManager.getInstance(app)
        val signedInUserId = sessionManager.getUserId()
        if (!sessionManager.isLoggedIn() || signedInUserId.isNullOrBlank()) {
            Log.w(TAG, "Ignoring Firebase message because no user is signed in")
            return false
        }

        val targetUserId = fields.value("target_user_id", "targetUserId", "recipient_user_id")
        if (targetUserId.isNullOrBlank()) {
            Log.w(TAG, "Ignoring Firebase message without a target user")
            return false
        }
        if (targetUserId != signedInUserId) {
            Log.w(TAG, "Ignoring Firebase message addressed to a different user")
            return false
        }
        return true
    }
}

/** Small tolerant parser for both snake_case Edge Function data and camelCase signaling JSON. */
private class PushFields private constructor(
    private val values: Map<String, String>
) {
    fun value(vararg aliases: String): String? = aliases.firstNotNullOfOrNull { alias ->
        values[normalize(alias)]?.takeIf(String::isNotBlank)
    }

    fun toIncomingCallOffer(): IncomingCallOffer? {
        val contactIdText = value("caller_contact_id", "callerContactId", "contact_id", "contactId")
        val transportCallerId = value("caller_id", "callerId")
        val callerUserId = value("caller_user_id", "callerUserId", "source_user_id", "sourceUserId")
            ?: transportCallerId?.takeIf(::looksLikeUuid)
            ?: return null
        val offerSdp = value("offer_sdp", "offerSdp", "sdp", "offer") ?: return null
        val callerName = value("caller_name", "callerName", "display_name", "displayName")
            ?: value("caller_username", "callerUsername")
            ?: "Unknown Caller"
        val callerUsername = value("caller_username", "callerUsername", "username")
            ?: callerName
        val mediaType = if (
            value("media_type", "mediaType", "call_type", "callType")
                .equals("video", ignoreCase = true)
        ) "video" else "audio"

        return IncomingCallOffer(
            callId = value("call_id", "callId") ?: "",
            callerId = contactIdText ?: transportCallerId ?: callerUserId,
            callerUserId = callerUserId,
            callerName = callerName,
            callerUsername = callerUsername,
            offerSdp = offerSdp,
            contactId = contactIdText?.toIntOrNull() ?: transportCallerId?.toIntOrNull(),
            nickname = value("caller_nickname", "callerNickname", "nickname"),
            avatarUrl = value("caller_avatar_url", "callerAvatarUrl", "avatar_url", "avatarUrl"),
            mediaType = mediaType
        )
    }

    fun toAppointmentEvent(): AppointmentEvent? {
        val appointmentId = value("appointment_id", "appointmentId", "id")?.toIntOrNull()
            ?: return null
        return AppointmentEvent(
            appointmentId = appointmentId,
            action = value("action") ?: "updated",
            date = value("date") ?: "",
            startTime = value("start_time", "startTime") ?: "",
            endTime = value("end_time", "endTime") ?: "",
            personnelName = value("personnel_name", "personnelName") ?: "",
            status = value("status") ?: "",
            patientResponseStatus = value("patient_response_status", "patientResponseStatus") ?: "",
            shortMessage = value("short_message", "shortMessage", "message") ?: ""
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun from(data: Map<String, String>): PushFields {
            val flattened = linkedMapOf<String, String>()

            fun addJsonObject(raw: String?, depth: Int = 0) {
                if (raw.isNullOrBlank() || depth > 2) return
                val jsonObject = runCatching {
                    json.parseToJsonElement(raw) as? JsonObject
                }.getOrNull() ?: return
                jsonObject.forEach { (key, element) ->
                    when (element) {
                        is JsonPrimitive -> element.contentOrNull?.let {
                            flattened.putIfAbsent(normalize(key), it)
                        }
                        is JsonObject -> addJsonObject(element.toString(), depth + 1)
                        else -> Unit
                    }
                }
            }

            // Values inside payload/data are fallbacks. Explicit top-level FCM
            // data wins if both representations are present.
            addJsonObject(data["payload"])
            addJsonObject(data["data"])
            data.forEach { (key, value) -> flattened[normalize(key)] = value }
            return PushFields(flattened)
        }

        private fun looksLikeUuid(value: String): Boolean =
            value.length == 36 && value.count { it == '-' } == 4

        private fun normalize(key: String): String =
            key.filter(Char::isLetterOrDigit).lowercase()
    }
}
