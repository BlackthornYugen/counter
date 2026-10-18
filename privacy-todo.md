# Privacy Policy Enforcement TODO: Inactivity Purge

## Context & Requirement

The Counter Privacy Policy (effective October 18, 2026, Section 5: *Data Retention, Deletion, and Server Removal*) establishes the following retention commitments:

> **Inactivity Purge Policy:** Unless explicitly told otherwise by the developer, all counters are purged from the server after 90 days of inactivity. All records of counters purged will be cleared 30 days after that (120 days after inactivity).

This document outlines the tasks required to enforce this policy via the Helm chart (`chart/`) and supporting backend logic.

---

## Action Items

### 1. Server-Side Cleanup Logic & Exemption Mechanism
- [ ] **Track Inactivity Timestamps**: Ensure counters in `counter.db` accurately track `last_activity_at` (or `updated_at`) on every read, increment, decrement, reset, or sync event.
- [ ] **Developer Exemption Flag**: Add an explicit mechanism (e.g., an `is_exempt INTEGER DEFAULT 0` column or a whitelist configuration) so the developer can exempt designated counters from automated purges.
- [ ] **Two-Stage Purge Logic**:
  - **Stage 1 (Day 90)**: Soft-delete/purge counters inactive for $> 90$ days. Disconnect live sync, reject increments/decrements with appropriate status code, and mark as purged.
  - **Stage 2 (Day 120)**: Hard-delete all residual records, tombstone entries, cryptographic hashes, and sync metadata ($> 30$ days after initial purge).
- [ ] **CLI / Entrypoint**: Provide a standalone script or entrypoint runnable via container command (e.g. `python -m counter.cleanup --inactive-days 90 --purge-retention-days 30`).
- [ ] **Manual GDPR Early Purge & Export Tool**: Provide an admin utility script (e.g. `python -m counter.gdpr_export_purge --admin-token <UUID> [--export-zip <PATH>]`) to:
  - Locate counter and related evaluation/server logs using hashed token.
  - Optionally bundle relevant logs into a ZIP file with all client IP addresses scrubbed/redacted.
  - Immediately purge counter records and hashes from active storage.


### 2. Helm Chart Updates (`chart/`)
- [ ] **Add Values Configuration in `chart/values.yaml`**:
  ```yaml
  cleanup:
    enabled: true
    schedule: "0 3 * * *"  # Daily at 03:00 UTC
    inactiveDays: 90
    purgeRetentionDays: 30
    concurrencyPolicy: Forbid
    successfulJobsHistoryLimit: 3
    failedJobsHistoryLimit: 3
    resources:
      limits:
        cpu: 100m
        memory: 128Mi
      requests:
        cpu: 20m
        memory: 32Mi
  ```
- [ ] **Create CronJob Template (`chart/templates/cleanup-cronjob.yaml`)**:
  - Deploy a `batch/v1` `CronJob` when `{{ .Values.cleanup.enabled }}` is `true`.
  - Reuse the main container image (`{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}`).
  - Mount the persistent volume at `/data` (`{{ .Values.persistence.mountPath }}`) so the job can access the SQLite database.
  - Pass configured environment variables and run the cleanup CLI script.

### 3. Testing & Verification
- [ ] **Backend Unit Tests**: Add tests in `server/tests/test_cleanup.py` verifying:
  - Counters inactive $\ge 90$ days are purged.
  - Exempt counters are preserved regardless of age.
  - Purged records $\ge 120$ days are hard-deleted.
  - Active counters (< 90 days) remain untouched.
- [ ] **Helm Template Validation**: Run `helm lint chart/` and `helm template test-release chart/` to ensure the `CronJob` renders valid Kubernetes manifest YAML.
