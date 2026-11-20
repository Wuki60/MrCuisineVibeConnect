package app.toque.core

import app.toque.core.time.ZoneLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneLookupTest {
    @Test
    fun readsTheZoneOfEachProvider() {
        assertEquals("Europe/Paris", ZoneLookup.zoneOf("""{"success":true,"timezone":{"id":"Europe/Paris","abbr":"CEST","utc":"+02:00"}}"""))
        assertEquals("Europe/Brussels", ZoneLookup.zoneOf("""{"ip":"1.2.3.4","country":"Belgium","timezone":"Europe/Brussels"}"""))
        assertEquals("Indian/Reunion", ZoneLookup.zoneOf("""{"status":"success","timezone":"Indian/Reunion"}"""))
        assertEquals("America/Argentina/Buenos_Aires", ZoneLookup.zoneOf("""{"timezone":"America/Argentina/Buenos_Aires"}"""))
    }

    @Test
    fun rejectsFailuresAndNonsense() {
        assertNull(ZoneLookup.zoneOf("""{"success":false,"message":"Reserved range"}"""))
        assertNull(ZoneLookup.zoneOf("""{"status":"fail","timezone":"Europe/Paris"}"""))
        assertNull(ZoneLookup.zoneOf("""{"timezone":"; rm -rf /"}"""))
        assertNull(ZoneLookup.zoneOf("""{"timezone":"Europe/Paris'; reboot"}"""))
        assertNull(ZoneLookup.zoneOf("pas du json"))
        assertNull(ZoneLookup.zoneOf("""{"timezone":""}"""))
    }

    @Test
    fun defersTheLookupUntilTheClockIsPlausible() {
        assertFalse(ZoneLookup.clockPlausible(0L))
        assertFalse(ZoneLookup.clockPlausible(1_262_304_000_000L)) // 1er janvier 2010
        assertFalse(ZoneLookup.clockPlausible(ZoneLookup.PLAUSIBLE_SINCE_MS - 1))
        assertTrue(ZoneLookup.clockPlausible(ZoneLookup.PLAUSIBLE_SINCE_MS))
        assertTrue(ZoneLookup.clockPlausible(1_800_000_000_000L))
    }

    @Test
    fun retriesTheLookupGently() {
        assertEquals(listOf(10_000L, 30_000L, 60_000L, 60_000L, 60_000L),
            (0..4).map { ZoneLookup.retryDelayMs(it) })
    }

    @Test
    fun keepsThePlainHttpProviderWhileTheClockIsWrong() {
        val now = 0L // 1er janvier 1970 : pas de pile d'horloge
        assertFalse(ZoneLookup.usable("https://ipwho.is/?fields=success,timezone", now))
        assertFalse(ZoneLookup.usable("https://ipapi.co/json/", now))
        assertTrue(ZoneLookup.usable("http://ip-api.com/json/?fields=status,timezone", now))
        assertTrue(ZoneLookup.PROVIDERS.any { !it.url.startsWith("https:") })
    }

    @Test
    fun usesEveryProviderOnceTheClockIsRight() {
        val now = 1_800_000_000_000L
        assertTrue(ZoneLookup.PROVIDERS.all { ZoneLookup.usable(it.url, now) })
        assertTrue(ZoneLookup.usable("http://ip-api.com/json/?fields=status,timezone", now))
    }
}
