package app.locomate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RailStorageScopeTest {
    @Test fun cacheScopeCannotCrossGatewayOrigins() {
        val production = railStorageScope("https://rail.example")
        assertEquals(production, railStorageScope("https://rail.example/"))
        assertNotEquals(production, railStorageScope("http://10.0.2.2:8766"))
        assertNotEquals(production, railStorageScope(""))
        assertEquals("preview", railStorageScope(""))
    }
}
