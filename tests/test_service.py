import pytest
import uuid
from counter.service import (
    register,
    register_async,
    get_counter_info_by_token,
    mutate_counter,
    InvalidTokenError,
    PermissionDeniedError,
    DuplicateTokenError,
)


@pytest.mark.asyncio
async def test_register_3_uuids_async(tmp_path):
    db_file = str(tmp_path / "service.db")
    ro = str(uuid.uuid4())
    rw = str(uuid.uuid4())
    admin = str(uuid.uuid4())

    result = await register_async(ro, rw, admin, db_path=db_file)
    assert "counter_id" in result
    assert result["roles"]["read_only"] == ro
    assert result["roles"]["read_write"] == rw
    assert result["roles"]["admin"] == admin
    assert len(result["token_hashes"]) == 3


@pytest.mark.asyncio
async def test_register_2_uuids_async(tmp_path):
    db_file = str(tmp_path / "service.db")
    ro = str(uuid.uuid4())
    admin = str(uuid.uuid4())

    result = await register_async(ro, admin, db_path=db_file)
    assert "counter_id" in result
    assert result["roles"]["read_only"] == ro
    assert result["roles"]["admin"] == admin
    assert "read_write" not in result["roles"]
    assert len(result["token_hashes"]) == 2


def test_register_sync_interface(tmp_path):
    db_file = str(tmp_path / "sync.db")
    ro = str(uuid.uuid4())
    rw = str(uuid.uuid4())
    admin = str(uuid.uuid4())

    # 3 UUIDs
    res3 = register(ro, rw, admin, db_path=db_file)
    assert res3["roles"]["read_only"] == ro
    assert res3["roles"]["read_write"] == rw
    assert res3["roles"]["admin"] == admin

    # 2 UUIDs
    ro2 = str(uuid.uuid4())
    admin2 = str(uuid.uuid4())
    res2 = register(ro2, admin2, db_path=db_file)
    assert res2["roles"]["read_only"] == ro2
    assert res2["roles"]["admin"] == admin2
    assert "read_write" not in res2["roles"]


@pytest.mark.asyncio
async def test_register_duplicate_tokens_fails(tmp_path):
    db_file = str(tmp_path / "dup.db")
    same_token = str(uuid.uuid4())
    other_token = str(uuid.uuid4())

    with pytest.raises(DuplicateTokenError):
        await register_async(same_token, same_token, db_path=db_file)

    with pytest.raises(DuplicateTokenError):
        await register_async(same_token, other_token, same_token, db_path=db_file)


@pytest.mark.asyncio
async def test_mutate_permissions(tmp_path):
    db_file = str(tmp_path / "mutate.db")
    ro = str(uuid.uuid4())
    rw = str(uuid.uuid4())
    admin = str(uuid.uuid4())

    await register_async(ro, rw, admin, initial_value=5, db_path=db_file)

    # 1. Read-only tries to increment -> PermissionDeniedError
    with pytest.raises(PermissionDeniedError):
        await mutate_counter(ro, action="increment", delta=1, db_path=db_file)

    # 2. Read-only tries to decrement -> PermissionDeniedError
    with pytest.raises(PermissionDeniedError):
        await mutate_counter(ro, action="decrement", delta=1, db_path=db_file)

    # 3. Read-write can increment
    res1 = await mutate_counter(rw, action="increment", delta=2, db_path=db_file)
    assert res1["value"] == 7
    assert res1["access"] == "read_write"

    # 4. Read-write can decrement
    res2 = await mutate_counter(rw, action="decrement", delta=3, db_path=db_file)
    assert res2["value"] == 4

    # 5. Read-write cannot reset
    with pytest.raises(PermissionDeniedError):
        await mutate_counter(rw, action="reset", target_value=0, db_path=db_file)

    # 6. Admin can reset
    res3 = await mutate_counter(admin, action="reset", target_value=100, db_path=db_file)
    assert res3["value"] == 100
    assert res3["access"] == "admin"
