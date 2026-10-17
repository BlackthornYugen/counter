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

    reg = await register_async(ro, rw, admin, initial_value=5, db_path=db_file)
    counter_id = reg["counter_id"]

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

    # 5. Admin and read-write have same write privileges (inc/dec)
    res3 = await mutate_counter(admin, action="increment", delta=5, db_path=db_file)
    assert res3["value"] == 9
    assert res3["access"] == "admin"

    # 6. Reset action does not exist (decrements should be used instead)
    with pytest.raises(ValueError, match="Unknown counter action: 'reset'"):
        await mutate_counter(admin, action="reset", delta=1, db_path=db_file)

    # 7. Read-only cannot delete counter
    with pytest.raises(PermissionDeniedError, match="cannot delete"):
        await delete_counter(ro, db_path=db_file)

    # 8. Read-write cannot delete counter
    with pytest.raises(PermissionDeniedError, match="cannot delete"):
        await delete_counter(rw, db_path=db_file)

    # 9. Admin can delete counter
    del_res = await delete_counter(admin, db_path=db_file)
    assert del_res["deleted"] is True
    assert del_res["counter_id"] == counter_id

    # 10. After deletion, token resolution fails
    from counter.db import resolve_token
    assert await resolve_token(admin, db_path=db_file) is None
    assert await resolve_token(rw, db_path=db_file) is None
    assert await resolve_token(ro, db_path=db_file) is None


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
    ro1 = str(uuid.uuid4())
    admin1 = str(uuid.uuid4())

    # Register first room
    await register_async(ro1, admin1, db_path=db_file)

    # Trying to register again with same admin1 or ro1 must raise TokenAlreadyRegisteredError
    ro2 = str(uuid.uuid4())
    with pytest.raises(TokenAlreadyRegisteredError, match="already registered"):
        await register_async(ro2, admin1, db_path=db_file)
