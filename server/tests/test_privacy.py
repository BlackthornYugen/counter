import pytest
from httpx import AsyncClient, ASGITransport
from counter.main import app


@pytest.mark.asyncio
async def test_current_privacy_endpoint() -> None:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        # Test /privacy
        res = await client.get("/privacy")
        assert res.status_code == 200
        assert "Privacy Policy" in res.text
        assert "October 19, 2026" in res.text
        assert "Core Operating Principles" in res.text
        assert "dev.jskw.count" in res.text
        # Link to previous policy
        assert "/privacy/2026-10-18" in res.text
        # Compare with link
        assert "/privacy/2026-10-18-vs-2026-10-19" in res.text

        # Test /privacy/
        res_slash = await client.get("/privacy/")
        assert res_slash.status_code == 200
        assert "Privacy Policy" in res_slash.text
        assert "October 19, 2026" in res_slash.text


@pytest.mark.asyncio
async def test_archived_privacy_version() -> None:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        res = await client.get("/privacy/2026-10-16")
        assert res.status_code == 200
        assert "October 16, 2026" in res.text
        assert "Archived Policy Version" in res.text
        # Links to current policy and compare diff
        assert 'href="/privacy"' in res.text
        assert "/privacy/2026-10-16-vs-2026-10-19" in res.text

        # Also test 2026-10-18 archived version
        res_18 = await client.get("/privacy/2026-10-18")
        assert res_18.status_code == 200
        assert "October 18, 2026" in res_18.text
        assert "Archived Policy Version" in res_18.text
        assert 'href="/privacy"' in res_18.text
        assert "/privacy/2026-10-18-vs-2026-10-19" in res_18.text


@pytest.mark.asyncio
async def test_effective_date_resolution() -> None:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        # Exact date for 2026-10-16
        res_16 = await client.get("/privacy/2026-10-16")
        assert res_16.status_code == 200
        assert "October 16, 2026" in res_16.text

        # Exact date for 2026-10-18
        res_18 = await client.get("/privacy/2026-10-18")
        assert res_18.status_code == 200
        assert "October 18, 2026" in res_18.text

        # Exact date for 2026-10-19
        res_19 = await client.get("/privacy/2026-10-19")
        assert res_19.status_code == 200
        assert "October 19, 2026" in res_19.text

        # Future date resolves to latest effective policy (2026-10-19)
        res_future = await client.get("/privacy/2026-12-01")
        assert res_future.status_code == 200
        assert "October 19, 2026" in res_future.text

        # Date prior to initial policy returns 404
        res_before = await client.get("/privacy/2026-10-15")
        assert res_before.status_code == 404
        assert "No privacy policy was effective for date" in res_before.text


@pytest.mark.asyncio
async def test_privacy_diff_view() -> None:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        res = await client.get("/privacy/2026-10-18-vs-2026-10-19")
        assert res.status_code == 200
        assert "GIT DIFF COMPARISON" in res.text
        assert "diff --git a/privacy/2026-10-18.html b/privacy/2026-10-19.html" in res.text
        assert "git-diff-body" in res.text
        # Pygments highlighted insertions (.gi) and deletions (.gd)
        assert 'class="gd"' in res.text
        assert 'class="gi"' in res.text
        # Diff statistics
        assert "lines" in res.text
        # Back and version navigation links
        assert 'href="/privacy"' in res.text
        assert 'href="/privacy/2026-10-18"' in res.text
        assert 'href="/privacy/2026-10-19"' in res.text


@pytest.mark.asyncio
async def test_privacy_diff_reverse_and_resolution() -> None:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        # Reverse comparison
        res_rev = await client.get("/privacy/2026-10-19-vs-2026-10-18")
        assert res_rev.status_code == 200
        assert "diff --git a/privacy/2026-10-19.html b/privacy/2026-10-18.html" in res_rev.text

        # Future date in comparison resolves to effective policy
        res_res = await client.get("/privacy/2026-10-18-vs-2026-11-01")
        assert res_res.status_code == 200
        assert "diff --git a/privacy/2026-10-18.html b/privacy/2026-10-19.html" in res_res.text


@pytest.mark.asyncio
async def test_privacy_diff_identical_dates() -> None:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        res = await client.get("/privacy/2026-10-19-vs-2026-10-19")
        assert res.status_code == 200
        assert "No Differences Detected" in res.text


@pytest.mark.asyncio
async def test_privacy_invalid_routes() -> None:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        res_inv_date = await client.get("/privacy/not-a-date")
        assert res_inv_date.status_code == 404

        res_inv_calendar = await client.get("/privacy/2026-02-31")
        assert res_inv_calendar.status_code == 404

        res_inv_diff_date = await client.get("/privacy/2026-10-10-vs-2026-10-18")
        assert res_inv_diff_date.status_code == 404
