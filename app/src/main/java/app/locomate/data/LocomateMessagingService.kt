package app.locomate.data

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** FCM data messages only update the card the traveller explicitly enabled. */
class LocomateMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        StatusPushWork.registered(this, installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data.isEmpty()) return
        if (JourneyStatusNotification(this).applyPush(message.data) && message.data["event"] == "end") {
            StatusPushWork.unregister(this, message.data["runId"] ?: return)
        }
    }
}
