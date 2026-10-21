/*
 * Protocole série de la base moteur du Monsieur Cuisine Connect (MCLauncher build 292).
 *
 * Adapté du « Monsieur Cuisine Developer SDK » (licence MIT, voir
 * docs/LICENSE-monsieur-cuisine-developer-sdk.txt). Les faits de protocole viennent de
 * leur analyse statique ; rien ici n'a été mesuré sur une vraie machine.
 */
package app.toque.core.protocol

import app.toque.core.i18n.tr
import java.io.IOException

class MccProtocolException(message: String) : IOException(message)

/** Sens de rotation. Sur le fil (octet 11 TX) : 0 = horaire, 1 = antihoraire (inverse). */
enum class Direction(val androidValue: Int) {
    COUNTER_CLOCKWISE(0),
    CLOCKWISE(1);

    val wire: Int get() = 1 - androidValue
}

/** Opérations TX observées dans le build 292. */
enum class Operation(val wire: Int) {
    STOP(0x00),
    STANDARD(0x01),
    STEAM(0x03),
    TOAST(0x04),
    KNEAD(0x05),
    SLEEP(0x0F),
    TURBO(0xC6);
}

/** Une trame d'état reçue de la base (27 octets), décodée sans interprétation supplémentaire. */
class Telemetry(
    val raw: ByteArray,
    val status: Int,
    val motorHeaterState: Int,
    val motorOn: Boolean,
    val heaterOn: Boolean,
    val scaleStable: Boolean,
    val direction: Direction,
    val errorDetected: Boolean,
    val errorCode: Int,
    val androidErrorCode: Int,
    val aboveTargetTemperature: Boolean,
    val bowlNtcDetected: Boolean,
    val lidOpen: Boolean,
    val extendedState: Int,
    val targetSpeedLevel: Int,
    val targetRpm: Int,
    val actualRpm: Int,
    val targetTemperatureLevel: Int,
    val targetTemperature: Int,
    val actualTemperature: Int,
    val weight: Int,
    val firmwareVersion: Int,
    val scaleStateRaw: Int,
    val scaleState: String,
    val calibrationStep: Int,
    val unknownByte24: Int,
    val warnings: List<String>,
) {
    val targetSpeedLevelSigned: Int get() = if (targetSpeedLevel < 128) targetSpeedLevel else targetSpeedLevel - 256
    val targetTemperatureLevelSigned: Int get() = if (targetTemperatureLevel < 128) targetTemperatureLevel else targetTemperatureLevel - 256
    val unknownByte24Signed: Int get() = if (unknownByte24 < 128) unknownByte24 else unknownByte24 - 256
    val androidScaleState: String get() = if (scaleState == "not_available") "overload" else scaleState
    val actualSpeedLevel: Int get() = Mcc.actualSpeedLevel(actualRpm)

    /** Moteur réellement à l'arrêt selon cet échantillon (bit moteur ET régime mesuré nul). */
    val motorStopped: Boolean get() = !motorOn && actualRpm == 0
    val hex: String get() = Mcc.hex(raw)
}

object Mcc {
    const val TX_SIZE = 15
    const val RX_SIZE = 27

    /** Niveau de température (0..19) -> °C, table de l'appli d'origine. */
    val TEMPERATURE_LEVELS: IntArray = intArrayOf(
        0, 37, 45, 50, 55, 60, 65, 70, 75, 80,
        85, 90, 95, 100, 105, 110, 115, 120, 125, 130,
    )

    private val ERROR_MAP = mapOf(1 to 1, 3 to 2, 4 to 3, 5 to 4, 6 to 5, 7 to 12, 8 to 13)

    fun celsiusForLevel(level: Int): Int = TEMPERATURE_LEVELS[level.coerceIn(0, 19)]

    /** Niveau exact pour une température de la table, sinon -1. */
    fun levelForCelsius(celsius: Int): Int = TEMPERATURE_LEVELS.indexOf(celsius)

    /** Niveau le plus proche sans dépasser la température demandée (0 si < 37 °C). */
    fun levelAtMost(celsius: Int): Int {
        var best = 0
        for (i in TEMPERATURE_LEVELS.indices) if (TEMPERATURE_LEVELS[i] <= celsius) best = i
        return best
    }

    fun checksum(bytes: ByteArray, end: Int = bytes.size): Int {
        var total = 0
        for (i in 0 until end) total = (total + (bytes[i].toInt() and 0xFF)) and 0xFF
        return total
    }

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 3)
        for ((i, b) in bytes.withIndex()) {
            if (i > 0) sb.append(' ')
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    fun parseHex(text: String): ByteArray {
        val compact = text.filter { !it.isWhitespace() }
        require(compact.isNotEmpty() && compact.length % 2 == 0) { "hex invalide" }
        return ByteArray(compact.length / 2) { compact.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Encode une trame de commande. Refuse les valeurs hors plage au lieu de les remplacer. */
    fun encodeTx(
        operation: Operation = Operation.STOP,
        speed: Int = 0,
        temperatureLevel: Int = 0,
        direction: Direction = Direction.CLOCKWISE,
        tare: Boolean = false,
    ): ByteArray {
        require(speed in 0..10) { "vitesse hors plage 0..10" }
        require(temperatureLevel in 0..19) { "niveau de température hors plage 0..19" }
        val raw = ByteArray(TX_SIZE)
        raw[0] = 0x55
        raw[1] = TX_SIZE.toByte()
        raw[2] = 0xA1.toByte()
        raw[3] = operation.wire.toByte()
        raw[4] = (if (tare) 0xA9 else 0).toByte()
        raw[5] = speed.toByte()
        raw[6] = temperatureLevel.toByte()
        // 7..10 réservés à zéro, 12 = calibration balance normale (jamais générée ici)
        raw[11] = direction.wire.toByte()
        raw[13] = checksum(raw, 13).toByte()
        raw[14] = 0xAA.toByte()
        return raw
    }

    fun validate(raw: ByteArray, expectedSize: Int? = null) {
        val size = raw.size
        if ((size != TX_SIZE && size != RX_SIZE) || (expectedSize != null && size != expectedSize))
            throw MccProtocolException("longueur de trame")
        if ((raw[0].toInt() and 0xFF) != 0x55 || (raw[1].toInt() and 0xFF) != size)
            throw MccProtocolException("en-tête ou octet de longueur")
        val kind = if (size == TX_SIZE) 0xA1 else 0xB1
        if ((raw[2].toInt() and 0xFF) != kind) throw MccProtocolException("type de trame")
        if ((raw[size - 1].toInt() and 0xFF) != 0xAA) throw MccProtocolException("terminateur")
        if ((raw[size - 2].toInt() and 0xFF) != checksum(raw, size - 2)) throw MccProtocolException("somme de contrôle")
    }

    fun actualSpeedLevel(rpm: Int): Int {
        if (rpm <= 0) return 0
        val upper = intArrayOf(558, 1008, 1437, 3359, 6349, 9309, 12312, 15315, 18232)
        for (i in upper.indices) if (rpm <= upper[i]) return i + 1
        return 10
    }

    fun decodeRx(raw: ByteArray): Telemetry {
        validate(raw, RX_SIZE)
        fun u(i: Int) = raw[i].toInt() and 0xFF
        fun word(i: Int) = (u(i) shl 8) or u(i + 1)
        val status = u(3)
        val mode = status and 3
        val error = status and 0x10 != 0
        val warnings = ArrayList<String>(0)
        if (u(6) > 10) warnings += "Niveau de vitesse cible hors 0..10"
        if (u(11) > 19) warnings += "Niveau de température cible hors 0..19"
        if (u(4) !in intArrayOf(0, 3, 4, 5)) warnings += "État étendu inconnu"
        if (error && u(5) !in ERROR_MAP) warnings += "Erreur non répertoriée"
        if (!error && u(5) != 0) warnings += "Code d'erreur sans drapeau d'erreur"
        if (u(22) and 0xF8 != 0) warnings += "Bits de balance inconnus"
        if (u(22) and 7 == 0) warnings += "Aucun drapeau de balance"
        if (u(23) !in intArrayOf(0, 1, 2, 3, 4, 5, 7)) warnings += "Étape de calibration inconnue"
        val scale = when {
            u(22) and 4 != 0 -> "overload"
            u(22) and 1 != 0 -> "calibration"
            u(22) and 2 != 0 -> "normal"
            else -> "not_available"
        }
        val weight = (u(16) shl 24) or (u(17) shl 16) or (u(18) shl 8) or u(19)
        return Telemetry(
            raw = raw.copyOf(),
            status = status,
            motorHeaterState = mode,
            motorOn = mode == 1 || mode == 3,
            heaterOn = mode == 1 || mode == 2,
            scaleStable = status and 0x04 != 0,
            direction = if (status and 0x08 != 0) Direction.CLOCKWISE else Direction.COUNTER_CLOCKWISE,
            errorDetected = error,
            errorCode = u(5),
            androidErrorCode = if (error) ERROR_MAP[u(5)] ?: 6 else 0,
            aboveTargetTemperature = status and 0x20 != 0,
            bowlNtcDetected = status and 0x40 != 0,
            lidOpen = status and 0x80 != 0,
            extendedState = u(4),
            targetSpeedLevel = u(6),
            targetRpm = word(7),
            actualRpm = word(9),
            targetTemperatureLevel = u(11),
            targetTemperature = word(12),
            actualTemperature = word(14),
            weight = weight,
            firmwareVersion = word(20),
            scaleStateRaw = u(22),
            scaleState = scale,
            calibrationStep = u(23),
            unknownByte24 = u(24),
            warnings = warnings,
        )
    }

    /** Message lisible pour un code d'erreur brut de la base. */
    fun boardErrorMessage(code: Int): String = when (code) {
        1 -> tr("Erreur de communication signalée par la base")
        3 -> tr("Surtension secteur")
        4 -> tr("Sous-tension secteur")
        5 -> tr("Erreur moteur")
        6 -> tr("Pas d'eau (vapeur)")
        7 -> tr("Moteur en surchauffe : laissez refroidir")
        8 -> tr("Durée maximale dépassée")
        else -> tr("Erreur de la base (code %d)", code)
    }
}

/**
 * Découpage robuste d'un flux d'octets : réassemble les trames fragmentées, accepte les
 * trames collées et se resynchronise après des octets corrompus. Garde au plus 26 octets.
 */
class MccStreamParser(private val expectedSize: Int = Mcc.RX_SIZE) {
    init {
        require(expectedSize == Mcc.TX_SIZE || expectedSize == Mcc.RX_SIZE)
    }

    private val buffer = ByteArray(expectedSize)
    private var count = 0
    var discardedBytes = 0L
        private set
    var invalidFrames = 0L
        private set
    val bufferedBytes: Int get() = count

    fun reset() {
        count = 0
    }

    private fun dropFirst() {
        System.arraycopy(buffer, 1, buffer, 0, count - 1)
        count--
        discardedBytes++
    }

    fun feed(data: ByteArray, length: Int = data.size, out: MutableList<ByteArray> = ArrayList(2)): MutableList<ByteArray> {
        val kind = if (expectedSize == Mcc.TX_SIZE) 0xA1 else 0xB1
        for (i in 0 until length) {
            buffer[count++] = data[i]
            while (count > 0) {
                if ((buffer[0].toInt() and 0xFF) != 0x55) { dropFirst(); continue }
                if (count < 2) break
                if ((buffer[1].toInt() and 0xFF) != expectedSize) { invalidFrames++; dropFirst(); continue }
                if (count < 3) break
                if ((buffer[2].toInt() and 0xFF) != kind) { invalidFrames++; dropFirst(); continue }
                if (count < expectedSize) break
                val candidate = buffer.copyOf(expectedSize)
                try {
                    Mcc.validate(candidate, expectedSize)
                } catch (e: MccProtocolException) {
                    invalidFrames++
                    dropFirst()
                    continue
                }
                out.add(candidate)
                count = 0
            }
        }
        return out
    }
}
