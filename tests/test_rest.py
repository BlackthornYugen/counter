import pytest
import uuid
from httpx import AsyncClient, ASGITransport
from counter.main import app
from counter.db import init_db
from counter import config


@pytest.fixture(autouse=True)
async def setup_test_db(tmp_path, monkeypatch):
    db_file = str(tmp_path / "rest_test.db")
    monkeypatch.setattr(config, "DB_PATH", db_file)
    await init_db(db_file)


@pytest.mark.asyncio
async def test_rest_register_3_uuids():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        ro = str(uuid.uuid4())
        rw = str(uuid.uuid4())
        admin = str(uuid.uuid4())

        payload = {
            "name": "REST 3 UUIDs",
            "read_only_uuid": ro,
            "read_write_uuid": rw,
            "admin_uuid": admin,
        }
        res = await client.post("/api/counters/register", json=payload)
        assert res.status_code == 201
        data = res.json()
        assert "counter_id" in data
        assert data["roles"]["read_only"] == ro
        assert data["roles"]["read_write"] == rw
        assert data["roles"]["admin"] == admin


@pytest.mark.asyncio
async def test_rest_register_2_uuids():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        ro = str(uuid.uuid4())
        admin = str(uuid.uuid4())

        payload = {
            "name": "REST 2 UUIDs",
            "readOnlyUUID": ro,
            "adminUUID": admin,
        }
        res = await client.post("/api/counters/register", json=payload)
        assert res.status_code == 201
        data = res.json()
        assert "read_write" not in data["roles"]
        assert data["roles"]["read_only"] == ro
        assert data["roles"]["admin"] == admin


@pytest.mark.asyncio
async def test_rest_register_rejects_vanity_urls():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # 1. Vanity strings 'a', 'b', 'c'
        res1 = await client.post("/api/counters/register", json={
            "readOnlyUUID": "a",
            "readWriteUUID": "b",
            "adminUUID": "c",
        })
        assert res1.status_code in (400, 422)

        # 2. Valid UUID format but wrong version (e.g. UUIDv1)
        res2 = await client.post("/api/counters/register", json={
            "readOnlyUUID": str(uuid.uuid1()),
            "adminUUID": str(uuid.uuid4()),
        })
        assert res2.status_code in (400, 422)


@pytest.mark.asyncio
async def test_rest_register_duplicate_across_rooms_returns_409():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        ro1 = str(uuid.uuid4())
        admin1 = str(uuid.uuid4())

        # First registration succeeds with 201
        res1 = await client.post("/api/counters/register", json={
            "readOnlyUUID": ro1,
            "adminUUID": admin1,
        })
        assert res1.status_code == 201

        # Second registration with same admin UUID must return 409 Conflict (not 500)
        ro2 = str(uuid.uuid4())
        res2 = await client.post("/api/counters/register", json={
            "readOnlyUUID": ro2,
            "adminUUID": admin1,
        })
        assert res2.status_code == 409
        assert "already registered" in res2.json()["detail"]


@pytest.mark.asyncio
async def test_rest_counter_lifecycle_and_permissions():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        ro = str(uuid.uuid4())
        rw = str(uuid.uuid4())
        admin = str(uuid.uuid4())

        # 1. Register
        reg_res = await client.post("/api/counters/register", json={
            "read_only_uuid": ro,
            "read_write_uuid": rw,
            "admin_uuid": admin,
            "initial_value": 0,
        })
        assert reg_res.status_code == 201

        # 2. Get info via ro token
        info_ro = await client.get(f"/api/counters/{ro}")
        assert info_ro.status_code == 200
        assert info_ro.json()["access"] == "read_only"
        assert info_ro.json()["permissions"]["can_write"] is False

        # 3. Read-only cannot increment
        inc_ro = await client.post(f"/api/counters/{ro}/increment", json={"delta": 1})
        assert inc_ro.status_code == 403

        # 4. Read-write can increment
        inc_rw = await client.post(f"/api/counters/{rw}/increment", json={"delta": 5})
        assert inc_rw.status_code == 200
        assert inc_rw.json()["value"] == 5
        assert inc_rw.json()["access"] == "read_write"

        # 5. Read-write can decrement
        dec_rw = await client.post(f"/api/counters/{rw}/decrement", json={"delta": 2})
        assert dec_rw.status_code == 200
        assert dec_rw.json()["value"] == 3

        # 6. Read-write cannot reset
        reset_rw = await client.post(f"/api/counters/{rw}/reset", json={"value": 0})
        assert reset_rw.status_code == 403

        # 7. Admin can reset
        reset_admin = await client.post(f"/api/counters/{admin}/reset", json={"value": 100})
        assert reset_admin.status_code == 200
        assert reset_admin.json()["value"] == 100
        assert reset_admin.json()["access"] == "admin"

        # 8. Check events history
        events_res = await client.get(f"/api/counters/{ro}/events")
        assert events_res.status_code == 200
        events = events_res.json()["events"]
        # Expected events: create, increment, decrement, reset
        actions = [e["action"] for e in events]
        assert "create" in actions
        assert "increment" in actions
        assert "decrement" in actions
        assert "reset" in actions
