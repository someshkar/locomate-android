package app.locomate.data

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Kept outside erased data roots so partial cleanup cannot silently resume an installation. */
object PrivacyDeletionState {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "privacy-deletion.pending"))

    fun pending(context: Context): Boolean = file(context).baseFile.exists()

    /** Coordinates durable local writes with the deletion marker. */
    @Synchronized fun <T> withDataAccess(context: Context, block: () -> T): T {
        if (pending(context)) throw GatewayError("Data deletion is pending.", code = "deletion_pending")
        return block()
    }

    @Synchronized fun begin(context: Context) {
        val marker = file(context)
        val output = marker.startWrite()
        try {
            output.write("pending".toByteArray(Charsets.UTF_8))
            marker.finishWrite(output)
        } catch (error: Exception) {
            marker.failWrite(output)
            throw error
        }
    }

    @Synchronized fun finish(context: Context): Boolean {
        file(context).delete()
        return !pending(context)
    }
}
