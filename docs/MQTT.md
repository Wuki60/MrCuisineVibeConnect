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
| `expectedEndAt` | Planned end time (ISO-8601 UTC), or `null` when idle / unknown. Stable while the countdown ticks → few publishes |
| `endReason` | Why it ended (empty while running) |
| `actualTempC` | Measured temperature |
| `targetTempC` | Job setpoint (°C from `jobTempLevel`), `0` when not heating |
| `faultCode` / `faultMessage` | Alerts |

No blade speed: `jobActive` / `jobState` are enough to know something is running.

`jobState` values include `RUNNING`, `PAUSED`, `FINISHED`, `ABORTED`, … HA automations typically watch `jobState` → `FINISHED` or `cooking` → off.

HA can show remaining time from `expectedEndAt` (e.g. template sensor with `as_timestamp(states('…')) - as_timestamp(now())`).

## Counting publishes (test traffic)

Subscribe to the state topic and count messages while you cook. Same idea as HA would see.

**GUI (easiest):** [MQTT Explorer](https://mqtt-explorer.com/) (desktop) — connect to your broker, open `toque/<id>/state`, watch the message list / history. Or HA › Developer tools › MQTT › Listen to a topic.

**CLI:** Mosquitto clients (`brew install mosquitto` / `apt install mosquitto-clients`):

```bash
# Live stream (retain + every change)
mosquitto_sub -h <broker> -p 1883 -u <user> -P <pass> -t 'toque/+/state' -v

# Count for 5 minutes (exclude the retained first message if you want only live traffic)
timeout 300 mosquitto_sub -h <broker> -t 'toque/+/state' -C 9999 | tee /tmp/mqtt-state.txt | wc -l
```

With `expectedEndAt` stable, a 5‑minute manual cook at fixed temp/speed should produce **a handful** of `…/state` messages (start, temp steps if they change, end) — not one per second.

**Programs to run on the robot (empty bowl, short durations):**

1. Manual · heat only · ~2–3 min · e.g. 60 °C, speed 0 — checks `targetTempC` / `actualTempC` / `expectedEndAt`.
2. Manual · speed only · ~1 min · speed 2, no heat — checks `speedLevel` without heating noise.
3. Manual · heat + speed · ~3 min — combined; pause once mid-run to see `jobState` / `PAUSED`.
4. Let it finish normally once — `jobState` → `FINISHED`, `expectedEndAt` → `null`, `endReason` set.

Do **not** use long Lidl recipes for a traffic test; short manual jobs are enough.

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
| `app/src/main/assets/lang/{en,es,de,ca}.json` | MQTT / Discovery strings |
