# Implementation notes

Technical detail behind the offline mesh. See the README for how to build and
test.

---

## Class map

| Class | Responsibility |
|---|---|
| `MeshEngine` | All BLE. Advertising, GATT server, delivery rounds, relaying, hub discovery. Application-scoped singleton. |
| `MainActivity` | Home screen. Mesh control, hub management, emergency reports. |
| `ChatActivity` | Local Emergency Chat. |
| `ChatAdapter` | Message bubbles. |
| `EmergencyDb` | SQLite: messages, outbox, dedup, delivery tracking, members, settings. |
| `BackendSync` | Uploads Emergency Reports, retries the queue. |
| `MeshListener` | UI callbacks, always delivered on the main thread. |
| `ChatMessage`, `OutboxItem` | Models. |

### Why `MeshEngine` is a singleton

The radio has to outlive any one screen. Previously all BLE state lived inside
`MainActivity`, so opening a chat screen would have torn down the connection
that the chat depends on. The engine is now owned by the application and both
activities attach to it as listeners.

---

## Wire protocol

Service `7d4a1000-8a21-4f6c-9d01-123456789001`, write characteristic
`7d4a1001-…`.

Each packet:

```
[4 bytes] total message length   (big endian)
[4 bytes] offset of this chunk   (big endian)
[N bytes] payload
```

Chunk size is `negotiatedMtu − 3 − 8`, with an MTU request of 247 and a floor
of 12 bytes so it still works if negotiation fails. Writes use
`WRITE_TYPE_DEFAULT`, so the next chunk is only sent after the previous one is
acknowledged. The receiver requires sequential offsets, and `offset == 0`
starts a fresh message, which is how several messages travel over one
connection.

Payload is JSON:

```json
{
  "messageId": "MSG-4A2F91C3",
  "hubId": "DISASTER-HUB-4F9384",
  "senderNodeId": "NODE-3344",
  "senderName": "Rahul",
  "timestamp": 1758240000000,
  "type": "CHAT",
  "content": "Water rising near the east gate",
  "ttl": 8
}
```

`type` is `CHAT`, `SYSTEM` or `PRESENCE`. Emergency report types (`MEDICAL`,
`RESCUE`, `FIRE`, …) never enter the mesh.

---

## Delivery state machine

```
IDLE ──kick()──> DISCOVERING ──6s──> DELIVERING ──> IDLE
  ^                                                  |
  └──────────── 9s gap, if outbox non-empty ─────────┘
```

`DELIVERING` walks a queue of peer addresses. For each peer it builds the list
of outbox items that peer has not received and that did not come from it, then
connects once and sends them all in sequence.

### Bugs this design had to avoid

**Self-inflicted disconnect.** Calling `gatt.disconnect()` fires
`onConnectionStateChange`, which would advance the queue a second time and
silently skip the next peer. Guarded with `gatt != activeGatt`.

**Watchdog starvation.** A 20-second per-peer timeout would fire mid-transfer
on a slow link. It is reset on every acknowledged chunk, so it only trips when
progress actually stops.

**UI thrash.** Scan results arrive many times per second. Listeners are
notified only when a genuinely new peer appears.

**Scan throttling.** Android blocks an app that starts more than 5 scans in 30
seconds. A 6-second discovery plus a 9-second gap keeps this to about 2 per 30
seconds.

---

## Database

Version 4. `onUpgrade` is additive (`CREATE TABLE IF NOT EXISTS`) and never
drops data — the previous version's `onUpgrade` was empty, which would have
left the new tables missing on existing installs.

| Table | Purpose |
|---|---|
| `messages` | Chat, system notices and reports. |
| `meta` | Node id, hub id, display name, backend URL. |
| `seen` | Message ids already processed. Dedup. Pruned after 24 h. |
| `outbox` | Messages awaiting delivery, with `source_addr` and round count. |
| `delivered` | `(message_id, peer_addr)` pairs already handed over. |
| `members` | Nodes heard in each hub, with last-seen time. |

`conversation()` returns only `CHAT` and `SYSTEM` rows, which is the mechanism
that keeps Emergency Reports out of the chat.

---

## Lifetimes

- Outbox entries expire after 15 minutes.
- A delivered message is dropped after 3 rounds, so latecomers still get it.
- Presence heartbeats go out every 45 seconds with TTL 3.
- A peer is treated as out of range after 90 seconds without a sighting.

---

## Worth doing next

1. **Foreground service** so the mesh survives a locked screen. This is the
   single biggest gap between the prototype and something usable in the field.
2. **Encryption and signed node identities.** Derive a key from the hub ID, or
   exchange keys on join. Today anyone in range can read and spoof.
3. **Acknowledgement receipts** so a sender knows a message reached a specific
   person, not just that some node accepted it.
4. **Location**, opt-in per message, so the dashboard can map incidents.
5. **Adaptive round timing** — back off when idle to save battery, tighten up
   when traffic is flowing.
6. **Priority queueing** so an SOS overtakes ordinary chat in the outbox.
