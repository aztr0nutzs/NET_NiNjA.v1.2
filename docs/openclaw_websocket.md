# OpenClaw WebSocket (`/openclaw/ws`)

This repository exposes a small OpenClaw gateway WebSocket that OpenClaw nodes can connect to.
It is implemented by both targets:

- Desktop server module: `server`
- Android local server: `android-app` (`AndroidLocalServer`)

## URL

- `ws://127.0.0.1:8787/openclaw/ws`

## Client -> Server Messages

JSON text frames:

```json
{
  "type": "HELLO | HEARTBEAT | RESULT",
  "nodeId": "string (required for HELLO)",
  "capabilities": ["string", "..."],
  "payload": "string (optional; RESULT only)"
}
```

Semantics:

- `HELLO`: registers the node id + capabilities with the gateway.
- `HEARTBEAT`: updates `lastSeen` for the node.
- `RESULT`: updates `lastSeen` and `lastResult` for the node.

## Server -> Client Broadcast

After state changes (HELLO/HEARTBEAT/RESULT), the gateway broadcasts a snapshot to connected clients:

```json
{
  "nodes": [
    {
      "id": "node-1",
      "capabilities": ["..."],
      "lastSeen": 1730000000000,
      "lastResult": "..."
    }
  ],
  "updatedAt": 1730000000000
}
```

## Related HTTP Endpoints

- `GET /api/openclaw/nodes`
- `GET /api/openclaw/stats`
