# Marstek Venus-A Telegram Monitor Bot

A Kotlin-based Telegram bot that monitors a **Marstek Venus-A** home battery system via its Local UDP Open API. It sends notifications when the grid goes down/restores and tracks battery levels during outages. Supports a `/status` command for on-demand status checks.

---

## Prerequisites

- Docker and Docker Compose installed
- Marstek Venus-A device with **Open API (UDP)** enabled in the Marstek app
- A **static IP address** assigned to the Venus-A on your LAN
- A Telegram bot token (created via BotFather)
- Your Telegram chat ID

---

## Setup

### 1. Enable Open API on the Marstek device

In the Marstek app, navigate to **Settings → Open API** and enable UDP access. Note the UDP port (default: `30000`).

### 2. Create a Telegram bot

1. Open Telegram and search for `@BotFather`
2. Send `/newbot` and follow the prompts
3. Copy the token (format: `123456:ABC-xxxx`)

### 3. Get your Telegram chat ID

1. Send any message to your new bot
2. Visit `https://api.telegram.org/bot<YOUR_TOKEN>/getUpdates`
3. Find `"chat":{"id": XXXXXXX}` — that number is your chat ID

### 4. Configure the bot

```bash
cp config.yml.example config.yml
```

Edit `config.yml`:

```yaml
bot:
  tg-bot-token: "123456:ABC-your-bot-token"
  tg-chat-id: "123456789"

marstek:
  device-ip: "192.168.1.100"   # Static IP of your Venus-A
  udp-port: 30000               # UDP port from the Marstek app
  poll-interval-seconds: 30     # Polling frequency in seconds
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
| `/status` | Show current grid state and battery level (live from device) |

---

## Notifications

The bot automatically sends messages to the configured chat ID when:

| Event | Trigger |
|-------|---------|
| **Power outage** | Grid transitions from connected → disconnected |
| **Grid restored** | Grid transitions from disconnected → connected |
| **Battery at X%** | SOC crosses a 10% threshold downward during an outage |

---

## Architecture

```
src/main/kotlin/com/mthkr/
├── Main.kt                      ← Entry point
├── config/Config.kt             ← YAML config parsing
├── marstek/
│   ├── MarstekClient.kt         ← UDP client (JSON-RPC)
│   └── MarstekModels.kt         ← Serialization models
├── monitor/GridMonitor.kt       ← Polling loop + state machine
├── bot/BotHandler.kt            ← Telegram bot + /status handler
└── notifications/Notifier.kt   ← Telegram message sender
```

---

## Notes

- Uses `network_mode: host` in Docker so the container can reach the Marstek device over UDP on your LAN
- Long polling is used (no webhook required)
- Only the configured `tg-chat-id` receives notifications; `/status` is also restricted to that chat
- If the device is unreachable during a poll cycle, the grid state is **not** changed — the error is logged and the cycle is skipped
