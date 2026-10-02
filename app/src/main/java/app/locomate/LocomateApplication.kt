package app.locomate

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class LocomateApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.FCM_PROJECT_ID.isBlank() || BuildConfig.FCM_APP_ID.isBlank() ||
            BuildConfig.FCM_API_KEY.isBlank() || BuildConfig.FCM_SENDER_ID.isBlank()) return
        if (FirebaseApp.getApps(this).isNotEmpty()) return
        FirebaseApp.initializeApp(this, FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FCM_PROJECT_ID)
            .setApplicationId(BuildConfig.FCM_APP_ID)
            .setApiKey(BuildConfig.FCM_API_KEY)
            .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
            .build())
    }
}
