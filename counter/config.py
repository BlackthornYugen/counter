from pathlib import Path
import os

BASE_DIR = Path(__file__).resolve().parent
DB_PATH = os.environ.get("COUNTER_DB_PATH", str(BASE_DIR.parent / "counter.db"))
STATIC_DIR = BASE_DIR / "static"
HOST = os.environ.get("COUNTER_HOST", "0.0.0.0")
PORT = int(os.environ.get("COUNTER_PORT", "8000"))
