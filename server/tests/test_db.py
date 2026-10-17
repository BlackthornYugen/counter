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
    ro_uuid = str(uuid.uuid4())
    admin_uuid = str(uuid.uuid4())

    tokens = {
        ro_uuid: "read_only",
        admin_uuid: "admin",
    }

    result = await insert_counter(
        counter_id=cid,
        name="Test Room",
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
        assert hash_token(ro_uuid) in hashes_in_db
        assert hash_token(admin_uuid) in hashes_in_db
        assert ro_uuid not in hashes_in_db
        assert admin_uuid not in hashes_in_db


@pytest.mark.asyncio
async def test_resolve_token(tmp_path):
    db_file = str(tmp_path / "test.db")
    await init_db(db_file)

    cid = str(uuid.uuid4())
    ro_uuid = str(uuid.uuid4())
    rw_uuid = str(uuid.uuid4())
    admin_uuid = str(uuid.uuid4())

    tokens = {
        ro_uuid: "read_only",
        rw_uuid: "read_write",
        admin_uuid: "admin",
    }

    await insert_counter(cid, "Multi Role Counter", tokens, initial_value=0, db_path=db_file)

    # Test resolving each token
    res_ro = await resolve_token(ro_uuid, db_path=db_file)
    assert res_ro is not None
    counter, role = res_ro
    assert counter["id"] == cid
    assert role == "read_only"

    res_rw = await resolve_token(rw_uuid, db_path=db_file)
    assert res_rw is not None
    _, role = res_rw
    assert role == "read_write"

    res_admin = await resolve_token(admin_uuid, db_path=db_file)
    assert res_admin is not None
    _, role = res_admin
    assert role == "admin"

    # Invalid token
    res_invalid = await resolve_token("non-existent-uuid", db_path=db_file)
    assert res_invalid is None


@pytest.mark.asyncio
async def test_update_counter_and_events(tmp_path):
    db_file = str(tmp_path / "test.db")
    await init_db(db_file)

    cid = str(uuid.uuid4())
    admin_uuid = str(uuid.uuid4())
    await insert_counter(cid, "Counter", {admin_uuid: "admin"}, initial_value=0, db_path=db_file)

    # Increment
    ev1 = await update_counter_value(cid, "increment", delta=1, new_value=1, role="admin", db_path=db_file)
    assert ev1["value"] == 1
    assert ev1["delta"] == 1

    # Check updated counter
    c = await get_counter_by_id(cid, db_path=db_file)
    assert c["value"] == 1

    # Decrement
    ev2 = await update_counter_value(cid, "decrement", delta=-5, new_value=-4, role="admin", db_path=db_file)
    assert ev2["value"] == -4

    # Events history
    events = await get_counter_events(cid, limit=10, db_path=db_file)
    assert len(events) == 3  # create, increment, decrement
    assert events[0]["action"] == "create"
    assert events[1]["action"] == "increment"
    assert events[2]["action"] == "decrement"
