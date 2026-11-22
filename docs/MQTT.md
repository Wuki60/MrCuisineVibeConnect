# MQTT / Home Assistant (Toque)

Read-only telemetry for Home Assistant (or any MQTT broker). **No commands** are accepted over MQTT.

## Behaviour

- Settings › **MQTT**: enable, host, port, user, password, TLS, topic prefix.
- Default prefix: `toque/<ANDROID_ID>/`.
- Topics (retain):
  - `…/status` — `online` / `offline` (LWT = `offline`)
  - `…/state` — JSON snapshot (see below)
- Publishes **only when the useful state changes** (no weight, no raw RPM).
- On connect: publishes **Home Assistant MQTT Discovery** configs under `homeassistant/…/config` (retain), then entities appear under device **Toque** without YAML.
- Discovery **entity names** follow Toque’s UI language (`tr(…)` → `en` / `es` / `de` / `ca` / …). `unique_id`s stay stable (English). Changing the UI language republishes Discovery.

## State JSON fields

Focused on the running program and its end (no lid/bowl/motor/heater/link).

| Field | Meaning |
|---|---|
| `jobActive` / `jobKind` / `jobLabel` / `jobState` | Program |
| `remainingSec` | Seconds left (`0` when done) |
| `endReason` | Why it ended (empty while running) |
| `actualTempC` / `targetTempC` | Temperatures |
| `speedLevel` | Blade speed 0–10 |
| `faultCode` / `faultMessage` | Alerts |

`jobState` values include `RUNNING`, `PAUSED`, `FINISHED`, `ABORTED`, … HA automations typically watch `jobState` → `FINISHED` or `cooking` → off.

## Files (MQTT / HA)

| Path | Role |
|---|---|
| `app/src/main/kotlin/app/toque/mqtt/MqttClient.kt` | Minimal MQTT 3.1.1 client (publish only) |
| `app/src/main/kotlin/app/toque/mqtt/MqttState.kt` | Quantised state → JSON |
| `app/src/main/kotlin/app/toque/mqtt/MqttPublisher.kt` | On-change publish, LWT, reconnect |
| `app/src/main/kotlin/app/toque/mqtt/MqttDiscovery.kt` | HA MQTT Discovery payloads |
| `app/src/main/kotlin/app/toque/Prefs.kt` | `mqttEnabled`, host, port, user, password, TLS, prefix, deviceId |
| `app/src/main/kotlin/app/toque/ToqueApp.kt` | Starts publisher on main process |
| `app/src/main/kotlin/app/toque/ui/screens/SettingsScreen.kt` | MQTT settings UI |
| `app/src/main/assets/lang/{en,es,de}.json` | MQTT / Discovery strings |
