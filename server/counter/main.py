from contextlib import asynccontextmanager
from typing import AsyncGenerator
import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from counter.config import HOST, PORT, STATIC_DIR
from counter.db import init_db
from counter.api import router as api_router


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """Startup and shutdown lifecycle events."""
    await init_db()
    yield


app = FastAPI(
    title="Counter Room Server",
    description="WebSockets, Server-Sent Events (SSE), and REST API with hashed UUID role-based access control.",
    version="1.0.0",
    lifespan=lifespan,
)

# Enable CORS for browser access
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Mount API router
app.include_router(api_router)

# Mount static assets directory (web/ folder)
STATIC_DIR.mkdir(parents=True, exist_ok=True)
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")
app.mount("/web", StaticFiles(directory=str(STATIC_DIR)), name="web")


@app.get("/", include_in_schema=False)
async def serve_index() -> FileResponse:
    index_file = STATIC_DIR / "index.html"
    return FileResponse(str(index_file))


@app.get("/privacy", include_in_schema=False)
@app.get("/privacy/", include_in_schema=False)
async def serve_privacy() -> FileResponse:
    return FileResponse(str(STATIC_DIR / "privacy" / "index.html"))





@app.get("/style.css", include_in_schema=False)
async def serve_css() -> FileResponse:
    return FileResponse(str(STATIC_DIR / "style.css"))


@app.get("/app.js", include_in_schema=False)
async def serve_js() -> FileResponse:
    return FileResponse(str(STATIC_DIR / "app.js"))


@app.get("/health", tags=["system"])
async def health_check() -> dict[str, str]:
    return {"status": "ok", "service": "counter-server"}


def run() -> None:
    uvicorn.run("counter.main:app", host=HOST, port=PORT, reload=False)


if __name__ == "__main__":
    run()
