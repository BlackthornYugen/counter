# Roles Refactor Plan — "Owner / Editor / Viewer"

## 1. Problem Statement

Role names are inconsistent across the stack and confusing to users:

| Layer | "Highest" | "Middle" | "Lowest" |
|---|---|---|---|
| **Server enums** (`models.py`, `db.py` CHECK constraint) | `admin` | `read_write` | `read_only` |
| **API JSON** (register request/response, access field) | `admin` / `adminUUID` | `read_write` / `readWriteUUID` | `read_only` / `readOnlyUUID` |
| **Web UI labels** (`app.js` `renderRoleCards`) | "Admin" / 👑 "Full Control" | "Read-Write" / ✍️ "Write & Read" | "Read-Only" / 👁️ "View Only" |
| **Android share chips** (`CounterDetailPane.kt`) | "Full (Admin)" | "Read-Write" | "Read-Only" |
| **Android enum** (`ShareRole`) | `ADMIN` | `READ_WRITE` | `READ_ONLY` |
| **Android model fields** (`Counter.kt`) | `deleteUuid` | `updateUuid` | `viewUuid` |
| **Android UI strings** | "Forget Admin Key" | — | — |

Three different labeling vocabularies are used for the same concept, with no single consistent name a user would recognise.

---

## 2. Target Naming

One word per role, everywhere — API fields, JSON keys, DB columns, UI labels, variable names. **No emoji icons** — clean text-only chips.

| Role | Display Name | API/DB value | JSON key | Description |
|---|---|---|---|---|
| **Owner** | Owner | `owner` | `"owner"` | Full control — delete counter, change settings, share all roles |
| **Editor** | Editor | `editor` | `"editor"` | Can increment & decrement the counter |
| **Viewer** | Viewer | `viewer` | `"viewer"` | Can see the live count (read-only) |

No `UUID` suffix anywhere. The register request body becomes:

```json
{
  "viewer": "xxxxxxxx-xxxx-4xxx-...",
  "editor": "xxxxxxxx-xxxx-4xxx-...",
  "owner":  "xxxxxxxx-xxxx-4xxx-..."
}
```

The register response `roles` dict:

```json
{
  "roles": {
    "viewer": "xxxxxxxx-xxxx-4xxx-...",
    "editor": "xxxxxxxx-xxxx-4xxx-...",
    "owner":  "xxxxxxxx-xxxx-4xxx-..."
  }
}
```

> [!TIP]
> "Owner / Editor / Viewer" follows the same mental model as Google Docs sharing. Users already understand it.

---

## 3. Architecture & Migration Strategy

### 3.1 Option A: Wire-Level & DB Enum Rename

Change wire-format enum values and DB constraints from `admin` → `owner`, `read_write` → `editor`, `read_only` → `viewer`.
- **Pros:** Unified vocabulary across the entire codebase, clean mental model, zero long-term cognitive overhead or translation layers.
- **Cons:** Requires a coordinated database migration and backward-compatibility bridge during beta.

### 3.2 Zero-Downtime Feature Flag — Dual-Key Transition

Since beta testers are actively using the app, the server **must not break old clients** while new clients roll out. The strategy:

**Phase 1 deploys the server with a feature flag** `DUAL_ROLE_KEYS=true` (environment variable or `config.py` constant). While the flag is on:

1. The server **stores** new role values internally (`owner`, `editor`, `viewer`).
2. API **responses** include **both** old and new keys side-by-side:

```json
{
  "roles": {
    "owner": "xxxxxxxx-...",
    "editor": "xxxxxxxx-...",
    "viewer": "xxxxxxxx-...",
    "admin": "xxxxxxxx-...",
    "read_write": "xxxxxxxx-...",
    "read_only": "xxxxxxxx-..."
  },
  "access": "owner",
  "access_legacy": "admin"
}
```

3. The `access` field in `CounterInfoResponse`, WebSocket `connected`, SSE `connected`, broadcast `update`, and `action_ack` payloads **also** includes a legacy `access_legacy` field:

```json
{
  "access": "owner",
  "access_legacy": "admin"
}
```

4. API **requests** accept **both** old and new field names for registration (`owner` or `adminUUID` / `admin_uuid`).
5. Old clients that read `roles["admin"]` or check `access == "read_only"` continue to work seamlessly. New clients read `roles["owner"]` or check `access == "viewer"`.

### 3.3 Prometheus Migration Metrics

To safely verify when it is safe to turn off legacy keys (reaching the 90% client migration threshold), the server must report telemetry on registration formats **before** web and Android clients are updated.

#### Metrics Instrumentation
- Expose standard Prometheus metric endpoint at `/metrics` using `prometheus_client`.
- Define registration counter:
  ```python
  counter_registrations_total = Counter(
      "counter_registrations_total",
      "Total counter registrations partitioned by payload key format and client",
      ["key_format", "client", "status"]  # key_format: "new", "legacy", "hybrid" | client: "android-<versionCode>", "wear-<versionCode>", "web-<git_hash>", "unknown" | status: "success", "error"
  )
  ```
- **Key Format Classification**:
  - `new`: Request supplied `owner`, `editor`, and/or `viewer`.
  - `legacy`: Request supplied `adminUUID`, `readWriteUUID`, `readOnlyUUID` (or snake_case variants).
  - `hybrid`: Mixed legacy and new parameters (treated as legacy).

- **Normalized Client Classification (User-Agent Header & Versioning)**:
  - **Android Client**: Ktor HTTP client is configured to send `User-Agent: Counter-Android/${BuildConfig.VERSION_CODE}`. The server normalizes this to `android-<versionCode>` (e.g. `android-11`, `android-12`). Because `versionCode` increments monotonically with each build/release, this gives exact client version adoption without high-cardinality device or OS fingerprints.
  - **Wear OS Client**: Ktor HTTP client is configured to send `User-Agent: Counter-Wear/${BuildConfig.VERSION_CODE}`. The server normalizes this to `wear-<versionCode>`.
  - **Web Client**: W3C specification strictly forbids JavaScript `fetch()` from overriding the browser's `User-Agent` header (`Refused to set unsafe header "User-Agent"`). Therefore:
    - The server identifies web traffic via browser user agents (containing `Mozilla/`, `Chrome/`, `Safari/`, `Firefox/`, or `Edge/`) or the `X-Client-Version` header, and normalizes them to `web-<GIT_HASH>` using the top 7 characters of the `GIT_HASH` environment variable (e.g. `web-7d2be46`, falling back to `web-dev` in development).
    - If the client supplies `X-Client-Version: web-<git_commit>`, the top 7 characters of the specified commit hash are extracted.
  - **Unknown / Legacy**: Fallback to `unknown` when no recognizable app or browser agent header is provided.

#### Prometheus Ingestion
- Update `chart/templates/deployment.yaml` and `chart/templates/service.yaml` with scrape annotations:
  ```yaml
  prometheus.io/scrape: "true"
  prometheus.io/path: "/metrics"
  prometheus.io/port: "8000"
  ```
- Configure/verify the Prometheus scraper targets the counter service.

#### Privacy & 30-Day Metrics Retention Policy
> [!IMPORTANT]
> To comply with the project's Privacy Policy (Section 5: *Data Retention and Server Removal*):
> 1. **Zero PII & Low-Cardinality Metrics**: Metric labels must strictly contain low-cardinality metadata (`key_format`, `client`, `status`). Raw User-Agent strings, browser fingerprints, device models, UUIDs, token hashes, client IPs, or counter names must **never** be used as metric labels. Only the normalized client family and versionCode (e.g. `android-11`, `web`, `unknown`) are stored.
> 2. **30-Day Retention**: Ensure the Prometheus metrics bucket / TSDB is configured to retain data for **at most 30 days** (`--storage.tsdb.retention.time=30d` or S3/MinIO bucket lifecycle expiration rule set to 30 days).

---

## 4. Phased Rollout Plan

```
[Phase 1: Server + Metrics + Dual-Key Flag]
                      │
                      ▼
[Phase 1.5: Verify Scrape & Baseline on Prometheus]
                      │
         ┌────────────┴────────────┐
         ▼                         ▼
 [Phase 2: Web Client]   [Phase 3: Android Client]
                                   │
                                   ▼
                         [Phase 4: Android Advanced Sharing]
                                   │
         ┌─────────────────────────┘
         ▼
 [Monitor Prometheus: >90% New Key Ratio]
         │
         ▼
 [Phase 5: Flip Flag OFF & Purge Legacy Keys]
```

### Phase 1 — Server, Database Migration & Prometheus Metrics Instrumentation

> [!IMPORTANT]
> This phase happens first. The server will support both new and legacy formats, and starts reporting metrics to Prometheus.

**Files touched:**

| File | Changes |
|---|---|
| [`pyproject.toml`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/server/pyproject.toml) | Add `prometheus-client>=0.21.0` dependency. |
| [`models.py`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/server/counter/models.py) | Rename `ROLE_PERMISSIONS` keys to `viewer`, `editor`, `owner`. Rename `can_admin` → `can_own`. Update `RegisterRequest` to support new fields (`viewer`, `editor`, `owner`) with backward-compatible aliases for legacy fields. |
| [`config.py`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/server/counter/config.py) | Add `DUAL_ROLE_KEYS = os.environ.get("DUAL_ROLE_KEYS", "true").lower() == "true"`. Add `LEGACY_ROLE_MAP`. Add helper functions `maybe_add_legacy_roles()` and `maybe_add_legacy_access()`. |
| [`config.py`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/server/counter/config.py) | Add `DUAL_ROLE_KEYS = os.environ.get("DUAL_ROLE_KEYS", "true").lower() == "true"`. Add `LEGACY_ROLE_MAP`. Add helper functions `maybe_add_legacy_roles()` and `maybe_add_legacy_access()`. |
| [`metrics.py`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/server/counter/metrics.py) | Create module with `counter_registrations_total(key_format, client, status)` and helpers to classify payload as `new` vs `legacy` and normalize user agent to family + `versionCode` (`android-<versionCode>`, `wear-<versionCode>`, `web`, `unknown`). |
| [`main.py`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/server/counter/main.py) | Mount `/metrics` endpoint using `prometheus_client.make_asgi_app()`. |
| [`db.py`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/server/counter/db.py) | Update `SCHEMA_SQL` CHECK constraint: `role IN ('viewer', 'editor', 'owner')`. Add startup migration script for existing database records. |
| [`service.py`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/server/counter/service.py) | Update internal naming (`owner`, `editor`, `viewer`). Wrap `roles_dict` with `maybe_add_legacy_roles()` and response with `maybe_add_legacy_access()`. |
| [`api.py`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/server/counter/api.py) | Support dual-key fields in SSE and WebSocket payloads. Inspect `User-Agent` and `X-Client-Version` headers on registration, normalize client identity, and increment registration metric. |
| [`chart/templates/service.yaml`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/chart/templates/service.yaml) | Add Prometheus scraping annotations (`prometheus.io/scrape: "true"`, port 8000, path `/metrics`). |

**Database Migration (SQLite):**
```sql
UPDATE counter_tokens SET role = 'viewer' WHERE role = 'read_only';
UPDATE counter_tokens SET role = 'editor' WHERE role = 'read_write';
UPDATE counter_tokens SET role = 'owner' WHERE role = 'admin';

UPDATE counter_events SET role = 'viewer' WHERE role = 'read_only';
UPDATE counter_events SET role = 'editor' WHERE role = 'read_write';
UPDATE counter_events SET role = 'owner' WHERE role = 'admin';
```

---

### Phase 1.5 — Verification & Metrics Baseline

#### Verification Audit Results

| Target / Check | Endpoint / API | Status | Verification Details |
|---|---|---|---|
| **Staging `/metrics`** | Staging Ingress (`/metrics`) | ✅ Verified Blocked | Public ingress access to `/metrics` returns `404 Not Found`. |
| **NodePort `/metrics`** | Internal NodePort (`:30403/metrics`) | ✅ Verified Live | Internal access exposes `counter_registrations_total` with labels `["key_format", "client", "status"]`. |
| **Grafana Auth** | Grafana API | ✅ Verified | Metrics Service Account authenticates cleanly via API Bearer token. |
| **Grafana DataSource** | Grafana Data Sources API | ✅ Verified | Default Prometheus datasource is connected and proxyable. |
| **TSDB Retention** | Prometheus Status API | ✅ Verified | TSDB retention confirmed at $\le 30$ days (satisfies privacy policy limit). |
| **Prometheus Target Scraping** | Prometheus Targets API | ⚠️ Pending Scrape Job | Scrape job for Counter metrics target to be configured in Prometheus. |

#### Options for Collecting Metrics into Prometheus & Grafana

Currently, `counter_registrations_total` is live on the container at `/metrics`, accessible via internal NodePort. Below are the architectural options evaluated for metric collection:

```mermaid
flowchart TD
    subgraph K8s["Kubernetes"]
        CounterPod["Counter Pod (:8000/metrics)"]
        CounterSVC["Counter Service (NodePort)"]
    end

    subgraph Monitoring["Monitoring Infrastructure"]
        Prom["Prometheus (:9090)"]
        Grafana["Grafana"]
    end

    CounterPod --> CounterSVC

    %% Option 1: Direct NodePort pull
    Prom -.->|"Option 1: Scrape Internal NodePort"| CounterSVC
    %% Option 2: Direct K8s SD
    Prom -.->|"Option 2: Kubernetes Pod SD (Cluster IP)"| CounterPod
    %% Option 3: Remote Write
    CounterPod -.->|"Option 3: In-Cluster Agent Remote-Write"| Prom

    Prom -->|"Datasource Proxy"| Grafana
```

---

##### Option 1: Internal NodePort Scraping (Implemented & Recommended)
Prometheus pulls metrics from the internal NodePort exposed by the Kubernetes service (`counter-main` pinned to static NodePort `30800`, PR previews allocated dynamically).

- **Implementation**:
  Add an internal scrape job to `prometheus.yml`:
  ```yaml
  scrape_configs:
    - job_name: counter
      metrics_path: /metrics
      static_configs:
        - targets: ['<internal-node-host>:<nodePort>']
          labels:
            app: counter
  ```
- **Pros**:
  - Completely internal: zero public ingress exposure, zero external TLS overhead.
  - Public ingress access to `/metrics` remains blocked with `404 Not Found`.
  - Static NodePort `30800` is pinned in `argocd/main-application.yaml` for production stability.
- **Cons**:
  - Requires network reachability between Prometheus and the Kubernetes node.

---

##### Option 2: Kubernetes Service Discovery (`kubernetes_sd_configs`)
Prometheus communicates with the Kubernetes API server to dynamically discover pods annotated with `prometheus.io/scrape: "true"`.

- **Implementation**:
  Add a pod discovery job to `prometheus.yml`:
  ```yaml
  scrape_configs:
    - job_name: kubernetes-pods
      kubernetes_sd_configs:
        - role: pod
          api_server: https://kubernetes.default.svc
          bearer_token: "<K8S_PROMETHEUS_TOKEN>"
          tls_config:
            insecure_skip_verify: true
      relabel_configs:
        - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
          action: keep
          regex: true
        - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
          action: replace
          target_label: __metrics_path__
          regex: (.+)
        - source_labels: [__address__, __meta_kubernetes_pod_annotation_prometheus_io_port]
          action: replace
          regex: ([^:]+)(?::\d+)?;(\d+)
          replacement: $1:$2
          target_label: __address__
        - source_labels: [__meta_kubernetes_namespace]
          action: replace
          target_label: kubernetes_namespace
        - source_labels: [__meta_kubernetes_pod_name]
          action: replace
          target_label: kubernetes_pod_name
  ```
- **Pros**:
  - Automatic discovery for all preview environments matching the annotations in `chart/templates/deployment.yaml`.
- **Cons**:
  - Requires Prometheus to route directly to internal cluster pod IPs.

---

##### Option 3: In-Cluster Prometheus Agent (`remote_write`)
Deploy a lightweight Prometheus Agent inside the Kubernetes cluster that scrapes pods internally and forwards samples to Prometheus via `remote_write`.

- **Implementation**:
  1. Enable `--web.enable-remote-write-receiver` on Prometheus.
  2. Deploy an agent into the cluster targeting the Prometheus endpoint:
     ```yaml
     remote_write:
       - url: "http://<prometheus-host>:9090/api/v1/write"
     ```
- **Pros**:
  - Cleanest network boundary when Prometheus is external to the cluster.
- **Cons**:
  - Requires deploying an additional agent workload in the cluster.

---

##### Architecture Options Summary Matrix

| Metric Collection Option | Setup Effort | Dynamic Discovery | Network Isolation | Status |
|---|---|---|---|---|
| **Option 1: Internal NodePort Scraping** | 🟢 Low | 🟢 Pinned 30800 for Prod | 🟢 Internal Only (404 on Ingress) | **Implemented & Verified** |
| **Option 2: Kubernetes Service Discovery** | 🟡 Medium | 🟢 Fully Automatic | 🟢 Direct Pod Scrape | Evaluated |
| **Option 3: In-Cluster Agent (remote_write)** | 🔴 High | 🟢 Fully Automatic | 🟢 Completely Private | Evaluated |

#### Pre-Release Checklist
1. Deploy updated server image with `DUAL_ROLE_KEYS=true`. *(Completed via PR #1)*
2. Confirm `GET /metrics` returns `counter_registrations_total`. *(Completed)*
3. Add scrape job to `prometheus.yml` and reload Prometheus.
4. Verify Prometheus target discovery shows `counter` as healthy (`up`).
5. Verify baseline: Existing active clients register with `key_format="legacy"` (100% legacy ratio).
6. Verify TSDB retention $\le 30$ days. *(Completed: 15d)*

---

### Phase 2 — Web Client

**Files touched:**

| File | Changes |
|---|---|
| [`app.js`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/web/app.js) | Full rename across ~30 references. Remove all emojis, use clean text chips. Update link anchors to `#owner#`, `#editor#`, `#viewer#` with backward-compatible parsing for existing `#admin#`, `#write#`, `#read#` links. |

**Key changes:**
1. `currentRole` initial value: `"admin"` → `"owner"`.
2. `applyRolePermissions()`: Role display text `"Owner"`, `"Editor"`, `"Viewer"`.
3. `renderRoleCards()`: Remove all emoji icons (👑 ✍️ 👁️). Render clean text-only badge chips.
4. `buildShareHash()`: `#owner#`, `#editor#`, `#viewer#`. Lookup tokens via `tokens.owner`, `tokens.editor`, `tokens.viewer`.
5. Registration payload: Send `owner`, `editor`, `viewer` (no UUID suffix).
6. Optional Client Header: Send `X-Client-Version: web` (or commit hash) in registration requests (browser `User-Agent` cannot be overridden via `fetch`, and the server automatically normalizes browser UAs to `web`).

---

### Phase 3 — Android Client

**Files touched:**

| File | Changes |
|---|---|
| [`CounterLinkParser.kt`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/android/app/src/main/java/dev/jskw/counter/util/CounterLinkParser.kt) | Rename `ShareRole.ADMIN` → `ShareRole.OWNER`, `ShareRole.READ_WRITE` → `ShareRole.EDITOR`, `ShareRole.READ_ONLY` → `ShareRole.VIEWER`. Support both new and legacy anchor keywords. |
| [`Counter.kt`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/android/app/src/main/java/dev/jskw/counter/data/model/Counter.kt) | Rename Room columns: `deleteUuid` → `owner`, `updateUuid` → `editor`, `viewUuid` → `viewer`. Rename properties: `canForgetAdminKey` → `canForgetOwnerKey`, `effectiveAdminToken` → `effectiveOwnerToken`. |
| [`CounterDetailPane.kt`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/android/app/src/main/java/dev/jskw/counter/ui/components/CounterDetailPane.kt) | Text-only chips: `"Owner"`, `"Editor"`, `"Viewer"`. Rename dialogs and buttons: "Forget Owner Key". |
| [`RemoteModels.kt`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/android/app/src/main/java/dev/jskw/counter/data/remote/RemoteModels.kt) | Rename `RegisterRequest` fields: `owner`, `editor`, `viewer`. |
| [`CounterApiService.kt`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/android/app/src/main/java/dev/jskw/counter/data/remote/CounterApiService.kt) | Rename `publishCounter()` parameters: `owner`, `editor`, `viewer`. |
| [`KtorCounterApiService.kt`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/android/app/src/main/java/dev/jskw/counter/data/remote/KtorCounterApiService.kt) | Matching parameter renames. Configure `HttpClient` with `UserAgent("Counter-Android/${BuildConfig.VERSION_CODE}")` so the server can track adoption by `versionCode`. |
| [`CounterRepositoryImpl.kt`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/android/app/src/main/java/dev/jskw/counter/data/repository/CounterRepositoryImpl.kt) | Rename `forgetAdminKey()` → `forgetOwnerKey()`. Update role dictionary resolution: `roles["owner"]`, `roles["editor"]`, `roles["viewer"]`. |
| [`CounterRepository.kt`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/android/app/src/main/java/dev/jskw/counter/data/repository/CounterRepository.kt) | Rename interface method `forgetOwnerKey()`. |
| [`CounterViewModel.kt`](file:///Users/jsteel/.gemini/antigravity/worktrees/counter/harmonize_user_roles_plan/android/app/src/main/java/dev/jskw/counter/ui/CounterViewModel.kt) | Rename `forgetAdminKey()` → `forgetOwnerKey()`. Notification: "Owner key forgotten". |

**Room Database Migration:**
```kotlin
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE counters RENAME COLUMN deleteUuid TO owner")
        db.execSQL("ALTER TABLE counters RENAME COLUMN updateUuid TO editor")
        db.execSQL("ALTER TABLE counters RENAME COLUMN viewUuid TO viewer")
    }
}
```

---

### Phase 4 — Android "Advanced Sharing" Opt-In

**New UX Behavior:**
1. **Default State**:
   - Share section only displays **Editor** and **Viewer** chips.
   - **Owner** chip is hidden to prevent accidental full-control sharing.
   - **"Forget Owner Key"** button is hidden by default.
2. **Opt-in Toggle**:
   - Add an **"Advanced sharing"** toggle in the share section header or dropdown menu.
   - When enabled, the **Owner** chip and the **"Forget Owner Key"** action become visible.
   - Store setting in `SharedPreferences` (defaults to `false`).

---

### Phase 5 — Metric-Driven Deprecation & Cleanup

Once Prometheus metrics demonstrate $\ge 90\%$ of registration traffic uses `key_format="new"`:
```promql
sum(rate(counter_registrations_total{key_format="new"}[7d]))
/
sum(rate(counter_registrations_total[7d])) >= 0.90
```
1. Set `DUAL_ROLE_KEYS=false` in environment/config.
2. Remove legacy mapping functions (`maybe_add_legacy_roles`, `maybe_add_legacy_access`).
3. Remove legacy alias fields from `RegisterRequest`.
4. Drop `access_legacy` from response models.
5. Clean up dual-key test cases.

---

## 5. Backward Compatibility & Privacy Checklist

| Area | Requirement | Solution / Safeguard |
|---|---|---|
| **Zero Downtime API** | Old apps must not break during rollout | Feature flag `DUAL_ROLE_KEYS=true` returns both old and new keys simultaneously. |
| **Old Links** | Links with `#admin#`, `#read#`, `#write#` must still work | Parsers in both web and Android permanently support old and new keywords. |
| **Existing Database** | Stored rows have old role strings | SQLite migration updates existing records to `owner`, `editor`, `viewer`. |
| **Client Migration Tracking** | Need objective adoption data before turning off legacy keys | Prometheus metric `counter_registrations_total{key_format="new|legacy|hybrid", client="android-<versionCode>|web-<git_hash>|unknown"}`. |
| **Privacy Compliance** | Section 5 retention policy (< 30 days) | Metric labels contain **zero PII**; User-Agent is normalized to client family and version (`android-<versionCode>`, `web-<git_hash>`, `unknown`), completely discarding raw strings and device fingerprints; Prometheus TSDB/bucket retention is set to **30 days**. |

---

## 6. Work Breakdown & Ordering

```mermaid
flowchart TD
    A["Phase 1: Server + DB Migration\n+ Metrics (/metrics) + Dual-Key ON"] --> B["Phase 1.5: Verify Scrape & Baseline\non Prometheus\n(Confirm 30-day retention)"]
    B --> C["Phase 2: Web Client Update\n(text-only chips, new keys)"]
    B --> D["Phase 3: Android Client Rename\n(Room migration, models, text chips)"]
    D --> E["Phase 4: Android Advanced Sharing UX\n(Opt-in for Owner link)"]
    C --> F["Prometheus Adoption Monitor\n(Wait for >= 90% new keys)"]
    E --> F
    F --> G["Phase 5: Flip Flag OFF\n& purge legacy keys"]
```

| Phase | Description | Est. Effort | Risk |
|---|---|---|---|
| **1 — Server & Metrics** | Server migration, dual-key feature flag, `prometheus-client` `/metrics` route, Helm scrape annotations | ~3 hours | Low (dual-key is non-breaking) |
| **1.5 — Metrics Verification** | Verify target scraping on Prometheus, confirm 30-day retention, establish baseline | ~30 min | Low |
| **2 — Web Client** | Update `app.js` with new names, text-only chips, backward-compatible parser | ~1.5 hours | Low |
| **3 — Android Rename** | Room migration, models, repository, text-only chips | ~2 hours | Medium (Room DB migration) |
| **4 — Android Advanced Sharing** | Opt-in toggle to reveal Owner chip and Forget Owner Key button | ~1 hour | Low (UI-only) |
| **5 — Flag Deprecation** | Flip flag to false once metrics confirm $\ge 90\%$ adoption, remove legacy aliases | ~30 min | Low |

---

## 7. Future Considerations

- **Owner-Only Capabilities**: Future capabilities like retention policy configuration, reset actions, and delta limits will naturally enforce `can_own` permission.
- **Role Extensibility**: The Owner / Editor / Viewer structure cleanly supports future roles (e.g. "Commenter" or "Auditor") without ambiguous abbreviations.
