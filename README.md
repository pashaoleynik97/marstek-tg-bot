# Marstek Venus-A + Aninerel Inverter Telegram Monitor Bot

A Kotlin-based Telegram bot that monitors:

- an **Aninerel / Anenji hybrid inverter** (via its EyBond Wi-Fi dongle, locally over LAN) — grid outage/restore
  detection, notifications and outage statistics stored in SQLite;
- a **Marstek Venus-A** home battery via its Local UDP Open API — shown in `/status`.

Commands: `/status`, `/stats` (outage summary / statistics), `/menu` (button bar).

---

## Prerequisites

- Docker and Docker Compose installed
- Marstek Venus-A device with **Open API (UDP)** enabled in the Marstek app
- A **static IP address** assigned to the Venus-A on your LAN
- Aninerel/Anenji inverter with the EyBond "Wi-Fi Plug Pro" dongle, with a **DHCP reservation** for the dongle
- TCP port **8899** free on the host and reachable from the dongle (allow it in the host firewall)
- A Telegram bot token (created via BotFather)
- Your Telegram chat ID

---

## Setup

### 1. Enable Open API on the Marstek device

In the Marstek app, navigate to **Settings → Open API** and enable UDP access. Note the UDP port (default: `30000`).

### 2. Inverter dongle (no app changes needed)

The dongle never accepts incoming connections. Instead the bot opens a TCP server (port `8899`) and the dongle
connects **to the bot** after a UDP pairing command. The bot does this automatically on startup and whenever the
session is lost:

1. Waits up to 5 times (~6 s each) for the dongle to connect by itself (it remembers the bot after a previous pairing).
2. If it doesn't, sends the pairing command to `<dongle-ip>:58899` — the equivalent of
   `echo -n "set>server=<server-ip>:8899;" | nc -u -w2 <dongle-ip> 58899`.
   The server IP is detected automatically (the host's LAN address used to reach the dongle).
3. Probes 5 more times. If it still fails, a ⚠️ message is sent to Telegram and the bot retries every 60 s;
   a ✅ message is sent once the connection is back.

The local session runs **in parallel** with the SmartESS cloud — the vendor app keeps working.
Only one local client can be connected at a time, so don't run other local tools (probe script, HomeGrid app)
while the bot is running.

### 3. Create a Telegram bot

1. Open Telegram and search for `@BotFather`
2. Send `/newbot` and follow the prompts
3. Copy the token (format: `123456:ABC-xxxx`)

### 4. Get your Telegram chat ID

1. Send any message to your new bot
2. Visit `https://api.telegram.org/bot<YOUR_TOKEN>/getUpdates`
3. Find `"chat":{"id": XXXXXXX}` — that number is your chat ID

### 5. Configure the bot

```bash
cp config.yml.example config.yml
```

Edit `config.yml`:

```yaml
bot:
  tg-bot-token: "123456:ABC-your-bot-token"
  tg-chat-ids:
    - 123456789

timezone: "Europe/Kyiv"          # stats day boundaries and times (default Europe/Kyiv)

inverter:
  dongle-ip: "192.168.0.100"     # static IP of the EyBond dongle
  dongle-udp-port: 58899          # optional, default 58899
  tcp-port: 8899                  # optional, default 8899
  modbus-slave: 1                 # optional, default 1
  poll-interval-seconds: 10       # optional, default 10
  grid-voltage-threshold: 170     # optional; grid present when grid voltage >= this (V)

marstek:
  device-ip: "192.168.1.100"   # Static IP of your Venus-A
  udp-port: 30000               # UDP port from the Marstek app

database:
  db-path: "data/grid_state.db" # optional
```

> `config.yml` is gitignored — never commit it.

---

## Running

### Build and start

```bash
docker-compose up --build -d
```

### View logs

```bash
docker logs -f marstek-venus-bot
```

### Stop

```bash
docker-compose down
```

---

## Bot Commands

| Command | Description |
|---------|-------------|
| `/status` | Inverter (mode, grid, output, battery, PV, temps) + Marstek (battery, power) status |
| `/stats` | Inline menu: **Summary** / **Statistic** × **Today** / **7 days** / **Month** |
| `/menu` | Opens the bottom button bar: `📊 Status`, `📈 Stats` → `📋 Summary` / `📅 Statistic` → `Today` / `7 days` / `Month` (with `⬅️ Back`) |

### Stats

- Periods: **this day** = today 00:00 → now; **last 7 days** = today + 6 previous days; **last month** = today + 29 previous days
  (all in the configured timezone, starting no earlier than the first recorded data).
- **Summary**: `There were X hours Y minutes of outages during <period>. Time without outages: X hours Y minutes.`
  (plus `❔ No data: …` if there were gaps).
- **Statistic**: an infographic (1080×1080 PNG, rendered in memory with Java2D, never written to disk) followed by text:
  - **Today**: pie (outage vs presence) + 00:00–24:00 timeline; text lists `❌ Outage: HH:mm-HH:mm (Xh Ym)`,
    `⚡ Presence: …`, `❔ No data: …` (time when the bot was down or the inverter was unreachable).
  - **7 days**: 7 timelines (newest on top); text = totals with outage/presence % + per-day timeline (newest first).
  - **Month**: pie; text = `Outages from DD-Month-YYYY to DD-Month-YYYY`, totals, per-day outage/presence durations.

Preview the infographics with synthetic data (on demand, not part of `./gradlew test`):

```bash
./gradlew previewInfographics && open build/infographics
```

---

## Notifications

Notifications are based on **inverter** data (Marstek is no longer used for outage detection).
Grid presence = inverter grid voltage ≥ `grid-voltage-threshold`; a change must be seen in 2 consecutive polls.

| Event | Trigger |
|-------|---------|
| **Power outage** | Grid transitions from present → absent (with how long electricity was available) |
| **Grid restored** | Grid transitions from absent → present (with outage duration) |
| **Battery at X%** | Inverter battery SOC crosses a 10% threshold downward during an outage |
| **Inverter not responding** | Setup sequence (probe → pairing → probe) failed |
| **Inverter connection restored** | Session is back after the alert above |
| **Bot online** | Bot started (with offline duration) |

If the grid state changed while the bot was down, the transition is reported on the first polls after startup.

---

## Architecture

```
src/main/kotlin/com/mthkr/
├── Main.kt                          ← Entry point / wiring
├── config/Config.kt                 ← YAML config parsing
├── marstek/
│   ├── MarstekClient.kt             ← Marstek UDP client (JSON-RPC)
│   └── MarstekModels.kt             ← Serialization models
├── inverter/
│   ├── EybondProtocol.kt            ← EyBond framing + Modbus RTU (CRC, read registers)
│   ├── InverterConnection.kt        ← TCP server, pairing, probing, serialized requests
│   └── InverterSnapshot.kt          ← Register 201..234 decoding
├── monitor/
│   ├── InverterMonitor.kt           ← Polling loop, outage detection, alerts
│   └── GridState.kt
├── db/
│   ├── InverterStateRepository.kt   ← inverter_grid_intervals table (outage history)
│   └── BotOnlineRepository.kt       ← last-online timestamp
├── stats/
│   ├── Timeline.kt                  ← Builds outage/presence/no-data segments
│   ├── StatsService.kt              ← /stats texts + StatsReport read model
│   └── InfographicRenderer.kt       ← Java2D pie/timeline PNGs (in memory)
├── bot/BotHandler.kt                ← /status, /stats, /menu handlers + keyboards
└── notifications/Notifier.kt        ← Telegram message sender
```

### Database

SQLite file (`data/grid_state.db` by default). Inverter history lives in `inverter_grid_intervals`
(`state` CONNECTED/DISCONNECTED, `start_ts`, `end_ts` in epoch ms, `start_soc`). Each successful poll extends the
current interval; time not covered by any interval is reported as "No data". The legacy Marstek table
`grid_state_registry` is no longer written to.

---

## Notes

- Uses `network_mode: host` in Docker so the container can reach the devices on your LAN and the dongle can connect to TCP 8899
- Inverter protocol details: `.claude/aninerel/sources-and-findings/anenji-inverter-local-protocol.md` (local docs)
- Long polling is used (no webhook required)
- Only the configured `tg-chat-ids` receive notifications; all commands are restricted to those chats
- If the inverter is unreachable, the grid state is **not** changed — the period shows as "No data" in stats
