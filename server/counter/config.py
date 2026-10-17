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
PORT = int(os.environ.get("COUNTER_PORT", "8000"))

