package app.toque.mqtt

import app.toque.core.json.Json
import app.toque.core.machine.Snapshot

/**
 * Sous-ensemble stable du [Snapshot] publié en MQTT (sans pesée ni RPM bruts).
 * Deux états égaux ⇒ pas de republication.
 */
data class MqttState(
    val link: String,
    val actualTempC: Int,
    val targetTempC: Int,
    val speedLevel: Int,
    val lidOpen: Boolean,
    val bowlDetected: Boolean,
    val motorOn: Boolean,
    val heaterOn: Boolean,
    val jobActive: Boolean,
    val jobKind: String,
    val jobLabel: String,
    val jobState: String,
    val remainingSec: Long,
    val faultCode: String,
    val faultMessage: String,
) {
    fun toJson(): String = Json.write(
        linkedMapOf(
            "link" to link,
            "actualTempC" to actualTempC,
            "targetTempC" to targetTempC,
            "speedLevel" to speedLevel,
            "lidOpen" to lidOpen,
            "bowlDetected" to bowlDetected,
            "motorOn" to motorOn,
            "heaterOn" to heaterOn,
            "jobActive" to jobActive,
            "jobKind" to jobKind,
            "jobLabel" to jobLabel,
            "jobState" to jobState,
            "remainingSec" to remainingSec,
            "faultCode" to faultCode,
            "faultMessage" to faultMessage,
        )
    )

    companion object {
        fun from(s: Snapshot): MqttState = MqttState(
            link = s.link.name,
            actualTempC = s.actualTempC,
            targetTempC = s.targetTempC,
            speedLevel = s.speedLevel,
            lidOpen = s.lidOpen,
            bowlDetected = s.bowlDetected,
            motorOn = s.motorOn,
            heaterOn = s.heaterOn,
            jobActive = s.jobActive,
            jobKind = s.jobKind,
            jobLabel = s.jobLabel,
            jobState = s.jobState,
            remainingSec = if (s.remainingMs < 0) -1 else (s.remainingMs + 999) / 1000,
            faultCode = s.faultCode,
            faultMessage = s.faultMessage,
        )
    }
}
