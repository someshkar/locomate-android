package app.locomate.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CommunityContributionTest {
    @Test fun gatewayBatchUsesNumericIdsAndDeltaTuples() {
        val first = CommunityObservation("12137:2026-10-01", 1_000, 1_900_000, 7_280_000,
            80.2, 12.3, 0.25, 4.5)
        val second = CommunityObservation("12137:2026-10-01", 2_500, 1_900_007, 7_279_996,
            81.0, 10.0, 0.26, 3.0)
        val (payload, key) = CommunityBatch.encode(listOf(second, first))
        assertEquals(1, payload.getInt("version"))
        assertEquals(1_000L, payload.getJSONArray("base").getLong(0))
        val tuples = payload.getJSONArray("observations")
        assertEquals(first.localId, tuples.getJSONArray(0).getLong(0))
        assertEquals(0L, tuples.getJSONArray(0).getLong(3))
        assertEquals(1_500L, tuples.getJSONArray(1).getLong(3))
        assertEquals(7L, tuples.getJSONArray(1).getLong(4))
        assertEquals(-4L, tuples.getJSONArray(1).getLong(5))
        assertEquals(802, tuples.getJSONArray(0).getInt(6))
        assertEquals(123, tuples.getJSONArray(0).getInt(7))
        assertEquals(250_000, tuples.getJSONArray(0).getInt(8))
        assertTrue(key.startsWith("observations-"))
    }

    @Test fun privateQueuePersistsAndSeparatesGatewayOrigins() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val one = "https://contribution-test-one.example"
        val two = "https://contribution-test-two.example"
        val observation = CommunityObservation("12137:2026-10-01", System.currentTimeMillis(),
            1_900_000, 7_280_000, 80.0, 12.0, 0.25, 4.0)
        try {
            CommunityQueue(context, one).append(observation)
            assertEquals(observation, CommunityQueue(context, one).pendingObservations().single())
            assertTrue(CommunityQueue(context, two).pendingObservations().isEmpty())
            val withdrawal = CommunityConsent.evidence(false)
            CommunityQueue(context, one).addWithdrawal(withdrawal)
            assertEquals(withdrawal.getString("evidenceId"),
                CommunityQueue(context, one).pendingWithdrawals().single().getString("evidenceId"))
            CommunityQueue(context, one).removeObservations(setOf(observation.localId))
            assertTrue(CommunityQueue(context, one).pendingObservations().isEmpty())
        } finally {
            File(context.filesDir, "community/${railStorageScope(one)}").deleteRecursively()
            File(context.filesDir, "community/${railStorageScope(two)}").deleteRecursively()
        }
    }
}
