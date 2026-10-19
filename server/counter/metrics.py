import re
from typing import Any
from prometheus_client import Counter
from counter import config

counter_registrations_total = Counter(
    "counter_registrations_total",
    "Total counter registrations partitioned by payload key format and client",
    ["key_format", "client", "status"],
)


def get_short_git_hash() -> str:
    """Returns top 7 characters of git hash or 'dev'."""
    h = getattr(config, "GIT_HASH", "dev")
    if not h:
        return "dev"
    return str(h)[:7]


def classify_registration_payload(raw_data: dict[str, Any] | None) -> str:
    """Classifies incoming registration payload format into:
    - 'new': Request supplied 'owner', 'editor', and/or 'viewer' (without legacy keys).
    - 'legacy': Request supplied 'adminUUID', 'readWriteUUID', 'readOnlyUUID' (or snake_case).
    - 'hybrid': Mixed legacy and new parameters.
    If no keys or empty, defaults to 'legacy' to preserve baseline.
    """
    if not isinstance(raw_data, dict):
        return "legacy"

    new_keys = {"owner", "editor", "viewer"}
    legacy_keys = {
        "adminUUID", "admin_uuid", "admin",
        "readWriteUUID", "read_write_uuid", "read_write",
        "readOnlyUUID", "read_only_uuid", "read_only",
    }

    has_new = any(raw_data.get(k) is not None for k in new_keys)
    has_legacy = any(raw_data.get(k) is not None for k in legacy_keys)

    if has_new and has_legacy:
        return "hybrid"
    if has_new:
        return "new"
    return "legacy"


def normalize_client_user_agent(user_agent: str | None, client_version_header: str | None = None) -> str:
    """Normalizes client information to a low-cardinality label (family + version).
    Never includes raw User-Agent strings, device fingerprints, or PII.
    Examples:
      - 'Counter-Android/11' -> 'android-11'
      - 'Counter-Wear/12' -> 'wear-12'
      - 'Mozilla/5.0 ...' -> 'web-<GIT_HASH[:7]>' (e.g. 'web-7d2be46')
      - X-Client-Version: 'web' -> 'web-<GIT_HASH[:7]>'
      - X-Client-Version: 'web-abcdef1234' -> 'web-abcdef1'
    """
    short_hash = get_short_git_hash()

    if client_version_header:
        cleaned = client_version_header.strip().lower()
        if cleaned == "web":
            return f"web-{short_hash}"
        if cleaned.startswith("web-"):
            suffix = cleaned[4:].strip()
            return f"web-{suffix[:7]}" if suffix else f"web-{short_hash}"
        if re.match(r"^[a-z0-9_-]{1,20}$", cleaned):
            return cleaned

    if not user_agent:
        return "unknown"

    ua = user_agent.strip()

    # Android client: e.g. "Counter-Android/11" or "Counter-Android (versionCode 11)"
    m_android = re.search(r"Counter-Android[/:\s]+(?:v)?(\d+)", ua, re.IGNORECASE)
    if m_android:
        return f"android-{m_android.group(1)}"

    # Wear OS client: e.g. "Counter-Wear/12"
    m_wear = re.search(r"Counter-Wear[/:\s]+(?:v)?(\d+)", ua, re.IGNORECASE)
    if m_wear:
        return f"wear-{m_wear.group(1)}"

    # Web browser
    if any(b in ua for b in ("Mozilla/", "Chrome/", "Safari/", "Firefox/", "Edge/")):
        return f"web-{short_hash}"

    return "unknown"


def record_registration_metric(key_format: str, client: str = "unknown", status: str = "error") -> None:
    """Increments the registration counter metric with validated low-cardinality labels."""
    kf = key_format if key_format in ("new", "legacy", "hybrid") else "legacy"
    cl = client if client and len(client) <= 32 else "unknown"
    st = status if status in ("success", "error") else "error"
    counter_registrations_total.labels(key_format=kf, client=cl, status=st).inc()
