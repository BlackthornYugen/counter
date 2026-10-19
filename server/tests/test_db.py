import pytest
import uuid
import aiosqlite
from counter.db import (
    hash_token,
    init_db,
    insert_counter,
    resolve_token,
    update_counter_value,
    get_counter_events,
    get_counter_by_id,
    migrate_db_roles,
)


@pytest.mark.asyncio
async def test_token_hashing():
    u = "c768f56e-8ff4-4363-8822-79ee8ca6eef4"
    h1 = hash_token(u)
    h2 = hash_token(f"  {u.upper()}  ")  # whitespace and casing normalization
    assert h1 == h2
    assert len(h1) == 64  # SHA-256 is 64 hex characters


@pytest.mark.asyncio
async def test_db_init_and_insert(tmp_path):
    db_file = str(tmp_path / "test.db")
    await init_db(db_file)

    cid = str(uuid.uuid4())
    viewer_uuid = str(uuid.uuid4())
    owner_uuid = str(uuid.uuid4())

    tokens = {
        viewer_uuid: "viewer",
        owner_uuid: "owner",
    }

    result = await insert_counter(
        counter_id=cid,
        token_role_map=tokens,
        initial_value=10,
        db_path=db_file,
    )
    assert result["id"] == cid
    assert result["value"] == 10

    # Verify that raw UUIDs are NOT in SQLite table, only hashes!
    async with aiosqlite.connect(db_file) as conn:
        cursor = await conn.execute("SELECT token_hash, role FROM counter_tokens WHERE counter_id = ?", (cid,))
        rows = await cursor.fetchall()
        assert len(rows) == 2
        hashes_in_db = {r[0] for r in rows}
        assert hash_token(viewer_uuid) in hashes_in_db
        assert hash_token(owner_uuid) in hashes_in_db
        assert viewer_uuid not in hashes_in_db
        assert owner_uuid not in hashes_in_db


@pytest.mark.asyncio
async def test_resolve_token(tmp_path):
    db_file = str(tmp_path / "test.db")
    await init_db(db_file)

    cid = str(uuid.uuid4())
    viewer_uuid = str(uuid.uuid4())
    editor_uuid = str(uuid.uuid4())
    owner_uuid = str(uuid.uuid4())

    tokens = {
        viewer_uuid: "viewer",
        editor_uuid: "editor",
        owner_uuid: "owner",
    }

    await insert_counter(cid, tokens, initial_value=0, db_path=db_file)

    # Test resolving each token
    res_v = await resolve_token(viewer_uuid, db_path=db_file)
    assert res_v is not None
    counter, role = res_v
    assert counter["id"] == cid
    assert role == "viewer"

    res_e = await resolve_token(editor_uuid, db_path=db_file)
    assert res_e is not None
    _, role = res_e
    assert role == "editor"

    res_o = await resolve_token(owner_uuid, db_path=db_file)
    assert res_o is not None
    _, role = res_o
    assert role == "owner"

    # Invalid token
    res_invalid = await resolve_token("non-existent-uuid", db_path=db_file)
    assert res_invalid is None


@pytest.mark.asyncio
async def test_update_counter_and_events(tmp_path):
    db_file = str(tmp_path / "test.db")
    await init_db(db_file)

    cid = str(uuid.uuid4())
    owner_uuid = str(uuid.uuid4())
    await insert_counter(cid, {owner_uuid: "owner"}, initial_value=0, db_path=db_file)

    # Increment
    ev1 = await update_counter_value(cid, "increment", delta=1, new_value=1, role="owner", db_path=db_file)
    assert ev1["value"] == 1
    assert ev1["delta"] == 1

    # Check updated counter
    c = await get_counter_by_id(cid, db_path=db_file)
    assert c["value"] == 1

    # Decrement
    ev2 = await update_counter_value(cid, "decrement", delta=-5, new_value=-4, role="owner", db_path=db_file)
    assert ev2["value"] == -4

    # Events history
    events = await get_counter_events(cid, limit=10, db_path=db_file)
    assert len(events) == 3  # create, increment, decrement
    assert events[0]["action"] == "create"
    assert events[1]["action"] == "increment"
    assert events[2]["action"] == "decrement"


@pytest.mark.asyncio
async def test_database_migration_from_legacy(tmp_path):
    """Verifies that an existing database with legacy roles ('read_only', 'read_write', 'admin')
    is automatically migrated to ('viewer', 'editor', 'owner') upon init_db().
    """
    db_file = str(tmp_path / "legacy.db")

    # Manually create legacy schema and insert records
    async with aiosqlite.connect(db_file) as conn:
        await conn.executescript("""
            CREATE TABLE counters (
                id TEXT PRIMARY KEY,
                value INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE counter_tokens (
                token_hash TEXT PRIMARY KEY,
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
            INSERT INTO counters VALUES ('c1', 10, '2026-01-01', '2026-01-01');
            INSERT INTO counter_tokens VALUES ('hash_ro', 'c1', 'read_only', '2026-01-01');
            INSERT INTO counter_tokens VALUES ('hash_rw', 'c1', 'read_write', '2026-01-01');
            INSERT INTO counter_tokens VALUES ('hash_admin', 'c1', 'admin', '2026-01-01');
            INSERT INTO counter_events VALUES (1, 'c1', 'create', NULL, 10, 'admin', '2026-01-01');
        """)
        await conn.commit()

    # Run init_db (which executes migrate_db_roles)
    await init_db(db_file)

    # Verify that records and schema have migrated
    async with aiosqlite.connect(db_file) as conn:
        conn.row_factory = aiosqlite.Row
        cursor = await conn.execute("SELECT role FROM counter_tokens ORDER BY role")
        roles = [row["role"] for row in await cursor.fetchall()]
        assert roles == ["editor", "owner", "viewer"]

        cursor_ev = await conn.execute("SELECT role FROM counter_events WHERE id = 1")
        ev_row = await cursor_ev.fetchone()
        assert ev_row["role"] == "owner"
