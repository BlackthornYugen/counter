import pytest
import uuid
import json
from starlette.testclient import TestClient
from counter.main import app
from counter.db import init_db
from counter import config


@pytest.fixture(autouse=True)
async def setup_test_db(tmp_path, monkeypatch):
    db_file = str(tmp_path / "ws_test.db")
    monkeypatch.setattr(config, "DB_PATH", db_file)
    await init_db(db_file)


def test_websocket_flow():
    client = TestClient(app)

    ro = str(uuid.uuid4())
    rw = str(uuid.uuid4())
    admin = str(uuid.uuid4())

    # 1. Register counter room
    reg_res = client.post("/api/counters/register", json={
        "read_only_uuid": ro,
        "read_write_uuid": rw,
        "admin_uuid": admin,
        "initial_value": 10,
    })
    assert reg_res.status_code == 201

    # 2. Connect with invalid token -> close code 4401
    with client.websocket_connect("/ws/invalid-uuid") as ws:
        msg = ws.receive_json()
        assert msg["type"] == "error"
        assert msg["code"] == "invalid_token"

    # 3. Connect with Admin token
    with client.websocket_connect(f"/ws/{admin}") as ws_admin:
        init_admin = ws_admin.receive_json()
        assert init_admin["type"] == "connected"
        assert init_admin["access"] == "admin"
        assert init_admin["value"] == 10
        assert init_admin["permissions"]["can_admin"] is True

        # 4. Connect Read-Only token in parallel
        with client.websocket_connect(f"/ws/{ro}") as ws_ro:
            init_ro = ws_ro.receive_json()
            assert init_ro["type"] == "connected"
            assert init_ro["access"] == "read_only"
            assert init_ro["value"] == 10
            assert init_ro["permissions"]["can_write"] is False

            # 5. Read-only tries to increment -> receives error
            ws_ro.send_json({"action": "increment", "delta": 1})
            err_msg = ws_ro.receive_json()
            assert err_msg["type"] == "error"
            assert err_msg["code"] == "permission_denied"

            # 6. Admin increments by 5
            ws_admin.send_json({"action": "increment", "delta": 5})

            # Admin receives ack and broadcast update
            msg1 = ws_admin.receive_json()
            # Depending on ordering between broadcast and direct ack, both arrive:
            msgs_admin = [msg1, ws_admin.receive_json()]
            update_ev = next(m for m in msgs_admin if m.get("type") == "update")
            assert update_ev["value"] == 15
            assert update_ev["delta"] == 5

            # Read-only socket simultaneously receives the broadcast update!
            ro_update = ws_ro.receive_json()
            assert ro_update["type"] == "update"
            assert ro_update["value"] == 15
            assert ro_update["delta"] == 5

            # 7. Admin decrements by 2
            ws_admin.send_json({"action": "decrement", "delta": 2})
            _ = ws_admin.receive_json()
            _ = ws_admin.receive_json()

            ro_dec = ws_ro.receive_json()
            assert ro_dec["type"] == "update"
            assert ro_dec["value"] == 13
            assert ro_dec["delta"] == -2
