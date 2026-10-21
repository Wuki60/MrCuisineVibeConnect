package app.toque.core.machine

/**
 * État complet et immuable de la machine, envoyé à l'interface ~10 fois par seconde.
 * [toMap]/[fromMap] servent au passage entre processus (Bundle Android).
 */
data class Snapshot(
    val link: LinkState = LinkState.OFFLINE,
    val transportName: String = "",
    val transportUp: Boolean = false,
    val faultCode: String = "",
    val faultMessage: String = "",
    val hasSample: Boolean = false,
    val sampleAgeMs: Long = -1,
    val actualTempC: Int = 0,
    val targetTempC: Int = 0,
    val rpm: Int = 0,
    val speedLevel: Int = 0,
    val weightG: Int = 0,
    val scaleStable: Boolean = false,
    val scaleState: String = "",
    val lidOpen: Boolean = false,
    val bowlDetected: Boolean = false,
    val motorOn: Boolean = false,
    val heaterOn: Boolean = false,
    val boardErrorCode: Int = 0,
    val firmware: Int = 0,
    val rxHex: String = "",
    val jobActive: Boolean = false,
    val jobKind: String = "",
    val jobLabel: String = "",
    val jobState: String = "",
    val pauseReason: String = "",
    val endReason: String = "",
    val phaseIndex: Int = 0,
    val phaseCount: Int = 0,
    val elapsedMs: Long = 0,
    val totalMs: Long = 0,
    val remainingMs: Long = 0,
    val waitingStop: Boolean = false,
    val jobSpeed: Int = 0,
    val jobTempLevel: Int = 0,
    val jobReverse: Boolean = false,
    val accessory: String = Accessory.BLADE.name,
    val hotBlendConfirmed: Boolean = false,
    val turboActive: Boolean = false,
    val wireOperation: String = "STOP",
    val wireSpeed: Int = 0,
    val wireTempLevel: Int = 0,
    val txHex: String = "",
    val txFrames: Long = 0,
    val rxFrames: Long = 0,
    val invalidFrames: Long = 0,
) {
    val ready: Boolean get() = link == LinkState.READY && faultCode.isEmpty()
    val hasFault: Boolean get() = faultCode.isNotEmpty()
    val state: JobState? get() = if (jobState.isEmpty()) null else JobState.valueOf(jobState)
    val kind: JobKind? get() = if (jobKind.isEmpty()) null else JobKind.valueOf(jobKind)
    val running: Boolean get() = state == JobState.RUNNING || state == JobState.STARTING || state == JobState.STOPPING
    val busy: Boolean get() = running || state == JobState.PAUSED || turboActive
    val hot: Boolean get() = hasSample && actualTempC >= Limits.HOT_C

    fun toMap(): Map<String, Any> = linkedMapOf(
        "link" to link.name, "transportName" to transportName, "transportUp" to transportUp,
        "faultCode" to faultCode, "faultMessage" to faultMessage, "hasSample" to hasSample,
        "sampleAgeMs" to sampleAgeMs, "actualTempC" to actualTempC, "targetTempC" to targetTempC,
        "rpm" to rpm, "speedLevel" to speedLevel, "weightG" to weightG, "scaleStable" to scaleStable,
        "scaleState" to scaleState, "lidOpen" to lidOpen, "bowlDetected" to bowlDetected,
        "motorOn" to motorOn, "heaterOn" to heaterOn, "boardErrorCode" to boardErrorCode,
        "firmware" to firmware, "rxHex" to rxHex, "jobActive" to jobActive, "jobKind" to jobKind,
        "jobLabel" to jobLabel, "jobState" to jobState, "pauseReason" to pauseReason,
        "endReason" to endReason, "phaseIndex" to phaseIndex, "phaseCount" to phaseCount,
        "elapsedMs" to elapsedMs, "totalMs" to totalMs, "remainingMs" to remainingMs,
        "waitingStop" to waitingStop, "jobSpeed" to jobSpeed, "jobTempLevel" to jobTempLevel,
        "jobReverse" to jobReverse, "accessory" to accessory, "hotBlendConfirmed" to hotBlendConfirmed,
        "turboActive" to turboActive, "wireOperation" to wireOperation, "wireSpeed" to wireSpeed,
        "wireTempLevel" to wireTempLevel, "txHex" to txHex, "txFrames" to txFrames,
        "rxFrames" to rxFrames, "invalidFrames" to invalidFrames,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): Snapshot {
            fun s(k: String) = m[k] as? String ?: ""
            fun i(k: String) = (m[k] as? Number)?.toInt() ?: 0
            fun l(k: String) = (m[k] as? Number)?.toLong() ?: 0L
            fun b(k: String) = m[k] as? Boolean ?: false
            return Snapshot(
                link = runCatching { LinkState.valueOf(s("link")) }.getOrDefault(LinkState.OFFLINE),
                transportName = s("transportName"), transportUp = b("transportUp"),
                faultCode = s("faultCode"), faultMessage = s("faultMessage"), hasSample = b("hasSample"),
                sampleAgeMs = l("sampleAgeMs"), actualTempC = i("actualTempC"), targetTempC = i("targetTempC"),
                rpm = i("rpm"), speedLevel = i("speedLevel"), weightG = i("weightG"), scaleStable = b("scaleStable"),
                scaleState = s("scaleState"), lidOpen = b("lidOpen"), bowlDetected = b("bowlDetected"),
                motorOn = b("motorOn"), heaterOn = b("heaterOn"), boardErrorCode = i("boardErrorCode"),
                firmware = i("firmware"), rxHex = s("rxHex"), jobActive = b("jobActive"), jobKind = s("jobKind"),
                jobLabel = s("jobLabel"), jobState = s("jobState"), pauseReason = s("pauseReason"),
                endReason = s("endReason"), phaseIndex = i("phaseIndex"), phaseCount = i("phaseCount"),
                elapsedMs = l("elapsedMs"), totalMs = l("totalMs"), remainingMs = l("remainingMs"),
                waitingStop = b("waitingStop"), jobSpeed = i("jobSpeed"), jobTempLevel = i("jobTempLevel"),
                jobReverse = b("jobReverse"), accessory = s("accessory").ifEmpty { Accessory.BLADE.name },
                hotBlendConfirmed = b("hotBlendConfirmed"), turboActive = b("turboActive"),
                wireOperation = s("wireOperation"), wireSpeed = i("wireSpeed"), wireTempLevel = i("wireTempLevel"),
                txHex = s("txHex"), txFrames = l("txFrames"), rxFrames = l("rxFrames"), invalidFrames = l("invalidFrames"),
            )
        }
    }
}
