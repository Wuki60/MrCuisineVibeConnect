package app.toque.core

import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.MccProtocolException
import app.toque.core.protocol.MccStreamParser
import app.toque.core.protocol.Operation
import app.toque.core.protocol.Telemetry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolTest {
    private fun resource(name: String): List<String> =
        javaClass.getResourceAsStream("/mcc/$name")!!.bufferedReader().use { r ->
            r.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        }

    private fun fields(s: Telemetry): String = listOf(
        s.status, s.motorHeaterState, s.motorOn, s.heaterOn, s.scaleStable, s.direction.androidValue,
        s.errorDetected, s.errorCode, s.androidErrorCode, s.aboveTargetTemperature, s.bowlNtcDetected,
        s.lidOpen, s.extendedState, s.targetSpeedLevel, s.targetRpm, s.actualRpm, s.targetTemperatureLevel,
        s.targetTemperature, s.actualTemperature, s.weight, s.firmwareVersion, s.scaleStateRaw, s.scaleState,
        s.calibrationStep, s.unknownByte24, s.actualSpeedLevel, s.targetSpeedLevelSigned,
        s.targetTemperatureLevelSigned, s.unknownByte24Signed, s.androidScaleState,
    ).joinToString("\t")

    @Test fun goldenTxVectors() {
        for (line in resource("golden_tx_292.tsv")) {
            val c = line.split('\t')
            val raw = Mcc.encodeTx(
                Operation.values().single { it.wire == c[0].toInt() }, c[1].toInt(), c[2].toInt(),
                Direction.values().single { it.androidValue == c[3].toInt() }, c[4] == "1",
            )
            assertEquals(c[5], Mcc.hex(raw))
        }
    }

    @Test fun goldenRxVectors() {
        val lines = resource("golden_rx_292.tsv")
        assertTrue(lines.size > 200)
        for (line in lines) {
            val split = line.indexOf('\t')
            assertEquals(line.substring(split + 1), fields(Mcc.decodeRx(Mcc.parseHex(line.substring(0, split)))))
        }
    }

    @Test fun everySingleBitMutationIsRejected() {
        val original = Mcc.parseHex(resource("golden_rx_292.tsv").first().substringBefore('\t'))
        for (i in original.indices) for (bit in 0..7) {
            val changed = original.copyOf()
            changed[i] = (changed[i].toInt() xor (1 shl bit)).toByte()
            try {
                Mcc.decodeRx(changed)
                fail("mutation $i/$bit acceptée")
            } catch (_: MccProtocolException) {
            }
        }
    }

    @Test fun encoderRejectsOutOfRange() {
        for (bad in listOf(-1, 11)) try {
            Mcc.encodeTx(speed = bad); fail()
        } catch (_: IllegalArgumentException) {
        }
        try {
            Mcc.encodeTx(temperatureLevel = 20); fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun streamResynchronises() {
        val raw = Mcc.parseHex(resource("golden_rx_292.tsv").first().substringBefore('\t'))
        for (cut in 1..26) {
            val p = MccStreamParser()
            assertTrue(p.feed(raw.copyOfRange(0, cut)).isEmpty())
            assertArrayEquals(raw, p.feed(raw.copyOfRange(cut, 27)).single())
            assertEquals(0, p.bufferedBytes)
        }
        val p = MccStreamParser()
        val bad = raw.copyOf()
        bad[25] = (bad[25].toInt() xor 1).toByte()
        val frames = p.feed(byteArrayOf(1, 2, 3) + bad + raw + raw)
        assertEquals(2, frames.size)
        assertTrue(p.invalidFrames > 0)
        p.feed(ByteArray(10_000) { 0x55 })
        assertTrue(p.bufferedBytes <= 26)
    }

    @Test fun temperatureTable() {
        assertEquals(17, Mcc.levelForCelsius(120))
        assertEquals(-1, Mcc.levelForCelsius(98))
        assertEquals(12, Mcc.levelAtMost(98))
        assertEquals(0, Mcc.levelAtMost(30))
        assertEquals(130, Mcc.celsiusForLevel(19))
    }
}
