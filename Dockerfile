# syntax=docker/dockerfile:1
FROM python:3.13-slim

WORKDIR /app

# Install dependencies using pip
COPY server/pyproject.toml /app/server/
WORKDIR /app/server
RUN pip install --no-cache-dir .

# Copy application source and frontend web assets
COPY server /app/server
COPY web /app/web

# Create persistent storage directory for SQLite database
RUN mkdir -p /data

ENV PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/server \
    COUNTER_HOST=0.0.0.0 \
    COUNTER_PORT=8000 \
    COUNTER_STATIC_DIR=/app/web \
    COUNTER_DB_PATH=/data/counter.db

EXPOSE 8000

CMD ["uvicorn", "counter.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips=*"]
