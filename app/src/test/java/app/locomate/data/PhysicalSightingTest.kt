package app.locomate.data

import org.junit.Assert.*
import org.junit.Test

class PhysicalSightingTest {
    @Test fun publicNumbersCannotBeConfusedWithPrivateCoachOrSeatLabels() {
        for (identifier in listOf("B1", "A1", "S1", "24 LB", "00000", "1234", "1234567890123"))
            assertFalse(identifier, PhysicalSighting(SightingKind.Coach, identifier, 0).validIdentifier)
        assertTrue(PhysicalSighting(SightingKind.Coach, "0123456", 0).validIdentifier)
        assertTrue(PhysicalSighting(SightingKind.Coach, "123456789012", 0).validIdentifier)
        assertTrue(PhysicalSighting(SightingKind.Locomotive, "30201", 0).validIdentifier)
        for (identifier in listOf("00000", "03020", "123456", "3020", "٣٠٢٠١"))
            assertFalse(identifier, PhysicalSighting(SightingKind.Locomotive, identifier, 0).validIdentifier)
    }
}
