# Quantum Counter Server ⚡

A high-performance Python server for real-time counter rooms supporting **WebSockets**, **Server-Sent Events (SSE)**, and **REST** calls.

Room access is managed through cryptographically hashed UUID tokens stored in **SQLite**, supporting 2 or 3 distinct access levels (`read_only`, `read_write`, and `admin`).

---

## 🌟 Key Features

- **Multi-Protocol Real-Time Sync**: Connect and receive streaming updates via **WebSockets** or **Server-Sent Events (SSE)**.
- **Hashed Token Security**: Raw secret UUIDs are **never stored in SQLite**. The server stores normalized **SHA-256 hashes**, authenticating incoming requests via hash resolution.
- **Role-Based Access Control (RBAC)**:
  - 👁️ **Read-Only (`read_only`)**: Subscribes to live updates and queries state. Mutation attempts receive `403 Forbidden` / `permission_denied`.
  - ✍️ **Read-Write (`read_write`)**: Can subscribe, inspect, increment, and decrement. Cannot delete room.
  - 👑 **Admin (`admin`)**: Can subscribe, inspect, increment, decrement, and delete the counter room (admin's exclusive privilege over read-write).
- **2 or 3 UUID Registration**:
  - `register(readOnlyUUID, readWriteUUID, adminUUID)`
  - `register(readOnlyUUID, adminUUID)`
- **Strict UUIDv4 Format Enforcement**: Custom vanity URLs (such as `a/b/c`) are strictly rejected. All registered access keys must be valid UUID version 4 tokens.
- **Cross-Protocol Broadcasting**: Updates triggered by WebSocket actions or REST calls are simultaneously broadcast to all active WebSockets and SSE clients.
- **Interactive Web Client**: Glassmorphism dashboard with glowing counters, live event feed, role switcher, and a side-by-side simulator.

---

## 📂 Repository Structure

```
├── android/            # Native Android Client App (Kotlin & Jetpack Compose)
├── server/             # Python Real-Time Counter Server (FastAPI, WebSockets, SSE, SQLite)
│   ├── counter/        # Core server package (api, db, hub, models, service)
│   ├── tests/          # Comprehensive async pytest suite
│   ├── main.py         # Server CLI entry point
│   └── pyproject.toml  # Python project dependencies managed via uv
└── web/                # Standalone Web Dashboard (HTML5, Vanilla CSS, Vanilla JS)
    ├── index.html      # Responsive dashboard interface
    ├── style.css       # Design system & animations
    └── app.js          # WebSockets & SSE client logic with hashtag routing
```

---

## 🚀 Quick Start with `uv`

### 1. Run the Server

```bash
cd server
uv run python main.py
```

The server binds to `http://0.0.0.0:8000` by default:
- **Interactive Web Dashboard**: [http://localhost:8000/](http://localhost:8000/)
- **Swagger OpenAPI Docs**: [http://localhost:8000/docs](http://localhost:8000/docs)
- **Health Check**: `GET http://localhost:8000/health`

Custom port or host:
```bash
cd server
uv run python main.py --host 127.0.0.1 --port 9000
```

### 2. Run Test Suite

```bash
cd server
uv run pytest -v
```

### 3. Build & Push Multi-Arch Docker Image

Build and push the multi-architecture container image for both ARM and x86 (`linux/amd64,linux/arm64`):

```bash
docker buildx build --platform linux/amd64,linux/arm64 -t git.tower.jskw.dev/webapps/counter:latest --push .
```

### 4. Deploy / Update Remote Kubernetes Deployment

The production counter deployment runs on Kubernetes in the `gitea` namespace with `imagePullPolicy: Always` (accessible at [https://counter.jskw.dev](https://counter.jskw.dev)).

After pushing a new image tag, perform a zero-downtime rolling update with:

```bash
kubectl rollout restart deployment/counter -n gitea
```

Monitor rollout progress:

```bash
kubectl rollout status deployment/counter -n gitea
```

Verify deployment health:

```bash
curl -s https://counter.jskw.dev/health
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

#### Delete Counter Room (Admin only)
`DELETE /api/counters/{token}`
```bash
curl -X DELETE http://localhost:8000/api/counters/{admin_token}
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
- **Delete Room**: `{"action": "delete"}` (Admin only)
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
Emitted whenever an increment or decrement occurs:
```
event: update
data: {"type": "update", "action": "increment", "delta": 1, "value": 14, "role": "admin", "timestamp": "2026-10-17T21:55:05Z"}
```

#### Event: `deleted`
Emitted if the counter room is deleted by an admin:
```
event: deleted
data: {"type": "deleted", "counter_id": "f8577a76-...", "message": "Counter room deleted by admin"}
```

---

## 🔗 Client URL Hash & Deep Link Specification

Both the web dashboard and native Android client exchange room access and configuration via URL fragment anchors (`#...`). This enables peer-to-peer sharing, zero-configuration auto-connecting, role escalation/demotion, and custom naming without requiring centralized user accounts.

### 1. Anchor Structure & Examples

The canonical anchor scheme places a **counter label** first, followed by role tags and corresponding UUIDv4 secret keys:

```
#<Counter Label>#read#<ReadOnlyUUID>#write#<ReadWriteUUID>#admin#<AdminUUID>
```

#### Shared Links by Role:
- **👑 Admin Link (3-UUID Room)**: Includes all lower-level tokens:
  ```
  http://localhost:8000/#Counter%2000fb10a2#read#c768f56e-8ff4-4363-8822-79ee8ca6eef4#write#e2244bb4-0997-4ef8-8280-9dfbf2ba1022#admin#523a8867-b50a-4a25-a1c1-40c42be279ab
  ```
- **👑 Admin Link (2-UUID Room)**: Write token is omitted:
  ```
  http://localhost:8000/#Counter%2000fb10a2#read#c768f56e-8ff4-4363-8822-79ee8ca6eef4#admin#523a8867-b50a-4a25-a1c1-40c42be279ab
  ```
- **✍️ Read-Write Link**: Includes read and write tokens:
  ```
  http://localhost:8000/#Counter%2000fb10a2#read#c768f56e-8ff4-4363-8822-79ee8ca6eef4#write#e2244bb4-0997-4ef8-8280-9dfbf2ba1022
  ```
- **👁️ Read-Only Link**: Includes only the read token:
  ```
  http://localhost:8000/#Counter%2000fb10a2#read#c768f56e-8ff4-4363-8822-79ee8ca6eef4
  ```

---

### 2. Counter Label, URL-Encoding & User Renaming

- **Default Label**: When a counter room is registered or connected without an existing label, clients default the name to `Counter <counter_id[:8]>` (for example, `Counter 00fb10a2`).
- **URL-Encoding for Links (`%20`)**:
  - To prevent URLs from breaking when shared in chat, email, or third-party apps, generated links URL-encode spaces in the counter label to `%20` (e.g. `Counter%2000fb10a2`).
  - Clients always **decode** the URL first so `%20` is restored as a single space character (`" "`) when parsing and displaying the counter status title in the UI.
- **User Renaming**: Users can rename their counter before sharing simply by editing the text before the tokens in the URL (e.g., `#Kitchen%20Inventory#read#...#write#...` or `#Kitchen Inventory#read#...`).
- **Client Label Parsing**:
  - The **first non-UUID section** of the anchor is parsed as the counter's display label.
  - If the first section is a valid UUID, starts with a UUID, or is a reserved role keyword (`read`, `write`, `admin`, `read_only`, `read_write`, `ro`, `rw`), it is **not** treated as a label.
  - Clients restore this parsed label into the UI counter status title.

---

### 3. Parsing Rules for Android & Web Clients

Clients (including the Android app and web dashboard) follow these specifications when parsing incoming deep links or user-pasted strings:

1. **URL Decoding**:
   - Clients decode incoming URLs (`URLDecoder.decode(...)` / `decodeURIComponent(...)`) before parsing so `%20` becomes a single space character.
   - If a full URL is provided (e.g. `http://.../#...`), only the fragment after `#` is processed.
2. **Delimiter Flexibility & Compression**:
   - Any character outside of the standard UUIDv4 character set (`[0-9a-fA-F-]`) or sequence of repeated hyphens (`--+`) is considered a delimiter (such as `#`, spaces, hyphens, commas, bangs `!`, colons, or slashes).
   - Multiple consecutive delimiters are compressed into one. For instance, `#uuid1 - uuid2 - uuid3` or `#uuid1#uuid2!uuid3#uuid4ignored` are parsed identically.
3. **Parse Up to 3 UUIDs**:
   - Clients scan for valid UUIDv4 tokens (`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`).
   - Up to **3 UUIDs** are extracted. Any additional UUID candidates beyond the 3rd are safely ignored.
4. **Role Resolution & Clobbering**:
   - Extracted UUIDs are verified via `GET /api/counters/{uuid}` to identify each token's role (`read_only`, `read_write`, `admin`).
   - If an anchor contains duplicate tokens for the same role (e.g., `admin#admin#read`), the **later** UUID clobbers earlier ones. If no write token exists, `read_write` remains unassigned.
5. **Graceful Handling of Vanity & Non-UUID Candidates**:
   - If the anchor contains vanity names or arbitrary words that are not valid UUIDs (e.g., `#my cool uuids#cool1#cool2#cool3`), candidates that fail the UUIDv4 test are safely skipped without displaying error toasts or disrupting the application.

---

### 4. Android Client Reference Implementation (Kotlin)

```kotlin
data class ParsedCounterLink(
    val label: String?,
    val uuids: List<String>
)

object CounterLinkParser {
    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
    private val EMBEDDED_UUID_REGEX = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )
    private val ROLE_KEYWORDS = setOf(
        "read", "write", "admin", "read_only", "read_write", "ro", "rw"
    )

    fun parse(rawInput: String?): ParsedCounterLink {
        if (rawInput.isNullOrBlank()) return ParsedCounterLink(null, emptyList())

        var input = rawInput
        if (input.contains("://")) {
            val hashIdx = input.indexOf('#')
            if (hashIdx != -1) {
                input = input.substring(hashIdx)
            }
        }

        // Decode URL first so %20 becomes a single space character
        val decoded = try {
            java.net.URLDecoder.decode(input, "UTF-8")
        } catch (_: Exception) {
            input
        }
        val trimmed = decoded.trimStart('#').trim()
        if (trimmed.isEmpty()) return ParsedCounterLink(null, emptyList())

        // 1. Extract label from first section if non-UUID and not a role keyword
        val sections = trimmed.split("#").map { it.trim() }.filter { it.isNotEmpty() }
        var parsedLabel: String? = null

        if (sections.isNotEmpty()) {
            val first = sections[0]
            val match = EMBEDDED_UUID_REGEX.find(first)
            val candidate = if (match != null) {
                if (match.range.first == 0) null
                else first.substring(0, match.range.first).trim().trimEnd('-', '—', '!', ' ', ':', '/')
            } else {
                first
            }

            // Strip trailing role keyword if preceded by delimiter (e.g. "Counter 00fb10a2 read" -> "Counter 00fb10a2")
            val cleaned = candidate
                ?.replace(Regex("(?:^|[-–—\\s/!:#])(read_only|read_write|read|write|admin|ro|rw)$", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.trimEnd('-', '—', '!', ' ', ':', '/')

            if (!cleaned.isNullOrBlank() && 
                !UUID_REGEX.matches(cleaned) && 
                cleaned.lowercase() !in ROLE_KEYWORDS
            ) {
                parsedLabel = cleaned
            }
        }

        // 2. Parse up to 3 valid UUIDs, treating any non-UUID char as a delimiter
        val rawParts = trimmed.split(Regex("[^0-9a-fA-F-]+|--+"))
        val uuids = mutableListOf<String>()

        for (part in rawParts) {
            val candidate = part.trim('-', ' ')
            if (candidate.isNotEmpty() && UUID_REGEX.matches(candidate)) {
                uuids.add(candidate.lowercase())
                if (uuids.size == 3) break
            }
        }

        return ParsedCounterLink(parsedLabel, uuids)
    }
}
```

---

## 🔒 SQLite Database Schema

The database uses SQLite with 3 tables:

```sql
CREATE TABLE counters (
    id TEXT PRIMARY KEY,
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
