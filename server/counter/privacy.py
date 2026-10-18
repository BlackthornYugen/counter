"""Privacy policy versioning and git diff engine for JSKW Counter."""

import datetime
import difflib
from pathlib import Path
from typing import Tuple, List, Optional
import re

from fastapi import APIRouter, HTTPException, Response
from fastapi.responses import HTMLResponse, FileResponse
from pygments import highlight
from pygments.lexers import DiffLexer
from pygments.formatters import HtmlFormatter

from counter.config import STATIC_DIR

router = APIRouter()

DATE_REGEX = re.compile(r"^\d{4}-\d{2}-\d{2}$")
DIFF_REGEX = re.compile(r"^(\d{4}-\d{2}-\d{2})-vs-(\d{4}-\d{2}-\d{2})$")


def get_versions_dir() -> Path:
    return STATIC_DIR / "privacy" / "versions"


def get_known_versions() -> List[str]:
    """Return all known policy version dates sorted in ascending order."""
    versions_dir = get_versions_dir()
    if versions_dir.is_dir():
        found = [
            f.stem
            for f in versions_dir.glob("*.html")
            if DATE_REGEX.match(f.stem)
        ]
        if found:
            return sorted(found)
    return ["2026-10-16", "2026-10-18"]


def resolve_effective_policy(target_date_str: str) -> Optional[str]:
    """Resolve a target date to the policy version that was effective on that date.
    
    If the target date is prior to the first enacted policy, returns None.
    Otherwise, returns the latest policy version enacted on or before target_date.
    """
    try:
        target_date = datetime.date.fromisoformat(target_date_str)
    except ValueError:
        return None

    versions = get_known_versions()
    parsed_versions = []
    for v in versions:
        try:
            parsed_versions.append((datetime.date.fromisoformat(v), v))
        except ValueError:
            continue

    parsed_versions.sort(key=lambda x: x[0])
    if not parsed_versions:
        return None

    earliest_date = parsed_versions[0][0]
    if target_date < earliest_date:
        return None

    effective_version = parsed_versions[0][1]
    for pdate, vname in parsed_versions:
        if pdate <= target_date:
            effective_version = vname
        else:
            break

    return effective_version


def get_policy_file(version: str) -> Optional[Path]:
    path = get_versions_dir() / f"{version}.html"
    if path.is_file():
        return path
    fallback = STATIC_DIR / "privacy" / "index.html"
    if fallback.is_file():
        return fallback
    return None


def generate_diff(v1: str, v2: str) -> Tuple[str, int, int]:
    """Generate a highlighted git diff HTML string, additions count, and deletions count."""
    file1 = get_policy_file(v1)
    file2 = get_policy_file(v2)

    if not file1 or not file2:
        return "<p class='diff-empty-notice'>Policy files could not be located.</p>", 0, 0

    lines1 = file1.read_text(encoding="utf-8").splitlines(keepends=True)
    lines2 = file2.read_text(encoding="utf-8").splitlines(keepends=True)

    diff_lines = list(
        difflib.unified_diff(
            lines1,
            lines2,
            fromfile=f"privacy/{v1}.html",
            tofile=f"privacy/{v2}.html",
            n=3,
        )
    )

    if not diff_lines:
        return "", 0, 0

    additions = sum(1 for line in diff_lines if line.startswith("+") and not line.startswith("+++"))
    deletions = sum(1 for line in diff_lines if line.startswith("-") and not line.startswith("---"))

    udiff_text = "".join(diff_lines)
    formatter = HtmlFormatter(nowrap=False, cssclass="git-diff-body", linenos="table")
    highlighted_html = highlight(udiff_text, DiffLexer(), formatter)

    return highlighted_html, additions, deletions


def render_not_found_page(detail: str) -> HTMLResponse:
    """Render a themed 404 page for privacy policy errors."""
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Privacy Policy Version Not Found — JSKW Counter</title>
  <link rel="stylesheet" href="/static/style.css">
</head>
<body>
  <div class="app-container">
    <header class="app-header">
      <div class="logo-group">
        <div class="logo-icon">⚡</div>
        <div>
          <h1 class="app-title">JSKW Counter</h1>
        </div>
      </div>
    </header>
    <main class="tab-view">
      <section class="card privacy-card" style="text-align: center; padding: 48px 24px;">
        <div class="privacy-tag tag-archived">404 NOT FOUND</div>
        <h1 class="privacy-title" style="margin-top: 14px;">Policy Version Not Found</h1>
        <p class="privacy-meta" style="margin: 20px auto; max-width: 600px;">
          {detail}
        </p>
        <div style="margin-top: 30px;">
          <a href="/privacy" class="btn-version-action active">View Current Privacy Policy &rarr;</a>
        </div>
      </section>
    </main>
  </div>
</body>
</html>"""
    return HTMLResponse(content=html, status_code=404)


def render_diff_page(
    date1: str,
    date2: str,
    effective1: str,
    effective2: str,
    diff_html: str,
    additions: int,
    deletions: int,
) -> HTMLResponse:
    """Render the full comparison page highlighting changes like a git diff."""
    if not diff_html:
        diff_content = f"""
        <div class="diff-empty-notice">
          <strong>No Differences Detected</strong>
          <p style="margin-top: 8px;">The effective policies for both dates ({effective1} and {effective2}) are completely identical.</p>
        </div>
        """
    else:
        diff_content = f"""
        <div class="git-diff-wrapper">
          <div class="git-diff-bar">
            <span class="git-diff-filename">diff --git a/privacy/{effective1}.html b/privacy/{effective2}.html</span>
            <span>unified format</span>
          </div>
          {diff_html}
        </div>
        """

    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Compare Privacy Policies ({date1} vs {date2}) — JSKW Counter</title>
  <meta name="description" content="Side-by-side git diff comparison of JSKW Counter privacy policies between {date1} and {date2}.">
  <link rel="stylesheet" href="/static/node_modules/@fontsource/outfit/400.css">
  <link rel="stylesheet" href="/static/node_modules/@fontsource/outfit/500.css">
  <link rel="stylesheet" href="/static/node_modules/@fontsource/outfit/600.css">
  <link rel="stylesheet" href="/static/node_modules/@fontsource/outfit/700.css">
  <link rel="stylesheet" href="/static/node_modules/@fontsource/outfit/800.css">
  <link rel="stylesheet" href="/static/node_modules/@fontsource/jetbrains-mono/400.css">
  <link rel="stylesheet" href="/static/node_modules/@fontsource/jetbrains-mono/600.css">
  <link rel="stylesheet" href="/static/node_modules/@fontsource/jetbrains-mono/700.css">
  <link rel="stylesheet" href="/static/style.css">
</head>
<body>
  <div class="app-container">
    
    <!-- Header -->
    <header class="app-header">
      <div class="logo-group">
        <div class="logo-icon">⚡</div>
        <div>
          <h1 class="app-title">JSKW Counter</h1>
        </div>
      </div>
      <div class="header-badges">
        <span class="badge badge-server">● Python / FastAPI</span>
        <a href="/docs" target="_blank" class="badge badge-docs">Swagger API Docs ↗</a>
      </div>
    </header>

    <!-- Main Navigation Bar -->
    <nav class="main-nav-tabs" aria-label="Main Navigation">
      <a href="/" class="tab-button" id="tab-dashboard">
        <span class="tab-icon">⚡</span>
        <span class="tab-label">Counter Dashboard</span>
      </a>
      <a href="/privacy" class="tab-button active" id="tab-privacy">
        <span class="tab-icon">🛡️</span>
        <span class="tab-label">Privacy Policy</span>
      </a>
    </nav>

    <!-- Diff Content -->
    <main id="view-diff" class="tab-view">
      <section class="card privacy-card git-diff-card">
        <div class="privacy-header diff-header">
          <div class="privacy-tag">GIT DIFF COMPARISON</div>
          <h1 class="privacy-title">Privacy Policy Changes</h1>
          <p class="privacy-meta">
            Comparing policy effective on <code>{date1}</code> (v{effective1}) with policy effective on <code>{date2}</code> (v{effective2}).
          </p>

          <div class="diff-meta-bar">
            <div class="diff-compare-tags">
              <span>Baseline:</span>
              <a href="/privacy/{effective1}" class="diff-version-tag">
                <span>📄 {effective1}</span>
                <span style="opacity: 0.6;">(view)</span>
              </a>
              <span style="color: var(--text-dim);">&rarr;</span>
              <span>Target:</span>
              <a href="/privacy/{effective2}" class="diff-version-tag">
                <span>📄 {effective2}</span>
                <span style="opacity: 0.6;">(view)</span>
              </a>
            </div>

            <div class="diff-stats-box">
              <span class="diff-stat-add">+{additions} lines</span>
              <span class="diff-stat-del">-{deletions} lines</span>
            </div>
          </div>

          <form class="diff-picker-form" onsubmit="event.preventDefault(); const f = document.getElementById('diff-from').value; const t = document.getElementById('diff-to').value; window.location.href = '/privacy/' + f + '-vs-' + t;">
            <span class="picker-label">Compare other versions:</span>
            <div class="picker-controls">
              <label for="diff-from" class="sr-only">From version</label>
              <select id="diff-from" class="diff-select">
                {''.join(f'<option value="{v}" {"selected" if v == effective1 else ""}>{v}</option>' for v in get_known_versions())}
              </select>
              <span style="color: var(--text-dim); font-size: 13px;">to</span>
              <label for="diff-to" class="sr-only">To version</label>
              <select id="diff-to" class="diff-select">
                {''.join(f'<option value="{v}" {"selected" if v == effective2 else ""}>{v}</option>' for v in get_known_versions())}
              </select>
              <button type="submit" class="btn-compare-pill" style="cursor: pointer;">Compare &rarr;</button>
            </div>
          </form>
        </div>

        {diff_content}

        <div class="privacy-footer-nav" style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px;">
          <a href="/privacy" class="btn-back-to-counter">
            <span>&larr;</span> Return to Current Privacy Policy
          </a>
          <div style="display: flex; gap: 10px;">
            <a href="/privacy/{effective1}" class="btn-version-action diff-action">View {effective1} Policy</a>
            <a href="/privacy/{effective2}" class="btn-version-action active">View {effective2} Policy</a>
          </div>
        </div>
      </section>
    </main>

  </div>
</body>
</html>"""
    return HTMLResponse(content=html)


@router.get("/privacy", include_in_schema=False)
@router.get("/privacy/", include_in_schema=False)
async def serve_current_privacy() -> Response:
    """Serve the latest / current privacy policy."""
    versions = get_known_versions()
    latest_version = versions[-1]
    policy_file = get_policy_file(latest_version)
    if policy_file and policy_file.is_file():
        return FileResponse(str(policy_file))
    return FileResponse(str(STATIC_DIR / "privacy" / "index.html"))


@router.get("/privacy/{spec}", include_in_schema=False)
@router.get("/privacy/{spec}/", include_in_schema=False)
async def serve_privacy_version_or_diff(spec: str) -> Response:
    """Handle /privacy/YYYY-MM-DD or /privacy/YYYY-MM-DD-vs-YYYY-MM-DD."""
    spec = spec.strip("/")

    # Check for comparison format: YYYY-MM-DD-vs-YYYY-MM-DD
    diff_match = DIFF_REGEX.match(spec)
    if diff_match:
        date1, date2 = diff_match.group(1), diff_match.group(2)
        eff1 = resolve_effective_policy(date1)
        eff2 = resolve_effective_policy(date2)

        if not eff1:
            return render_not_found_page(
                f"No privacy policy was effective for date '{date1}'. The initial privacy policy was enacted on October 16, 2026."
            )
        if not eff2:
            return render_not_found_page(
                f"No privacy policy was effective for date '{date2}'. The initial privacy policy was enacted on October 16, 2026."
            )

        diff_html, additions, deletions = generate_diff(eff1, eff2)
        return render_diff_page(date1, date2, eff1, eff2, diff_html, additions, deletions)

    # Check for single date format: YYYY-MM-DD
    if DATE_REGEX.match(spec):
        effective = resolve_effective_policy(spec)
        if not effective:
            return render_not_found_page(
                f"No privacy policy was effective for date '{spec}'. The initial privacy policy was enacted on October 16, 2026."
            )

        policy_file = get_policy_file(effective)
        if not policy_file or not policy_file.is_file():
            return render_not_found_page(f"Policy file for version '{effective}' is missing.")

        return FileResponse(str(policy_file))

    # Any other unrecognized spec
    return render_not_found_page(
        f"Invalid privacy policy path '{spec}'. Expected /privacy/YYYY-MM-DD or /privacy/YYYY-MM-DD-vs-YYYY-MM-DD."
    )
