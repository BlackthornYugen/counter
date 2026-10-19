from pathlib import Path
from typing import Any
import os

BASE_DIR = Path(__file__).resolve().parent
SERVER_DIR = BASE_DIR.parent
REPO_ROOT = SERVER_DIR.parent

DB_PATH = os.environ.get("COUNTER_DB_PATH", str(SERVER_DIR / "counter.db"))

default_web_dir = REPO_ROOT / "web"
if not default_web_dir.is_dir():
    default_web_dir = BASE_DIR / "static"

STATIC_DIR = Path(os.environ.get("COUNTER_STATIC_DIR", str(default_web_dir)))
HOST = os.environ.get("COUNTER_HOST", "0.0.0.0")
raw_port = os.environ.get("COUNTER_PORT", os.environ.get("PORT", "8000"))
if isinstance(raw_port, str) and raw_port.startswith("tcp://"):
    raw_port = raw_port.split(":")[-1]
PORT = int(raw_port)


GIT_HASH = os.environ.get("GIT_HASH", os.environ.get("GIT_COMMIT", "dev"))

# Dual-role keys transition feature flag
DUAL_ROLE_KEYS = os.environ.get("DUAL_ROLE_KEYS", "true").lower() == "true"

LEGACY_ROLE_MAP = {
    "owner": "admin",
    "editor": "read_write",
    "viewer": "read_only",
}

REVERSE_LEGACY_ROLE_MAP = {
    "admin": "owner",
    "read_write": "editor",
    "read_only": "viewer",
}


def maybe_add_legacy_roles(roles: dict[str, str]) -> dict[str, str]:
    """If DUAL_ROLE_KEYS is enabled, populates legacy keys alongside new keys in the roles dict."""
    if not DUAL_ROLE_KEYS:
        return dict(roles)
    res = dict(roles)
    for new_key, legacy_key in LEGACY_ROLE_MAP.items():
        if new_key in roles and legacy_key not in res:
            res[legacy_key] = roles[new_key]
    return res


def maybe_add_legacy_access(data: dict[str, Any]) -> dict[str, Any]:
    """If DUAL_ROLE_KEYS is enabled, populates access_legacy from access if present."""
    if not DUAL_ROLE_KEYS:
        return data
    access = data.get("access")
    if access and access in LEGACY_ROLE_MAP and "access_legacy" not in data:
        data["access_legacy"] = LEGACY_ROLE_MAP[access]
    return data



