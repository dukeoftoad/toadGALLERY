package com.toadgallery.gallery.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for [MetadataValue]'s constructor helpers (Phase 4). No
 * emulator, no Robolectric (ADR-0016): the wrapper is pure Kotlin, so the
 * availability/value pairing contract is pinned directly.
 */
class MetadataAvailabilityTest {

    @Test
    fun `present carries the value and PRESENT availability`() {
        val wrapper: MetadataValue<GpsCoordinates> =
            MetadataValue.present(GpsCoordinates(latitude = 51.5, longitude = -0.125))
        assertEquals(MetadataAvailability.PRESENT, wrapper.availability)
        assertEquals(GpsCoordinates(latitude = 51.5, longitude = -0.125), wrapper.value)
    }

    @Test
    fun `absent carries ABSENT and no value`() {
        val wrapper: MetadataValue<GpsCoordinates> = MetadataValue.absent()
        assertEquals(MetadataAvailability.ABSENT, wrapper.availability)
        assertNull(wrapper.value)
    }

    @Test
    fun `unsupported carries UNSUPPORTED and no value`() {
        val wrapper: MetadataValue<GpsCoordinates> = MetadataValue.unsupported()
        assertEquals(MetadataAvailability.UNSUPPORTED, wrapper.availability)
        assertNull(wrapper.value)
    }

    @Test
    fun `unreadable carries UNREADABLE and no value`() {
        val wrapper: MetadataValue<GpsCoordinates> = MetadataValue.unreadable()
        assertEquals(MetadataAvailability.UNREADABLE, wrapper.availability)
        assertNull(wrapper.value)
    }

    @Test
    fun `helpers are interchangeable across payload types`() {
        // The type parameter flows through all four helpers, so a field
        // declared MetadataValue<ExposureInfo> can only be built with an
        // ExposureInfo payload --- availability/value mismatches are
        // unrepresentable at the construction sites.
        val exposure: MetadataValue<ExposureInfo> = MetadataValue.absent()
        val camera: MetadataValue<CameraInfo> = MetadataValue.unsupported()
        assertEquals(MetadataAvailability.ABSENT, exposure.availability)
        assertEquals(MetadataAvailability.UNSUPPORTED, camera.availability)
    }
}
