package app.locomate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.security.MessageDigest

class JourneyAlertModelsTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli()
    private val subscription = JourneyAlertSubscription("12951:2026-10-01", now, true,
        JourneyAlertChannel.entries.toSet(), null, now + 3_600_000)
    private val payload = mapOf("type" to "journey-alert", "version" to "1", "eventId" to "alert:one",
        "runId" to subscription.runId, "revision" to subscription.revision.toString(), "channel" to "delay",
        "trainNumber" to "12951", "serviceDate" to "2026-10-01", "observedAt" to (now - 1_000).toString(),
        "expiresAt" to (now + 60_000).toString(), "title" to "Delay changed", "body" to "Expected 5 minutes later",
        "deepLink" to "locomate://journeys/12951?date=2026-10-01")

    @Test fun consentEvidenceMatchesTheNoticeActuallyShownAndOldConsentIsInactive() {
        val digest = MessageDigest.getInstance("SHA-256").digest(JourneyAlertConsent.NOTICE.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(JourneyAlertConsent.NOTICE_HASH, digest)
        assertFalse(subscription.copy(consentVersion = "old").active(now))
        assertFalse(subscription.copy(noticeHash = "wrong").active(now))
        assertNull(JourneyAlertPayload.parse(payload, subscription.copy(consentVersion = "old"), now))
    }

    @Test fun validAlertRequiresMatchingRunRevisionAndChannel() {
        assertNotNull(JourneyAlertPayload.parse(payload, subscription, now))
        assertNull(JourneyAlertPayload.parse(payload, subscription.copy(enabled = false), now))
        assertNull(JourneyAlertPayload.parse(payload, subscription.copy(revision = now + 1), now))
        assertNull(JourneyAlertPayload.parse(payload, subscription.copy(channels = setOf(JourneyAlertChannel.Arrival)), now))
        assertNull(JourneyAlertPayload.parse(payload + ("runId" to "12951:2026-10-02"), subscription, now))
        assertNull(JourneyAlertPayload.parse(payload + ("serviceDate" to "2026-10-02"), subscription, now))
        assertNull(JourneyAlertPayload.parse(payload + ("trainNumber" to "12137"), subscription, now))
    }

    @Test fun rejectsExpiredFutureMalformedAndOversizedPayloads() {
        listOf(
            "observedAt" to (now - 11 * 60_000).toString(),
            "observedAt" to (now + 61_000).toString(),
            "expiresAt" to now.toString(),
            "expiresAt" to (now + 11 * 60_000).toString(),
            "title" to "x".repeat(161), "body" to "x\u0000y", "eventId" to " ",
            "version" to "2", "channel" to "unknown", "revision" to "NaN",
            "deepLink" to "locomate://journeys/12951?date=2026-10-02",
        ).forEach { invalid -> assertNull(invalid.first,
            JourneyAlertPayload.parse(payload + invalid, subscription, now)) }
        assertNull(JourneyAlertPayload.parse(payload, subscription.copy(expiresAt = now), now))
    }

    @Test fun datedDeepLinksRejectAmbiguousAndInvalidDates() {
        val valid = "locomate://journeys/12951?date=2026-10-01"
        assertEquals("12951:2026-10-01", JourneyAlertLink.parse(valid)?.runId)
        listOf("https://journeys/12951?date=2026-10-01", "$valid&date=2026-10-02", "$valid#fragment",
            "locomate://evil@journeys/12951?date=2026-10-01", "locomate://journeys/12951?date=2026-02-30",
            "locomate://journeys/12951?date=2026-10-01%20", "locomate://journeys/12951/?date=2026-10-01",
            "locomate://journeys:443/12951?date=2026-10-01").forEach { assertNull(it, JourneyAlertLink.parse(it)) }
    }

    @Test fun revisionsStayMonotonicWhenClockMovesBackwards() {
        assertEquals(now + 1, JourneyAlertSubscription.nextRevision(now, now - 60_000))
        assertEquals(now + 60_000, JourneyAlertSubscription.nextRevision(now, now + 60_000))
    }

    @Test fun quietHoursHandleOvernightAndDaytimeBoundaries() {
        val quiet = JourneyAlertQuietHours("22:00", "07:00")
        fun at(value: String) = Instant.parse("2026-10-01T${value}:00+05:30").toEpochMilli()
        assertTrue(quiet.valid())
        assertTrue(quiet.contains(at("22:00")))
        assertTrue(quiet.contains(at("06:59")))
        assertFalse(quiet.contains(at("07:00")))
        assertFalse(quiet.contains(at("21:59")))
        assertTrue(JourneyAlertQuietHours("12:00", "13:00").contains(at("12:30")))
        assertFalse(JourneyAlertQuietHours("22:00", "22:00").valid())
        assertFalse(JourneyAlertQuietHours("22:00", "07:00", "Not/AZone").valid())
    }

    @Test fun subscriptionExpiryIsBoundedAndRejectsPreviewAndPastRuns() {
        val route = RoutePreview("12951", "Test", "A", "A", "B", "B", "12:00", "13:00", 0,
            listOf(RailPoint(19.0, 72.0), RailPoint(20.0, 73.0)), emptyList(),
            isPreview = false, runId = subscription.runId, runDate = "2026-10-01",
            departureInstantMillis = now - 1_000, arrivalInstantMillis = now + 3_600_000)
        assertEquals(now + 25 * 3_600_000L, JourneyAlertSubscription.localExpiry(route, now))
        assertEquals(now + 5 * 24 * 3_600_000L,
            JourneyAlertSubscription.localExpiry(route.copy(arrivalInstantMillis = now + 8 * 24 * 3_600_000L), now))
        assertNull(JourneyAlertSubscription.localExpiry(route.copy(isPreview = true), now))
        assertNull(JourneyAlertSubscription.localExpiry(route.copy(statusLabel = "STALE · LAST KNOWN"), now))
        assertNull(JourneyAlertSubscription.localExpiry(route, now + 26 * 3_600_000L))
    }
}
