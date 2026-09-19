# AI Disaster Response System

Offline-first disaster communication. Victims and responders in a disaster
zone can talk to each other over Bluetooth Low Energy when there is no
internet, no Wi-Fi and no mobile network.

---

## What is in here

| Folder | What it is |
|---|---|
| `android/` | The Android app (Java). BLE mesh, local chat, emergency reports, SQLite store. |
| `backend/` | FastAPI service that receives Emergency Reports, redacts PII and classifies urgency. |
| `dashboard/` | Browser command-centre view of incoming incidents. |
| `docs/` | Implementation notes. |

---

## The two message paths

These are deliberately separate, and it matters:

**Local Emergency Chat — Bluetooth only.**
A group conversation shared by every device in the same hub. No internet, no
server, no accounts. This is how people on the ground talk to each other.

**Emergency Report — main system only.**
Goes to the backend for the response centre. It never appears in the local
chat. If there is no connectivity it is stored on the device and uploaded
automatically once a connection returns.

---

## How more than two devices talk

The earlier version connected to the first device it found and stopped, which
only ever worked for a pair. It now works for any number of devices.

Every device runs a GATT server *and* advertises, so every device is
reachable. Delivery happens in rounds:

1. **Discover** — scan for ~6 seconds and collect *every* nearby node, not
   just the first one.
2. **Deliver** — visit each node in turn and hand over every message it has
   not already received.
3. **Relay** — a node that receives a message re-queues it for its *own*
   neighbours with TTL reduced by one.

Step 3 is what makes this a mesh rather than a pair of phones:

```
A  <-->  B  <-->  C  <-->  D
```

A and D are far apart and never see each other. A message from A still
reaches D, carried by B and C.

Safeguards:

- **Duplicates** are dropped by message id, so a message never loops.
- **A message is never handed back** to the device it arrived from.
- **Messages are re-offered for a few rounds**, so a device that joins late
  still receives what it missed.
- **Store and forward**: with no one in range, messages sit in the outbox and
  go out automatically as soon as a node appears.
- **Foreign hubs are relayed too.** Carrying a neighbouring hub's traffic
  costs little and extends the range of the whole network.

---

## Hubs

A hub is the group that devices share. Hub IDs look like
`DISASTER-HUB-4F9384`.

- **Scan** shows nearby hubs in a tappable list with signal strength. Nobody
  has to type an ID.
- **Create** makes a new hub, which other devices then see when they scan.
- **Enter ID** is a fallback for a hub that is known but out of range.
- **Leave** detaches the device so it can join a different hub and see
  conditions in another area.

The hub ID travels in the BLE scan response, which keeps the service UUID in
the primary advertisement and stays inside the 31-byte limit.

---

## Build the app

1. Open the `android/` folder in Android Studio.
2. Let Gradle sync. It creates `local.properties` with your SDK path.
3. Run on a device.

Toolchain: AGP 8.6.1, Gradle 8.9, JDK 17, `minSdk 26`, `compileSdk 35`.

> **Use two or more real phones.** The Android emulator does not implement BLE
> advertising, so the mesh cannot be tested there.

---

## Testing the mesh

1. Install on three or more phones.
2. **Phone A** → `CREATE` → note the hub ID → `ACTIVATE ALERT SYSTEM`.
3. **Phones B, C, D** → `SCAN HUBS` → tap A's hub → the Alert System starts.
4. Open `LOCAL EMERGENCY CHAT` on each and send messages.
5. To prove relaying: carry D out of range of A but leave it near C. Messages
   from A still arrive.

What you should see: `· queued` on a message you sent changes to
`✓ delivered` once at least one node has taken it. The header shows how many
nodes are known and how many are in radio range.

---

## Run the main system (PC) and connect the phones

This is the part that carries Emergency Reports from a phone to the
dashboard. The chat does not need any of it — it works over Bluetooth with
no internet at all.

There are two very different situations here, and it matters which one
you're in:

- **Same Wi-Fi as the PC** (testing, a command post with its own network) —
  the phone finds the backend automatically. Nothing to configure.
- **A different network entirely** (mobile data, a hotspot, a victim
  somewhere else) — this is the real disaster scenario, and it needs a
  backend address that's reachable from the *internet*, not just from one
  Wi-Fi network. See "Reaching the backend from anywhere" below.

### 1. Start the backend

```bash
cd backend
python -m venv .venv
# Windows: .venv\Scripts\activate
# macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt

python run_server.py
```

On Windows you can just double-click `start_backend.bat`.

**Use `run_server.py`, not `uvicorn main:app --reload`.** Plain uvicorn
binds to `127.0.0.1`, which is reachable only from the PC itself. That
single detail is the most common reason a phone reports
"Main system unreachable". `run_server.py` binds every interface and
prints every address the PC has — if you have a VPN, Docker, Hyper-V, or
WSL installed, more than one address is normal; try each if the first
doesn't work.

### 2. Open the dashboard

- On the PC: `http://127.0.0.1:8000/`
- From a phone on the same Wi-Fi: `http://<your-pc-ip>:8000/`

The dashboard is served by the backend, so it refreshes itself every few
seconds and needs no separate file.

### 3. Same-Wi-Fi: the phone finds the PC by itself

The backend answers a UDP broadcast on port 8765 with its own address, and
the app listens for that answer and binds the request to the phone's Wi-Fi
specifically — so it still works even if the phone also has mobile data
turned on, which otherwise silently wins and routes the request nowhere
useful.

If it does not connect, open **Settings / Privacy → TEST CONNECTION** in
the app. It reports exactly what failed rather than a generic error.

### 4. Same-Wi-Fi troubleshooting

- **Windows Firewall** blocks the port by default. From an **admin**
  PowerShell:

  ```powershell
  New-NetFirewallRule -DisplayName "Disaster 8000" -Direction Inbound -Protocol TCP -LocalPort 8000 -Action Allow
  New-NetFirewallRule -DisplayName "Disaster 8765" -Direction Inbound -Protocol UDP -LocalPort 8765 -Action Allow
  ```

- **Check reachability** from the phone's browser:
  `http://<your-pc-ip>:8000/whereami`. If that page loads, the network is
  fine and the problem is in the app; if it does not, it is the firewall
  or the Wi-Fi.

- **Guest / client-isolation Wi-Fi** blocks device-to-device traffic
  entirely, even though both devices show the same network name. Most
  public, campus, and hotel Wi-Fi does this, and so do some home mesh
  routers. There is no app-side fix for this — use a phone hotspot with
  the PC joined to it instead, or see below.

## Reaching the backend from anywhere (real deployment)

Same-Wi-Fi discovery is a convenience for testing. It is not how this
should run for actual disaster response — a victim on mobile data, a
different Wi-Fi, or a different city cannot reach a PC that's only
listening on its home network, no matter how well the discovery code
works. What they need is one internet address that reaches your PC (or a
cloud server) no matter where they are. There are two ways to get one.

### Option A — tunnel your PC (fastest to set up, needs the PC to stay on)

A tunnel gives your existing `run_server.py` a public HTTPS address
without touching your router. [Cloudflare
Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/get-started/)
is free and needs no account for a quick tunnel:

```bash
# Windows: download cloudflared.exe from Cloudflare's site
# macOS:   brew install cloudflared
# Linux:   see Cloudflare's install docs for your distro

# with the backend already running via run_server.py, in another terminal:
cloudflared tunnel --url http://localhost:8000
```

This prints a `https://<random-name>.trycloudflare.com` address. Set that
as the **Main system address** in the app, with `/sync` on the end:
`https://<random-name>.trycloudflare.com/sync`. Anyone with that address
can now reach your backend from any network, anywhere.

Trade-offs: the PC must stay on and connected, and a free quick tunnel's
address changes every time you restart it — you'd need to update the
address in the app (and give it to your team) after every restart. For a
short field exercise this is usually fine; for anything longer, a fixed
tunnel token (still free, needs a Cloudflare account) keeps the same
address across restarts.

### Option B — deploy the backend to the cloud (steadier, works with the PC off)

For real, ongoing use, `backend/main.py` is a plain FastAPI app and runs
on any Python host with no changes — [Render](https://render.com),
[Railway](https://railway.app), and [Fly.io](https://fly.io) all have free
tiers that give a permanent `https://your-app.onrender.com`-style address.
Point **Main system address** at that instead of a PC address, and the
system keeps working even when your laptop is closed. This is the right
answer if the command center needs to be reachable continuously rather
than only while someone is sitting at the PC.

Whichever option you pick, `usesCleartextTraffic="true"` in the app's
manifest means both plain `http://` and `https://` addresses work, so
nothing else needs to change.

### What happens on a weak connection

Reports are written to the phone's database *before* any upload, so a
report is never lost. Delivery then tolerates a poor network: 15 s connect
and 20 s read timeouts, three attempts with a growing gap, and (for a
same-Wi-Fi address only) one automatic re-discovery in between — a public
address skips that step, since discovery can only ever find something on
the local network. Anything still queued is retried when the app is
reopened **and** the moment a network becomes available again, so getting
signal back is enough to flush the backlog.

Received reports are written to `backend/reports.json`, so restarting the
backend no longer empties the dashboard.

## Known limits

Worth being straight about these:

- **Foreground only.** Android suspends BLE when the screen locks. Keep the
  app open during testing. Production use needs a foreground service with a
  persistent notification.
- **Range** is roughly 10–50 m outdoors between phones, much less through
  walls and concrete.
- **Messages are not encrypted.** Anyone in range running the app can read a
  hub's traffic. Hub IDs give grouping, not secrecy.
- **No sender authentication.** A node id can be spoofed.
- **Chunked transfers cap at 4 KB** per message.
- Advertising support varies by chipset. A device that cannot advertise can
  still receive and relay.

This is a working prototype, not a certified emergency service. Do not rely on
it as a sole means of calling for help.
