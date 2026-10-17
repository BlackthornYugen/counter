# Quantum Counter Server ⚡

A high-performance Python server for real-time counter rooms supporting **WebSockets**, **Server-Sent Events (SSE)**, and **REST** calls.

Room access is managed through cryptographically hashed UUID tokens stored in **SQLite**, supporting 2 or 3 distinct access levels (`read_only`, `read_write`, and `admin`).

---

## 🌟 Key Features

- **Multi-Protocol Real-Time Sync**: Connect and receive streaming updates via **WebSockets** or **Server-Sent Events (SSE)**.
- **Hashed Token Security**: Raw secret UUIDs are **never stored in SQLite**. The server stores normalized **SHA-256 hashes**, authenticating incoming requests via hash resolution.
- **Role-Based Access Control (RBAC)**:
  - 👁️ **Read-Only (`read_only`)**: Subscribes to live updates and queries state. Mutation attempts receive `403 Forbidden` / `permission_denied`.
  - ✍️ **Read-Write (`read_write`)**: Can subscribe, inspect, increment, and decrement. Cannot reset.
  - 👑 **Admin (`admin`)**: Full privileges to inspect, increment, decrement, and reset the counter.
- **2 or 3 UUID Registration**:
  - `register(readOnlyUUID, readWriteUUID, adminUUID)`
  - `register(readOnlyUUID, adminUUID)`
- **Cross-Protocol Broadcasting**: Updates triggered by WebSocket actions or REST calls are simultaneously broadcast to all active WebSockets and SSE clients.
- **Interactive Web Client**: Glassmorphism dashboard with glowing counters, live event feed, role switcher, and a side-by-side simulator.

---

## 🚀 Quick Start with `uv`

### 1. Run the Server

```bash
uv run python main.py
```

The server binds to `http://0.0.0.0:8000` by default:
- **Interactive Web Dashboard**: [http://localhost:8000/](http://localhost:8000/)
- **Swagger OpenAPI Docs**: [http://localhost:8000/docs](http://localhost:8000/docs)
- **Health Check**: `GET http://localhost:8000/health`

Custom port or host:
```bash
uv run python main.py --host 127.0.0.1 --port 9000
```

### 2. Run Test Suite

```bash
uv run pytest -v
```

---

## 🐍 Programmatic Python API

The server package can also be used directly as a Python library:

```python
from counter import register, get_counter_info_by_token, mutate_counter

# Register counter room with 3 UUIDs:
room_3 = register(
    "c768f56e-8ff4-4363-8822-79ee8ca6eef4",  # readOnlyUUID
    "e2244bb4-0997-4ef8-8280-9dfbf2ba1022",  # readWriteUUID
    "523a8867-b50a-4a25-a1c1-40c42be279ab",  # adminUUID
    name="Production Metrics"
)

# Or register counter room with 2 UUIDs:
room_2 = register(
    "c768f56e-8ff4-4363-8822-79ee8ca6eef4",  # readOnlyUUID
    "523a8867-b50a-4a25-a1c1-40c42be279ab",  # adminUUID
)
```

Async variant:
```python
from counter import register_async

result = await register_async(readOnlyUUID, adminUUID)
```

---

## 📡 Protocol Specifications

### 1. REST API

#### Register a New Counter Room
`POST /api/counters/register`

Payload (3 UUIDs):
```json
{
  "name": "Live Visitors",
  "read_only_uuid": "c768f56e-8ff4-4363-8822-79ee8ca6eef4",
  "read_write_uuid": "e2244bb4-0997-4ef8-8280-9dfbf2ba1022",
  "admin_uuid": "523a8867-b50a-4a25-a1c1-40c42be279ab",
  "initial_value": 0
}
```
*Note: CamelCase fields (`readOnlyUUID`, `readWriteUUID`, `adminUUID`) are also accepted.*

Response:
```json
{
  "counter_id": "f8577a76-0b1d-4eb7-a06d-476c8c4a4e10",
  "name": "Live Visitors",
  "value": 0,
  "roles": {
    "read_only": "c768f56e-8ff4-4363-8822-79ee8ca6eef4",
    "read_write": "e2244bb4-0997-4ef8-8280-9dfbf2ba1022",
    "admin": "523a8867-b50a-4a25-a1c1-40c42be279ab"
  },
  "token_hashes": {
    "read_only": "1a2b3c...",
    "read_write": "4d5e6f...",
    "admin": "7g8h9i..."
  },
  "message": "Counter room created with 3 access tokens."
}
```

#### Increment Counter
`POST /api/counters/{token}/increment`
```bash
curl -X POST http://localhost:8000/api/counters/{rw_or_admin_token}/increment \
     -H "Content-Type: application/json" \
     -d '{"delta": 1}'
```

#### Decrement Counter
`POST /api/counters/{token}/decrement`
```bash
curl -X POST http://localhost:8000/api/counters/{rw_or_admin_token}/decrement \
     -H "Content-Type: application/json" \
     -d '{"delta": 1}'
```

#### Reset Counter (Admin only)
`POST /api/counters/{token}/reset`
```bash
curl -X POST http://localhost:8000/api/counters/{admin_token}/reset \
     -H "Content-Type: application/json" \
     -d '{"value": 0}'
```

---

### 2. WebSockets

Connect to:
`ws://localhost:8000/ws/{token}` (or `ws://localhost:8000/ws?token={token}`)

#### 1. On Connection
The server validates the token hash and immediately responds with connection status, the authenticated access role, and permissions:

```json
{
  "type": "connected",
  "protocol": "websocket",
  "counter_id": "f8577a76-0b1d-4eb7-a06d-476c8c4a4e10",
  "counter_name": "Live Visitors",
  "access": "read_write",
  "permissions": {
    "can_read": true,
    "can_write": true,
    "can_admin": false
  },
  "value": 12,
  "recent_events": [ ... ]
}
```

#### 2. Client Actions
Clients send JSON payloads:

- **Increment**: `{"action": "increment", "delta": 1}`
- **Decrement**: `{"action": "decrement", "delta": 1}`
- **Reset**: `{"action": "reset", "value": 0}` (Admin only)
- **Ping**: `{"action": "ping"}`

#### 3. Real-Time Broadcasts
All clients in the counter room receive:
```json
{
  "type": "update",
  "counter_id": "f8577a76-0b1d-4eb7-a06d-476c8c4a4e10",
  "action": "increment",
  "delta": 1,
  "value": 13,
  "role": "read_write",
  "timestamp": "2026-10-17T21:55:00Z"
}
```

#### 4. Permission Denied Error
If a `read_only` user attempts to send an increment/decrement command:
```json
{
  "type": "error",
  "code": "permission_denied",
  "access": "read_only",
  "message": "Role 'read_only' has read-only access and cannot perform 'increment'."
}
```

---

### 3. Server-Sent Events (SSE)

Connect to:
`GET /sse/{token}` (or `GET /sse?token={token}`)

Streaming headers: `Content-Type: text/event-stream`

#### Event: `connected`
Immediately emitted upon connection:
```
event: connected
data: {"type": "connected", "access": "read_only", "permissions": {"can_read": true, "can_write": false, "can_admin": false}, "value": 13, "recent_events": [...]}
```

#### Event: `update`
Emitted whenever an increment, decrement, or reset occurs:
```
event: update
data: {"type": "update", "action": "increment", "delta": 1, "value": 14, "role": "admin", "timestamp": "2026-10-17T21:55:05Z"}
```

---

## 🔒 SQLite Database Schema

The database uses SQLite with 3 tables:

```sql
CREATE TABLE counters (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    value INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE counter_tokens (
    token_hash TEXT PRIMARY KEY,   -- SHA-256 hex digest of normalized token
    counter_id TEXT NOT NULL,
    role TEXT NOT NULL CHECK(role IN ('read_only', 'read_write', 'admin')),
    created_at TEXT NOT NULL,
    FOREIGN KEY (counter_id) REFERENCES counters(id) ON DELETE CASCADE
);

CREATE TABLE counter_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    counter_id TEXT NOT NULL,
    action TEXT NOT NULL,
    delta INTEGER,
    value_after INTEGER NOT NULL,
    role TEXT NOT NULL,
    timestamp TEXT NOT NULL,
    FOREIGN KEY (counter_id) REFERENCES counters(id) ON DELETE CASCADE
);
```
