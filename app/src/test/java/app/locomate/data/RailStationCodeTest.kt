package app.locomate.data

import org.junit.Assert.*
import org.junit.Test

class RailStationCodeTest {
    @Test fun acceptsCatalogueHyphensAndOneLetterButRejectsUnsafeOrOverlongCodes() {
        for (code in listOf("A", "NDLS", "NRL-DLS", "AB-1", "A123456789")) assertTrue(code, RailStationCode.isValid(code))
        for (code in listOf("", "123", "1A", "-A", "A-", "A--B", "a", "A/B", "A?x", "A1234567890", "ＮＤＬＳ")) assertFalse(code, RailStationCode.isValid(code))
    }
}
