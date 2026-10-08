package app.locomate.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class QueuedPhysicalReport(val trainNumber: String, val date: String, val installation: String,
                                val bodyText: String, val key: String) {
    val consentedAt: Long get() = JSONObject(bodyText).getJSONObject("consent").nonnegativeInteger("consentedAt")
    fun hasCurrentConsent(now: Long = System.currentTimeMillis()) = consentedAt in (now - 15 * 60_000)..(now + 60_000)
    fun canRenew(now: Long = System.currentTimeMillis()) = sightings().all { it.observedAt in (now - 24 * 3_600_000)..(now + 5 * 60_000) }
    fun sightings(): List<PhysicalSighting> {
        val array = JSONObject(bodyText).getJSONArray("sightings")
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            require(item.keys().asSequence().toSet() == setOf("assetKind", "identifier", "observedAt", "evidenceMethod") +
                if (item.has("stationCode")) setOf("stationCode") else emptySet())
            val kind = SightingKind.entries.first { it.wire == item.getString("assetKind") }
            require(item.getString("evidenceMethod") == kind.method)
            PhysicalSighting(kind, item.getString("identifier"), item.nonnegativeInteger("observedAt"),
                if (item.isNull("stationCode")) null else item.getString("stationCode"))
                .also { it.encode() }
        }
    }
    fun encode() = JSONObject().put("trainNumber", trainNumber).put("date", date)
        .put("installation", installation).put("bodyText", bodyText).put("key", key)
    companion object {
        fun decode(item: JSONObject): QueuedPhysicalReport {
            val value = QueuedPhysicalReport(item.getString("trainNumber"), item.getString("date"),
                item.getString("installation"), item.getString("bodyText"), item.getString("key"))
            require(JourneyAlertLink.fromRunId("${value.trainNumber}:${value.date}") != null && value.installation.isNotBlank())
            val digest = MessageDigest.getInstance("SHA-256").digest(value.bodyText.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            require(value.key == "physical-$digest")
            val body = JSONObject(value.bodyText)
            require(body.keys().asSequence().toSet() == setOf("sightings", "consent"))
            val consent = body.getJSONObject("consent")
            require(consent.keys().asSequence().toSet() == setOf("evidenceId", "purpose", "granted", "consentVersion", "noticeHash", "consentedAt"))
            require(consent.getBoolean("granted") && consent.getString("purpose") == CommunityConsent.PURPOSE &&
                consent.getString("noticeHash") == CommunityConsent.NOTICE_HASH &&
                consent.getString("consentVersion") == CommunityConsent.VERSION.toString())
            require(consent.getString("evidenceId").isNotBlank() && value.consentedAt >= 0)
            val sightings = value.sightings()
            require(sightings.size in 1..8 && sightings.map { it.kind to it.identifier }.distinct().size == sightings.size)
            return value
        }
    }
}

/** JSON numeric coercion must never truncate a persisted consent or observation timestamp. */
private fun JSONObject.nonnegativeInteger(key: String): Long {
    val number = get(key) as? Number ?: error("Invalid timestamp $key")
    val finite = number.toDouble()
    require(finite.isFinite() && finite >= 0 && finite <= 9_007_199_254_740_991.0 && finite % 1.0 == 0.0)
    return number.toLong()
}

/** Exact bodies and retry keys survive dismissal/process death, within the same gateway and installation. */
class PhysicalReportQueue(context: Context, sourceUrl: String) {
    private val appContext = context.applicationContext
    private val file = AtomicFile(File(appContext.filesDir, "community/${railStorageScope(sourceUrl)}/physical-sightings.json"))
    fun pending(): List<QueuedPhysicalReport> = synchronized(lock) {
        if (!file.baseFile.exists()) emptyList() else {
            require(file.baseFile.length() <= 262_144) { "Physical report queue is too large" }
            val saved = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            require(saved.getInt("version") == 1)
            val items = saved.getJSONArray("reports")
            require(items.length() <= 32)
            (0 until items.length()).map { QueuedPhysicalReport.decode(items.getJSONObject(it)) }
        }
    }
    fun stage(train: String, date: String, batch: PhysicalSightingBatch, installation: String,
              replacing: String? = null): QueuedPhysicalReport = synchronized(lock) {
        PrivacyDeletionState.withDataAccess(appContext) {
            require(currentInstallation() == installation) { "Installation changed before saving this report" }
            val (body, key) = batch.encode()
            val item = QueuedPhysicalReport.decode(QueuedPhysicalReport(train, date, installation, body.toString(), key).encode())
            val previous = pending().filterNot { it.key == replacing || it.key == key }
            require(previous.size < 32) { "Remove an old pending report before adding another" }
            save(previous + item)
            item
        }
    }
    fun remove(key: String, installation: String) = synchronized(lock) {
        PrivacyDeletionState.withDataAccess(appContext) {
            require(currentInstallation() == installation) { "Installation changed while confirming this report" }
            if (file.baseFile.exists()) save(pending().filterNot { it.key == key })
        }
    }
    fun contains(item: QueuedPhysicalReport): Boolean = synchronized(lock) {
        currentInstallation() == item.installation && pending().any { it.key == item.key }
    }
    fun clear() = synchronized(lock) { file.delete(); check(!file.baseFile.exists()) }
    private fun currentInstallation() = appContext.getSharedPreferences("locomate.installation", Context.MODE_PRIVATE).getString("id", null)
    private fun save(items: List<QueuedPhysicalReport>) {
        file.baseFile.parentFile!!.mkdirs()
        val output = file.startWrite()
        try {
            output.write(JSONObject().put("version", 1).put("reports", JSONArray(items.map { it.encode() }))
                .toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) { file.failWrite(output); throw error }
    }
    companion object { private val lock = Any() }
}
