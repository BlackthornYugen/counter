from pathlib import Path
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



