import pytest
import uuid
import json
import asyncio
from httpx import AsyncClient, ASGITransport
from counter.main import app
from counter.db import init_db
from counter import config
from counter.service import mutate_counter, register_async
from counter.api import sse_event_generator


@pytest.fixture(autouse=True)
async def setup_test_db(tmp_path, monkeypatch):
    db_file = str(tmp_path / "sse_test.db")
    monkeypatch.setattr(config, "DB_PATH", db_file)
    await init_db(db_file)


@pytest.mark.asyncio
async def test_sse_endpoint_validation():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # Invalid token returns 401
        res = await client.get("/sse/non-existent-uuid")
        assert res.status_code == 401
        assert res.json()["detail"] == "Unauthorized: invalid token/UUID."

        # Missing token returns 422 (validation error on Query parameter)
        res_missing = await client.get("/sse")
        assert res_missing.status_code == 422


@pytest.mark.asyncio
async def test_sse_generator_lifecycle_and_updates():
    v = str(uuid.uuid4())
    o = str(uuid.uuid4())

    reg = await register_async(v, o, initial_value=42)
    counter_id = reg["counter_id"]

    # 1. Test generator directly for real-time pub/sub verification
    gen = sse_event_generator(v)

    # First event yielded is 'connected'
    first_chunk = await anext(gen)
    assert first_chunk.startswith("event: connected\ndata: ")
    data_str = first_chunk.split("data: ", 1)[1].strip()
    conn_data = json.loads(data_str)
    assert conn_data["type"] == "connected"
    assert conn_data["access"] == "viewer"
    assert conn_data["access_legacy"] == "read_only"
    assert conn_data["value"] == 42
    assert conn_data["permissions"]["can_read"] is True
    assert conn_data["permissions"]["can_write"] is False

    # 2. Wait for next event in background task
    next_event_task = asyncio.create_task(anext(gen))

    # Let the generator reach queue.get()
    await asyncio.sleep(0.01)

    # Mutate counter with owner token
    await mutate_counter(o, action="increment", delta=8)

    # Next event yielded is 'update'
    update_chunk = await asyncio.wait_for(next_event_task, timeout=2.0)
    assert update_chunk.startswith("event: update\ndata: ")
    update_data_str = update_chunk.split("data: ", 1)[1].strip()
    update_data = json.loads(update_data_str)
    assert update_data["action"] == "increment"
    assert update_data["delta"] == 8
    assert update_data["value"] == 50
    assert update_data["role"] == "owner"
    assert update_data["role_legacy"] == "admin"
    assert update_data["access"] == "owner"
    assert update_data["access_legacy"] == "admin"

    # Clean close generator
    await gen.aclose()
