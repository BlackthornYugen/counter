# syntax=docker/dockerfile:1

# Stage 1: Install frontend JS dependencies
FROM node:22-slim AS frontend
WORKDIR /app/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --omit=dev

# Stage 2: Final application image
FROM python:3.13-slim

WORKDIR /app

# Install Python dependencies
COPY server/pyproject.toml /app/server/
WORKDIR /app/server
RUN pip install --no-cache-dir .

# Copy application source and frontend web assets
WORKDIR /app
COPY server /app/server
COPY web /app/web
COPY --from=frontend /app/web/node_modules /app/web/node_modules

# Create persistent storage directory for SQLite database
RUN mkdir -p /data

ARG GIT_HASH="dev"
ENV GIT_HASH=${GIT_HASH} \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/server \
    COUNTER_HOST=0.0.0.0 \
    COUNTER_PORT=8000 \
    COUNTER_STATIC_DIR=/app/web \
    COUNTER_DB_PATH=/data/counter.db

EXPOSE 8000

CMD ["uvicorn", "counter.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips=*"]
