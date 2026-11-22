package app.toque.mqtt

import app.toque.core.json.Json
import app.toque.core.machine.Snapshot

/**
 * Sous-ensemble du [Snapshot] utile pour l'automation (programme + fin), sans capteurs/actuateurs.
 * Deux états égaux ⇒ pas de republication.
 */
data class MqttState(
    val actualTempC: Int,
    val targetTempC: Int,
    val speedLevel: Int,
    val jobActive: Boolean,
    val jobKind: String,
    val jobLabel: String,
    val jobState: String,
    val remainingSec: Long,
    val endReason: String,
    val faultCode: String,
    val faultMessage: String,
) {
    fun toJson(): String = Json.write(
        linkedMapOf(
            "actualTempC" to actualTempC,
            "targetTempC" to targetTempC,
            "speedLevel" to speedLevel,
            "jobActive" to jobActive,
            "jobKind" to jobKind,
            "jobLabel" to jobLabel,
            "jobState" to jobState,
            "remainingSec" to remainingSec,
            "endReason" to endReason,
            "faultCode" to faultCode,
            "faultMessage" to faultMessage,
        )
    )

    companion object {
        fun from(s: Snapshot): MqttState = MqttState(
            actualTempC = s.actualTempC,
            targetTempC = s.targetTempC,
            speedLevel = s.speedLevel,
            jobActive = s.jobActive,
            jobKind = s.jobKind,
            jobLabel = s.jobLabel,
            jobState = s.jobState,
            remainingSec = if (s.remainingMs < 0) -1 else (s.remainingMs + 999) / 1000,
            endReason = s.endReason,
            faultCode = s.faultCode,
            faultMessage = s.faultMessage,
        )
    }
}
