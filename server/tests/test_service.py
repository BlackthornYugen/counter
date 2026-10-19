import pytest
import uuid
from counter.service import (
    register,
    register_async,
    get_counter_info_by_token,
    mutate_counter,
    delete_counter,
    InvalidTokenError,
    PermissionDeniedError,
    DuplicateTokenError,
)
from counter import config


@pytest.mark.asyncio
async def test_register_3_uuids_async(tmp_path):
    db_file = str(tmp_path / "service.db")
    v = str(uuid.uuid4())
    e = str(uuid.uuid4())
    o = str(uuid.uuid4())

    result = await register_async(v, e, o, db_path=db_file)
    assert "counter_id" in result
    # Both new and legacy keys are populated when DUAL_ROLE_KEYS=True
    assert result["roles"]["viewer"] == v
    assert result["roles"]["editor"] == e
    assert result["roles"]["owner"] == o
    assert result["roles"]["read_only"] == v
    assert result["roles"]["read_write"] == e
    assert result["roles"]["admin"] == o
    assert len(result["token_hashes"]) == 6


@pytest.mark.asyncio
async def test_register_2_uuids_async(tmp_path):
    db_file = str(tmp_path / "service.db")
    v = str(uuid.uuid4())
    o = str(uuid.uuid4())

    result = await register_async(v, o, db_path=db_file)
    assert "counter_id" in result
    assert result["roles"]["viewer"] == v
    assert result["roles"]["owner"] == o
    assert result["roles"]["read_only"] == v
    assert result["roles"]["admin"] == o
    assert "editor" not in result["roles"]
    assert "read_write" not in result["roles"]
    assert len(result["token_hashes"]) == 4


def test_register_sync_interface(tmp_path):
    db_file = str(tmp_path / "sync.db")
    v = str(uuid.uuid4())
    e = str(uuid.uuid4())
    o = str(uuid.uuid4())

    # 3 UUIDs
    res3 = register(v, e, o, db_path=db_file)
    assert res3["roles"]["viewer"] == v
    assert res3["roles"]["editor"] == e
    assert res3["roles"]["owner"] == o
    assert res3["roles"]["read_only"] == v
    assert res3["roles"]["read_write"] == e
    assert res3["roles"]["admin"] == o

    # 2 UUIDs
    v2 = str(uuid.uuid4())
    o2 = str(uuid.uuid4())
    res2 = register(v2, o2, db_path=db_file)
    assert res2["roles"]["viewer"] == v2
    assert res2["roles"]["owner"] == o2
    assert res2["roles"]["read_only"] == v2
    assert res2["roles"]["admin"] == o2
    assert "editor" not in res2["roles"]


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
    v = str(uuid.uuid4())
    e = str(uuid.uuid4())
    o = str(uuid.uuid4())

    reg = await register_async(v, e, o, initial_value=5, db_path=db_file)
    counter_id = reg["counter_id"]

    # 1. Viewer tries to increment -> PermissionDeniedError
    with pytest.raises(PermissionDeniedError):
        await mutate_counter(v, action="increment", delta=1, db_path=db_file)

    # 2. Viewer tries to decrement -> PermissionDeniedError
    with pytest.raises(PermissionDeniedError):
        await mutate_counter(v, action="decrement", delta=1, db_path=db_file)

    # 3. Editor can increment
    res1 = await mutate_counter(e, action="increment", delta=2, db_path=db_file)
    assert res1["value"] == 7
    assert res1["access"] == "editor"
    assert res1["access_legacy"] == "read_write"

    # 4. Editor can decrement
    res2 = await mutate_counter(e, action="decrement", delta=3, db_path=db_file)
    assert res2["value"] == 4

    # 5. Owner and editor have same write privileges (inc/dec)
    res3 = await mutate_counter(o, action="increment", delta=5, db_path=db_file)
    assert res3["value"] == 9
    assert res3["access"] == "owner"
    assert res3["access_legacy"] == "admin"

    # 6. Reset action does not exist (decrements should be used instead)
    with pytest.raises(ValueError, match="Unknown counter action: 'reset'"):
        await mutate_counter(o, action="reset", delta=1, db_path=db_file)

    # 7. Viewer cannot delete counter
    with pytest.raises(PermissionDeniedError, match="cannot delete"):
        await delete_counter(v, db_path=db_file)

    # 8. Editor cannot delete counter
    with pytest.raises(PermissionDeniedError, match="cannot delete"):
        await delete_counter(e, db_path=db_file)

    # 9. Owner can delete counter
    del_res = await delete_counter(o, db_path=db_file)
    assert del_res["deleted"] is True
    assert del_res["counter_id"] == counter_id

    # 10. After deletion, token resolution fails
    from counter.db import resolve_token
    assert await resolve_token(o, db_path=db_file) is None
    assert await resolve_token(e, db_path=db_file) is None
    assert await resolve_token(v, db_path=db_file) is None


@pytest.mark.asyncio
async def test_dual_role_flag_disabled(tmp_path, monkeypatch):
    """Verifies that when DUAL_ROLE_KEYS=False, legacy keys are omitted."""
    monkeypatch.setattr(config, "DUAL_ROLE_KEYS", False)
    db_file = str(tmp_path / "flag_off.db")
    v = str(uuid.uuid4())
    e = str(uuid.uuid4())
    o = str(uuid.uuid4())

    reg = await register_async(v, e, o, db_path=db_file)
    assert "admin" not in reg["roles"]
    assert "read_write" not in reg["roles"]
    assert "read_only" not in reg["roles"]
    assert "owner" in reg["roles"]
    assert len(reg["token_hashes"]) == 3

    info = await get_counter_info_by_token(o, db_path=db_file)
    assert info["access"] == "owner"
    assert "access_legacy" not in info


@pytest.mark.asyncio
async def test_register_rejects_vanity_and_non_uuidv4(tmp_path):
    from counter.service import InvalidUUIDError
    db_file = str(tmp_path / "vanity.db")
    valid_v4_1 = str(uuid.uuid4())
    valid_v4_2 = str(uuid.uuid4())

    # 1. Reject vanity strings like 'a', 'b', 'c'
    with pytest.raises(InvalidUUIDError, match="must be formatted as a valid UUIDv4"):
        await register_async("a", "b", "c", db_path=db_file)

    with pytest.raises(InvalidUUIDError, match="must be formatted as a valid UUIDv4"):
        await register_async("read-only-vanity", valid_v4_1, valid_v4_2, db_path=db_file)

    # 2. Reject non-v4 UUIDs (e.g. UUIDv1)
    v1_uuid = str(uuid.uuid1())
    with pytest.raises(InvalidUUIDError, match="must be a UUID version 4"):
        await register_async(v1_uuid, valid_v4_1, valid_v4_2, db_path=db_file)


@pytest.mark.asyncio
async def test_register_already_registered_uuid_fails(tmp_path):
    from counter.service import TokenAlreadyRegisteredError
    db_file = str(tmp_path / "exist.db")
    v1 = str(uuid.uuid4())
    o1 = str(uuid.uuid4())

    # Register first room
    await register_async(v1, o1, db_path=db_file)

    # Trying to register again with same o1 or v1 must raise TokenAlreadyRegisteredError
    v2 = str(uuid.uuid4())
    with pytest.raises(TokenAlreadyRegisteredError, match="already registered"):
        await register_async(v2, o1, db_path=db_file)
