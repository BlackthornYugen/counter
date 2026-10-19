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
    value INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS counter_tokens (
    token_hash TEXT PRIMARY KEY,
    counter_id TEXT NOT NULL,
    role TEXT NOT NULL CHECK(role IN ('viewer', 'editor', 'owner')),
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


async def migrate_db_roles(conn: aiosqlite.Connection) -> None:
    """Migrates existing database records and constraints from legacy role names
    ('read_only', 'read_write', 'admin') to new role names ('viewer', 'editor', 'owner').
    """
    cursor = await conn.execute(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='counter_tokens'"
    )
    row = await cursor.fetchone()
    if row and row[0]:
        table_sql = row[0]
        # Check if table still has the legacy CHECK constraint
        if "read_only" in table_sql or "owner" not in table_sql:
            await conn.execute("PRAGMA foreign_keys = OFF;")
            await conn.execute("""
                CREATE TABLE IF NOT EXISTS counter_tokens_new (
                    token_hash TEXT PRIMARY KEY,
                    counter_id TEXT NOT NULL,
                    role TEXT NOT NULL CHECK(role IN ('viewer', 'editor', 'owner')),
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (counter_id) REFERENCES counters(id) ON DELETE CASCADE
                );
            """)
            await conn.execute("""
                INSERT OR IGNORE INTO counter_tokens_new (token_hash, counter_id, role, created_at)
                SELECT token_hash, counter_id,
                    CASE role
                        WHEN 'read_only' THEN 'viewer'
                        WHEN 'read_write' THEN 'editor'
                        WHEN 'admin' THEN 'owner'
                        ELSE role
                    END,
                    created_at
                FROM counter_tokens;
            """)
            await conn.execute("DROP TABLE counter_tokens;")
            await conn.execute("ALTER TABLE counter_tokens_new RENAME TO counter_tokens;")
            await conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_counter_tokens_counter_id ON counter_tokens(counter_id);"
            )
            await conn.execute("PRAGMA foreign_keys = ON;")

    # Migrate events history
    cursor_ev = await conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='counter_events'"
    )
    if await cursor_ev.fetchone():
        await conn.execute("UPDATE counter_events SET role = 'viewer' WHERE role = 'read_only';")
        await conn.execute("UPDATE counter_events SET role = 'editor' WHERE role = 'read_write';")
        await conn.execute("UPDATE counter_events SET role = 'owner' WHERE role = 'admin';")


async def init_db(db_path: str | None = None) -> None:
    """Initializes the database schema if tables do not exist and runs role migrations."""
    async with get_connection(db_path) as conn:
        await conn.executescript(SCHEMA_SQL)
        await migrate_db_roles(conn)
        await conn.commit()


async def check_tokens_exist(tokens: list[str], db_path: str | None = None) -> list[str]:
    """Checks which tokens already exist in counter_tokens. Returns list of existing raw tokens."""
    existing = []
    async with get_connection(db_path) as conn:
        for t in tokens:
            t_hash = hash_token(t)
            cursor = await conn.execute(
                "SELECT 1 FROM counter_tokens WHERE token_hash = ?", (t_hash,)
            )
            row = await cursor.fetchone()
            if row:
                existing.append(t)
    return existing


async def insert_counter(
    counter_id: str,
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
            INSERT INTO counters (id, value, created_at, updated_at)
            VALUES (?, ?, ?, ?)
            """,
            (counter_id, initial_value, now, now),
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
            (counter_id, "create", None, initial_value, "owner", now),
        )
        event_id = cursor.lastrowid
        await conn.commit()

    return {
        "id": counter_id,
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
            SELECT t.role, c.id, c.value, c.created_at, c.updated_at
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
            "SELECT id, value, created_at, updated_at FROM counters WHERE id = ?",
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


async def delete_counter_by_id(
    counter_id: str, db_path: str | None = None
) -> bool:
    """Deletes a counter by ID. Cascades to tokens and events."""
    async with get_connection(db_path) as conn:
        cursor = await conn.execute("DELETE FROM counters WHERE id = ?", (counter_id,))
        await conn.commit()
        return cursor.rowcount > 0

