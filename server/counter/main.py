from contextlib import asynccontextmanager
from typing import AsyncGenerator
import uvicorn
from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from uvicorn.middleware.proxy_headers import ProxyHeadersMiddleware
from prometheus_client import make_asgi_app

from counter.metrics import record_registration_metric, normalize_client_user_agent

from counter.config import HOST, PORT, STATIC_DIR, GIT_HASH
from counter.db import init_db
from counter.api import router as api_router
from counter.privacy import router as privacy_router


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """Startup and shutdown lifecycle events."""
    await init_db()
    yield


app = FastAPI(
    title="JSKW Counter",
    description="Real-time WebSockets, Server-Sent Events (SSE), and REST API.",
    version="1.0.0",
    lifespan=lifespan,
)

# Support reverse proxy headers for custom domains, SSL termination, and client IP
app.add_middleware(ProxyHeadersMiddleware, trusted_hosts="*")

# Enable CORS for browser access from staging and production domains
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def proxy_prefix_middleware(request: Request, call_next) -> Response:
    """Handle X-Forwarded-Prefix for custom deployment subpaths (staging/prod)."""
    prefix = request.headers.get("x-forwarded-prefix")
    if prefix:
        request.scope["root_path"] = prefix.rstrip("/")
    return await call_next(request)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    if request.url.path == "/api/counters/register":
        user_agent = request.headers.get("user-agent")
        client_version = request.headers.get("x-client-version") or request.headers.get("x-app-version")
        client_id = normalize_client_user_agent(user_agent, client_version)
        record_registration_metric(key_format="legacy", client=client_id, status="error")
    return await request_validation_exception_handler(request, exc)


# Mount API router
app.include_router(api_router)

# Prometheus metrics endpoint (/metrics) - internal only, blocked on public ingress
from prometheus_client import generate_latest, CONTENT_TYPE_LATEST
from fastapi import HTTPException

BLOCKED_METRICS_HOST_DOMAINS = (
    "jskw.dev",
    "jskw.ca",
    "jsteelkw.dev",
    "jsteelkw.ca",
    "jsteel.dev",
    "jsteel.ca",
)


@app.get("/metrics", include_in_schema=False)
@app.get("/metrics/", include_in_schema=False)
async def metrics_endpoint(request: Request) -> Response:
    """Internal metrics endpoint. Blocked on public ingress."""
    forwarded_host = request.headers.get("x-forwarded-host", "")
    host = request.headers.get("host", "")

    # Reject requests arriving via external ingress or public domain names
    if forwarded_host or any(domain in host for domain in BLOCKED_METRICS_HOST_DOMAINS):
        raise HTTPException(status_code=404, detail="Not Found")

    return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)

# Mount static assets directory (web/ folder)
STATIC_DIR.mkdir(parents=True, exist_ok=True)
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")
app.mount("/web", StaticFiles(directory=str(STATIC_DIR)), name="web")


@app.get("/", include_in_schema=False)
async def serve_index() -> FileResponse:
    index_file = STATIC_DIR / "index.html"
    return FileResponse(str(index_file))


# Mount Privacy Policy router (versioning and diff comparison)
app.include_router(privacy_router)





@app.get("/style.css", include_in_schema=False)
async def serve_css() -> FileResponse:
    return FileResponse(str(STATIC_DIR / "style.css"))


@app.get("/app.js", include_in_schema=False)
async def serve_js() -> FileResponse:
    return FileResponse(str(STATIC_DIR / "app.js"))


@app.get("/health", tags=["system"])
async def health_check() -> dict[str, str]:
    return {"status": "ok", "service": "counter-server", "git_hash": GIT_HASH}


def run() -> None:
    uvicorn.run(
        "counter.main:app",
        host=HOST,
        port=PORT,
        reload=False,
        proxy_headers=True,
        forwarded_allow_ips="*",
    )



if __name__ == "__main__":
    run()
