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
            "read_only_uuid": ro,
            "read_write_uuid": rw,
            "admin_uuid": admin,
        }
        res = await client.post("/api/counters/register", json=payload)
        assert res.status_code == 201
        data = res.json()
        assert "counter_id" in data
        # Dual-role keys: both new and legacy keys returned
        assert data["roles"]["viewer"] == ro
        assert data["roles"]["editor"] == rw
        assert data["roles"]["owner"] == admin
        assert data["roles"]["read_only"] == ro
        assert data["roles"]["read_write"] == rw
        assert data["roles"]["admin"] == admin


@pytest.mark.asyncio
async def test_rest_register_new_format():
    """Tests registration using new target naming (viewer, editor, owner)."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        v = str(uuid.uuid4())
        e = str(uuid.uuid4())
        o = str(uuid.uuid4())

        payload = {
            "viewer": v,
            "editor": e,
            "owner": o,
            "initial_value": 7,
        }
        res = await client.post("/api/counters/register", json=payload)
        assert res.status_code == 201
        data = res.json()
        assert data["value"] == 7
        assert data["roles"]["viewer"] == v
        assert data["roles"]["editor"] == e
        assert data["roles"]["owner"] == o


@pytest.mark.asyncio
async def test_rest_register_2_uuids():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        ro = str(uuid.uuid4())
        admin = str(uuid.uuid4())

        payload = {
            "readOnlyUUID": ro,
            "adminUUID": admin,
        }
        res = await client.post("/api/counters/register", json=payload)
        assert res.status_code == 201
        data = res.json()
        assert "read_write" not in data["roles"]
        assert "editor" not in data["roles"]
        assert data["roles"]["read_only"] == ro
        assert data["roles"]["admin"] == admin
        assert data["roles"]["viewer"] == ro
        assert data["roles"]["owner"] == admin


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
        assert info_ro.json()["access"] == "viewer"
        assert info_ro.json()["access_legacy"] == "read_only"
        assert info_ro.json()["permissions"]["can_write"] is False

        # 3. Viewer cannot increment
        inc_ro = await client.post(f"/api/counters/{ro}/increment", json={"delta": 1})
        assert inc_ro.status_code == 403

        # 4. Editor can increment
        inc_rw = await client.post(f"/api/counters/{rw}/increment", json={"delta": 5})
        assert inc_rw.status_code == 200
        assert inc_rw.json()["value"] == 5
        assert inc_rw.json()["access"] == "editor"
        assert inc_rw.json()["access_legacy"] == "read_write"

        # 5. Editor can decrement
        dec_rw = await client.post(f"/api/counters/{rw}/decrement", json={"delta": 2})
        assert dec_rw.status_code == 200
        assert dec_rw.json()["value"] == 3

        # 6. Reset endpoint has been removed (404/405)
        reset_res = await client.post(f"/api/counters/{admin}/reset", json={"delta": 1})
        assert reset_res.status_code in (404, 405)

        # 7. Check events history before deletion
        events_res = await client.get(f"/api/counters/{ro}/events")
        assert events_res.status_code == 200
        assert events_res.json()["access"] == "viewer"
        assert events_res.json()["access_legacy"] == "read_only"
        events = events_res.json()["events"]
        actions = [e["action"] for e in events]
        assert "create" in actions
        assert "increment" in actions
        assert "decrement" in actions

        # 8. Viewer cannot delete counter
        del_ro = await client.delete(f"/api/counters/{ro}")
        assert del_ro.status_code == 403

        # 9. Editor cannot delete counter
        del_rw = await client.delete(f"/api/counters/{rw}")
        assert del_rw.status_code == 403

        # 10. Owner can delete counter
        del_admin = await client.delete(f"/api/counters/{admin}")
        assert del_admin.status_code == 200
        assert del_admin.json()["deleted"] is True

        # 11. Subsequent access returns 401
        after_get = await client.get(f"/api/counters/{admin}")
        assert after_get.status_code == 401


@pytest.mark.asyncio
async def test_prometheus_metrics_endpoint():
    """Verifies that /metrics endpoint returns Prometheus telemetry and counter_registrations_total."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # Perform 1 legacy registration and 1 new registration
        v = str(uuid.uuid4())
        o = str(uuid.uuid4())
        await client.post("/api/counters/register", json={"viewer": v, "owner": o})

        ro = str(uuid.uuid4())
        admin = str(uuid.uuid4())
        await client.post("/api/counters/register", json={"readOnlyUUID": ro, "adminUUID": admin})

        res = await client.get("/metrics")
        assert res.status_code == 200
        content = res.text
        assert "counter_registrations_total" in content
        assert 'key_format="new"' in content
        assert 'key_format="legacy"' in content

        # Ingress blocking checks: public host or X-Forwarded-Host must return 404
        ingress_res_1 = await client.get("/metrics", headers={"X-Forwarded-Host": "pr-1-counter.jskw.dev"})
        assert ingress_res_1.status_code == 404

        ingress_res_2 = await client.get("/metrics", headers={"Host": "counter.jskw.dev"})
        assert ingress_res_2.status_code == 404

        ingress_res_3 = await client.get("/metrics/", headers={"Host": "counter.jskw.ca"})
        assert ingress_res_3.status_code == 404


@pytest.mark.asyncio
async def test_serve_privacy_route() -> None:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        # Test /privacy
        res = await client.get("/privacy")
        assert res.status_code == 200
        assert "Privacy Policy" in res.text
        assert "Core Operating Principles" in res.text
        assert "Zero-Identity Synchronization" in res.text
        assert "dev.jskw.count" in res.text

        # Test /privacy/
        res_slash = await client.get("/privacy/")
        assert res_slash.status_code == 200
        assert "Privacy Policy" in res_slash.text


@pytest.mark.asyncio
async def test_proxy_headers_and_custom_subpath() -> None:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        # Pass reverse proxy headers
        headers = {
            "x-forwarded-for": "203.0.113.195",
            "x-forwarded-proto": "https",
            "x-forwarded-host": "counter.staging.jskw.dev",
            "x-forwarded-port": "443",
            "x-forwarded-prefix": "/staging-counter",
        }
        res = await client.get("/health", headers=headers)
        assert res.status_code == 200
        data = res.json()
        assert data["status"] == "ok"
        assert data["service"] == "counter-server"
        assert "git_hash" in data


@pytest.mark.asyncio
async def test_rest_register_hybrid_format_and_zero_pii_metrics():
    """Verifies hybrid payload detection and privacy requirement that metrics contain zero PII."""
    from counter.metrics import counter_registrations_total

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # Hybrid registration (mixing new 'owner' with legacy 'read_only_uuid')
        o = str(uuid.uuid4())
        ro = str(uuid.uuid4())
        res = await client.post("/api/counters/register", json={
            "owner": o,
            "read_only_uuid": ro,
        })
        assert res.status_code == 201

        # Check /metrics output
        metrics_res = await client.get("/metrics")
        assert metrics_res.status_code == 200
        assert 'key_format="hybrid"' in metrics_res.text

        # Zero PII verification: Ensure metric label names are strictly key_format, client, and status
        assert counter_registrations_total._labelnames == ("key_format", "client", "status")


@pytest.mark.asyncio
async def test_rest_register_client_user_agent_metrics():
    """Verifies that Android versionCode and Web User-Agents are normalized in telemetry."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # 1. Android client with versionCode 11
        res1 = await client.post(
            "/api/counters/register",
            json={"viewer": str(uuid.uuid4()), "owner": str(uuid.uuid4())},
            headers={"User-Agent": "Counter-Android/11 (dev.jskw.count; Build 11)"},
        )
        assert res1.status_code == 201

        # 2. Web browser
        res2 = await client.post(
            "/api/counters/register",
            json={"viewer": str(uuid.uuid4()), "owner": str(uuid.uuid4())},
            headers={"User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"},
        )
        assert res2.status_code == 201

        # 3. Custom X-Client-Version header with full commit hash
        res3 = await client.post(
            "/api/counters/register",
            json={"viewer": str(uuid.uuid4()), "owner": str(uuid.uuid4())},
            headers={"X-Client-Version": "web-6aacbea123456789"},
        )
        assert res3.status_code == 201

        metrics = (await client.get("/metrics")).text
        assert 'client="android-11"' in metrics
        assert 'client="web-dev"' in metrics or 'client="web-' in metrics
        assert 'client="web-6aacbea"' in metrics


@pytest.mark.asyncio
async def test_rest_dual_role_flag_disabled(monkeypatch):
    """Verifies that when DUAL_ROLE_KEYS is False, REST responses omit legacy keys."""
    monkeypatch.setattr(config, "DUAL_ROLE_KEYS", False)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        v = str(uuid.uuid4())
        e = str(uuid.uuid4())
        o = str(uuid.uuid4())

        reg_res = await client.post("/api/counters/register", json={
            "viewer": v,
            "editor": e,
            "owner": o,
        })
        assert reg_res.status_code == 201
        data = reg_res.json()
        assert "owner" in data["roles"]
        assert "admin" not in data["roles"]
        assert "read_write" not in data["roles"]
        assert "read_only" not in data["roles"]

        # Counter info
        info_res = await client.get(f"/api/counters/{v}")
        assert info_res.status_code == 200
        info = info_res.json()
        assert info["access"] == "viewer"
        assert "access_legacy" not in info or info["access_legacy"] is None

