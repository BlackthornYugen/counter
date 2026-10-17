from datetime import datetime, timezone
import hashlib
from typing import Any, AsyncGenerator
from contextlib import asynccontextmanager
import aiosqlite
from counter import config


def hash_token(raw_uuid: str) -> str:
    """Computes SHA-256 hash of a normalized UUID/token.
    Plaintext UUIDs are never stored in the database.
    """
    normalized = str(raw_uuid).strip().lower()
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def get_iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()


SCHEMA_SQL = """
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS counters (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    value INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS counter_tokens (
    token_hash TEXT PRIMARY KEY,
    counter_id TEXT NOT NULL,
    role TEXT NOT NULL CHECK(role IN ('read_only', 'read_write', 'admin')),
    created_at TEXT NOT NULL,
    FOREIGN KEY (counter_id) REFERENCES counters(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_counter_tokens_counter_id ON counter_tokens(counter_id);

CREATE TABLE IF NOT EXISTS counter_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    counter_id TEXT NOT NULL,
    action TEXT NOT NULL,
    delta INTEGER,
    value_after INTEGER NOT NULL,
    role TEXT NOT NULL,
    timestamp TEXT NOT NULL,
    FOREIGN KEY (counter_id) REFERENCES counters(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_counter_events_counter_id_id ON counter_events(counter_id, id);
"""


@asynccontextmanager
async def get_connection(db_path: str | None = None) -> AsyncGenerator[aiosqlite.Connection, None]:
    path = db_path or config.DB_PATH
    async with aiosqlite.connect(path) as conn:
        conn.row_factory = aiosqlite.Row
        await conn.execute("PRAGMA foreign_keys = ON;")
        yield conn


async def init_db(db_path: str | None = None) -> None:
    """Initializes the database schema if tables do not exist."""
    async with get_connection(db_path) as conn:
        await conn.executescript(SCHEMA_SQL)
        await conn.commit()


async def insert_counter(
    counter_id: str,
    name: str,
    token_role_map: dict[str, str],
    initial_value: int = 0,
    db_path: str | None = None,
) -> dict[str, Any]:
    """Inserts a counter and the hashes of its access tokens.
    token_role_map: { raw_uuid: role }
    """
    now = get_iso_now()
    async with get_connection(db_path) as conn:
        await conn.execute(
            """
            INSERT INTO counters (id, name, value, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (counter_id, name, initial_value, now, now),
        )

        for raw_uuid, role in token_role_map.items():
            t_hash = hash_token(raw_uuid)
            await conn.execute(
                """
                INSERT INTO counter_tokens (token_hash, counter_id, role, created_at)
                VALUES (?, ?, ?, ?)
                """,
                (t_hash, counter_id, role, now),
            )

        # Record registration event
        cursor = await conn.execute(
            """
            INSERT INTO counter_events (counter_id, action, delta, value_after, role, timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (counter_id, "create", None, initial_value, "admin", now),
        )
        event_id = cursor.lastrowid
        await conn.commit()

    return {
        "id": counter_id,
        "name": name,
        "value": initial_value,
        "created_at": now,
        "updated_at": now,
        "initial_event_id": event_id,
    }


async def resolve_token(
    raw_token: str, db_path: str | None = None
) -> tuple[dict[str, Any], str] | None:
    """Resolves a token by its SHA-256 hash.
    Returns (counter_dict, role) or None if invalid.
    """
    t_hash = hash_token(raw_token)
    async with get_connection(db_path) as conn:
        cursor = await conn.execute(
            """
            SELECT t.role, c.id, c.name, c.value, c.created_at, c.updated_at
            FROM counter_tokens t
            JOIN counters c ON t.counter_id = c.id
            WHERE t.token_hash = ?
            """,
            (t_hash,),
        )
        row = await cursor.fetchone()
        if not row:
            return None

        counter = {
            "id": row["id"],
            "name": row["name"],
            "value": row["value"],
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
        }
        role = row["role"]
        return counter, role


async def get_counter_by_id(
    counter_id: str, db_path: str | None = None
) -> dict[str, Any] | None:
    async with get_connection(db_path) as conn:
        cursor = await conn.execute(
            "SELECT id, name, value, created_at, updated_at FROM counters WHERE id = ?",
            (counter_id,),
        )
        row = await cursor.fetchone()
        if not row:
            return None
        return dict(row)


async def update_counter_value(
    counter_id: str,
    action: str,
    delta: int | None,
    new_value: int,
    role: str,
    db_path: str | None = None,
) -> dict[str, Any]:
    """Updates counter value and records the event in history."""
    now = get_iso_now()
    async with get_connection(db_path) as conn:
        await conn.execute(
            """
            UPDATE counters
            SET value = ?, updated_at = ?
            WHERE id = ?
            """,
            (new_value, now, counter_id),
        )

        cursor = await conn.execute(
            """
            INSERT INTO counter_events (counter_id, action, delta, value_after, role, timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (counter_id, action, delta, new_value, role, now),
        )
        event_id = cursor.lastrowid
        await conn.commit()

    return {
        "id": event_id,
        "counter_id": counter_id,
        "action": action,
        "delta": delta,
        "value": new_value,
        "role": role,
        "timestamp": now,
    }


async def get_counter_events(
    counter_id: str, limit: int = 50, db_path: str | None = None
) -> list[dict[str, Any]]:
    """Gets the most recent events for a counter."""
    async with get_connection(db_path) as conn:
        cursor = await conn.execute(
            """
            SELECT id, counter_id, action, delta, value_after AS value, role, timestamp
            FROM counter_events
            WHERE counter_id = ?
            ORDER BY id DESC
            LIMIT ?
            """,
            (counter_id, limit),
        )
        rows = await cursor.fetchall()
        # Return in ascending order for streaming/chronology
        return [dict(row) for row in reversed(rows)]
