package app.locomate.data

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Each FCM payload is checked against the matching consumer's explicit local opt-in. */
class LocomateMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        StatusPushWork.registered(this, installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data.isEmpty()) return
        if (message.data["type"] == "journey-alert") {
            JourneyAlertsNotification(this).applyPush(message.data)
            return
        }
        if (StatusPushWork.deleting(this)) return
        if (JourneyStatusNotification(this).applyPush(message.data) && message.data["event"] == "end") {
            StatusPushWork.unregister(this, message.data["runId"] ?: return)
        }
    }
}
