package dev.jskw.counter.util

import dev.jskw.counter.data.model.Counter
import dev.jskw.counter.data.model.canDelete
import dev.jskw.counter.data.model.canDeleteOnServer
import dev.jskw.counter.data.model.canIncrementDecrement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CounterLinkParserTest {

    private val uuidReadOnly = "c768f56e-8ff4-4363-8822-79ee8ca6eef4"
    private val uuidReadWrite = "e2244bb4-0997-4ef8-8280-9dfbf2ba1022"
    private val uuidAdmin = "523a8867-b50a-4a25-a1c1-40c42be279ab"

    @Test
    fun testParseFull3KeyAnchor() {
        val raw = "#Kitchen Inventory#read#$uuidReadOnly#write#$uuidReadWrite#admin#$uuidAdmin"
        val parsed = CounterLinkParser.parse(raw)

        assertEquals("Kitchen Inventory", parsed.label)
        assertEquals(3, parsed.uuids.size)
        assertEquals(uuidReadOnly, parsed.uuids[0])
        assertEquals(uuidReadWrite, parsed.uuids[1])
        assertEquals(uuidAdmin, parsed.uuids[2])
    }

    @Test
    fun testParseDefaultTrickOrTreatersFullAdminLink() {
        val raw = "https://counter.jskw.dev/#Trick-or-Treaters#read#a20686ba-d0c5-48bf-a770-5161f0cd4575#write#dbbccc62-c6ec-4788-be97-469732d59226#admin#6292f09d-e65c-4661-9e90-fbafb5a35f9f"
        val parsed = CounterLinkParser.parse(raw)

        assertEquals("Trick-or-Treaters", parsed.label)
        assertEquals(3, parsed.uuids.size)
        assertEquals("a20686ba-d0c5-48bf-a770-5161f0cd4575", parsed.uuids[0])
        assertEquals("dbbccc62-c6ec-4788-be97-469732d59226", parsed.uuids[1])
        assertEquals("6292f09d-e65c-4661-9e90-fbafb5a35f9f", parsed.uuids[2])
    }

    @Test
    fun testParseReadWriteAnchor() {
        val raw = "http://localhost:8000/#Coffee Count#read#$uuidReadOnly#write#$uuidReadWrite"
        val parsed = CounterLinkParser.parse(raw)

        assertEquals("Coffee Count", parsed.label)
        assertEquals(2, parsed.uuids.size)
        assertEquals(uuidReadOnly, parsed.uuids[0])
        assertEquals(uuidReadWrite, parsed.uuids[1])
    }

    @Test
    fun testParseReadOnlyAnchor() {
        val raw = "https://example.com/#Spectators#read#$uuidReadOnly"
        val parsed = CounterLinkParser.parse(raw)

        assertEquals("Spectators", parsed.label)
        assertEquals(1, parsed.uuids.size)
        assertEquals(uuidReadOnly, parsed.uuids[0])
    }

    @Test
    fun testBuildShareUrlEncodesSpacesAndSpecialChars() {
        val counter = Counter(
            title = "Trick-or-Treaters 2026",
            viewUuid = uuidReadOnly,
            updateUuid = uuidReadWrite,
            deleteUuid = uuidAdmin
        )

        val url = CounterLinkParser.buildShareUrl("http://localhost:8000", counter, ShareRole.ADMIN)
        assertEquals(
            "http://localhost:8000/#Trick-or-Treaters%202026#read#$uuidReadOnly#write#$uuidReadWrite#admin#$uuidAdmin",
            url
        )
    }

    @Test
    fun testParseDecodesPercent20AsSingleChar() {
        val raw = "http://localhost:8000/#Kitchen%20Inventory%20Room%201#read#$uuidReadOnly#write#$uuidReadWrite"
        val parsed = CounterLinkParser.parse(raw)

        assertEquals("Kitchen Inventory Room 1", parsed.label)
        assertEquals(2, parsed.uuids.size)
        assertEquals(uuidReadOnly, parsed.uuids[0])
        assertEquals(uuidReadWrite, parsed.uuids[1])
    }

    @Test
    fun testUrlParsingFirstWithUri() {
        val rawUrl = "https://mycounter.app:8443/dashboard/#My%20Cool%20Counter#read#$uuidReadOnly"
        val parsed = CounterLinkParser.parse(rawUrl)

        assertEquals("My Cool Counter", parsed.label)
        assertEquals(1, parsed.uuids.size)
        assertEquals(uuidReadOnly, parsed.uuids[0])
    }

    @Test
    fun testRoundTripBuildAndParseUrl() {
        val originalTitle = "Bottles of Water & Soda #2"
        val counter = Counter(
            title = originalTitle,
            viewUuid = uuidReadOnly,
            updateUuid = uuidReadWrite,
            deleteUuid = uuidAdmin
        )

        val builtUrl = CounterLinkParser.buildShareUrl("https://example.com", counter, ShareRole.ADMIN)
        val parsed = CounterLinkParser.parse(builtUrl)

        assertEquals(originalTitle, parsed.label)
        assertEquals(3, parsed.uuids.size)
        assertEquals(uuidReadOnly, parsed.uuids[0])
        assertEquals(uuidReadWrite, parsed.uuids[1])
        assertEquals(uuidAdmin, parsed.uuids[2])
    }

    @Test
    fun testCounterPermissions() {
        // Admin
        val adminCounter = Counter(
            title = "Admin Counter",
            isPublished = true,
            viewUuid = uuidReadOnly,
            updateUuid = uuidReadWrite,
            deleteUuid = uuidAdmin
        )
        assertTrue(adminCounter.canIncrementDecrement)
        assertTrue(adminCounter.canDelete)
        assertTrue(adminCounter.canDeleteOnServer)

        // Read-Write (no deleteUuid)
        val rwCounter = Counter(
            title = "Read-Write Counter",
            isPublished = true,
            viewUuid = uuidReadOnly,
            updateUuid = uuidReadWrite,
            deleteUuid = null
        )
        assertTrue(rwCounter.canIncrementDecrement)
        assertTrue(rwCounter.canDelete)
        assertFalse(rwCounter.canDeleteOnServer)

        // Read-Only (no updateUuid and no deleteUuid)
        val roCounter = Counter(
            title = "Read-Only Counter",
            isPublished = true,
            viewUuid = uuidReadOnly,
            updateUuid = null,
            deleteUuid = null
        )
        assertFalse(roCounter.canIncrementDecrement)
        assertTrue(roCounter.canDelete)
        assertFalse(roCounter.canDeleteOnServer)
    }
}
