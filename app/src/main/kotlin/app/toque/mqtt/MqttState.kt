package app.toque.mqtt

import app.toque.core.json.Json
import app.toque.core.machine.JobState
import app.toque.core.machine.Snapshot
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Sous-ensemble du [Snapshot] utile pour l'automation (programme + fin), sans capteurs/actuateurs.
 * Deux états égaux ⇒ pas de republication.
 *
 * [targetTempC] : consigne du job ([Snapshot.jobTempC], pas l'écho base ni la table Connect).
 * [expectedEndAt] : instant de fin prévu (ISO-8601 UTC). Absent en pause (sinon l'heure « avance »
 * pendant que le programme est arrêté) ; le publisher le « colle » pendant le décompte.
 */
data class MqttState(
    val actualTempC: Int,
    val targetTempC: Int,
    val jobActive: Boolean,
    val jobKind: String,
    val jobLabel: String,
    val jobState: String,
    /** Instant de fin prévu, ou `null` si pas de job / pause / durée inconnue / terminé. */
    val expectedEndAt: String?,
    val endReason: String,
    val faultCode: String,
    val faultMessage: String,
) {
    fun toJson(): String = Json.write(
        linkedMapOf(
            "actualTempC" to actualTempC,
            "targetTempC" to targetTempC,
            "jobActive" to jobActive,
            "jobKind" to jobKind,
            "jobLabel" to jobLabel,
            "jobState" to jobState,
            "expectedEndAt" to expectedEndAt,
            "endReason" to endReason,
            "faultCode" to faultCode,
            "faultMessage" to faultMessage,
        )
    )

    companion object {
        private val isoUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        fun formatEnd(ms: Long): String = synchronized(isoUtc) { isoUtc.format(ms) }

        fun parseEnd(iso: String): Long? = synchronized(isoUtc) {
            try {
                isoUtc.parse(iso)?.time
            } catch (_: Exception) {
                null
            }
        }

        fun from(s: Snapshot, nowMs: Long = System.currentTimeMillis()): MqttState {
            val heating = s.jobActive && s.jobTempC > 0
            val paused = s.jobState == JobState.PAUSED.name
            // remainingMs == 0 en FINISHED feria expectedEndAt = now → 1 publish/s ; només amb temps > 0.
            // En pause : null (HA veu jobState=PAUSED ; no cal un rellotge que es mogui).
            val endAt = if (s.jobActive && !paused && s.remainingMs > 0) {
                val remSec = (s.remainingMs + 999) / 1000
                val endMs = ((nowMs / 1000L) + remSec) * 1000L
                formatEnd(endMs)
            } else {
                null
            }
            return MqttState(
                actualTempC = s.actualTempC,
                targetTempC = if (heating) s.jobTempC else 0,
                jobActive = s.jobActive,
                jobKind = s.jobKind,
                jobLabel = s.jobLabel,
                jobState = s.jobState,
                expectedEndAt = endAt,
                endReason = s.endReason,
                faultCode = s.faultCode,
                faultMessage = s.faultMessage,
            )
        }
    }
}
