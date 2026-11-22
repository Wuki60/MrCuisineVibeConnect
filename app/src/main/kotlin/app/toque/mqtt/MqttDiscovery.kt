package app.toque.mqtt

import app.toque.core.i18n.tr
import app.toque.core.json.Json

/**
 * Configurations MQTT Discovery pour Home Assistant (lecture seule).
 * Les [name] suivent la langue de l'interface ([tr]) ; les unique_id restent stables (anglais).
 */
object MqttDiscovery {

    fun configs(deviceId: String, statusTopic: String, stateTopic: String): List<Pair<String, String>> {
        val node = sanitize(deviceId)
        val device = linkedMapOf(
            "identifiers" to listOf("toque_$node"),
            "name" to "Toque",
            "manufacturer" to "Toque",
            "model" to "Monsieur Cuisine Connect",
        )
        fun avail() = linkedMapOf(
            "availability_topic" to statusTopic,
            "payload_available" to "online",
            "payload_not_available" to "offline",
        )
        fun sensor(
            objectId: String,
            name: String,
            template: String,
            unit: String? = null,
            deviceClass: String? = null,
            stateClass: String? = null,
        ): Pair<String, String> {
            val body = linkedMapOf<String, Any?>(
                "name" to name,
                "unique_id" to "toque_${node}_$objectId",
                "state_topic" to stateTopic,
                "value_template" to template,
                "device" to device,
            )
            body.putAll(avail())
            if (unit != null) body["unit_of_measurement"] = unit
            if (deviceClass != null) body["device_class"] = deviceClass
            if (stateClass != null) body["state_class"] = stateClass
            return "homeassistant/sensor/toque_$node/$objectId/config" to Json.write(body)
        }
        fun binary(
            objectId: String,
            name: String,
            template: String,
            deviceClass: String? = null,
        ): Pair<String, String> {
            val body = linkedMapOf<String, Any?>(
                "name" to name,
                "unique_id" to "toque_${node}_$objectId",
                "state_topic" to stateTopic,
                "value_template" to "{{ 'ON' if ($template) else 'OFF' }}",
                "payload_on" to "ON",
                "payload_off" to "OFF",
                "device" to device,
            )
            body.putAll(avail())
            if (deviceClass != null) body["device_class"] = deviceClass
            return "homeassistant/binary_sensor/toque_$node/$objectId/config" to Json.write(body)
        }

        val out = ArrayList<Pair<String, String>>(12)
        out += "homeassistant/binary_sensor/toque_$node/online/config" to Json.write(
            linkedMapOf(
                "name" to tr("En ligne"),
                "unique_id" to "toque_${node}_online",
                "state_topic" to statusTopic,
                "payload_on" to "online",
                "payload_off" to "offline",
                "device_class" to "connectivity",
                "device" to device,
            )
        )
        // Programme / fin — le cœur utile pour les automations HA.
        out += sensor("job_label", tr("Programme"), "{{ value_json.jobLabel }}")
        out += sensor("job_state", tr("État du programme"), "{{ value_json.jobState }}")
        out += sensor("job_kind", tr("Type de programme"), "{{ value_json.jobKind }}")
        out += sensor("remaining", tr("Temps restant"), "{{ value_json.remainingSec }}", "s", stateClass = "measurement")
        out += sensor("end_reason", tr("Fin du programme"), "{{ value_json.endReason }}")
        out += binary("cooking", tr("En cours de cuisson"), "value_json.jobActive", "running")
        // Contexte pendant le programme.
        out += sensor("temperature", tr("Température"), "{{ value_json.actualTempC }}", "°C", "temperature", "measurement")
        out += sensor("target_temperature", tr("Température cible"), "{{ value_json.targetTempC }}", "°C", "temperature")
        out += sensor("speed", tr("Vitesse"), "{{ value_json.speedLevel }}")
        out += sensor("fault_code", tr("Code d'alerte"), "{{ value_json.faultCode }}")
        out += sensor("fault_message", tr("Alerte"), "{{ value_json.faultMessage }}")
        return out
    }

    private fun sanitize(id: String): String =
        id.lowercase().replace(Regex("[^a-z0-9_-]"), "_").ifEmpty { "robot" }
}
