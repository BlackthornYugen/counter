import asyncio
import json
import logging
from typing import Any
from fastapi import WebSocket

logger = logging.getLogger("counter.hub")


class CounterHub:
    """Manages active WebSockets and Server-Sent Event (SSE) subscribers
    for each counter room.
    """

    def __init__(self) -> None:
        # counter_id -> set of WebSocket instances
        self._ws_subscribers: dict[str, set[WebSocket]] = {}
        # counter_id -> set of asyncio.Queue instances for SSE
        self._sse_subscribers: dict[str, set[asyncio.Queue[dict[str, Any]]]] = {}
        self._lock = asyncio.Lock()

    async def register_ws(self, counter_id: str, websocket: WebSocket) -> None:
        async with self._lock:
            if counter_id not in self._ws_subscribers:
                self._ws_subscribers[counter_id] = set()
            self._ws_subscribers[counter_id].add(websocket)
            logger.info("WS connected to counter %s (total: %d)", counter_id, len(self._ws_subscribers[counter_id]))

    async def unregister_ws(self, counter_id: str, websocket: WebSocket) -> None:
        async with self._lock:
            if counter_id in self._ws_subscribers:
                self._ws_subscribers[counter_id].discard(websocket)
                if not self._ws_subscribers[counter_id]:
                    del self._ws_subscribers[counter_id]
            logger.info("WS disconnected from counter %s", counter_id)

    async def register_sse(self, counter_id: str) -> asyncio.Queue[dict[str, Any]]:
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=100)
        async with self._lock:
            if counter_id not in self._sse_subscribers:
                self._sse_subscribers[counter_id] = set()
            self._sse_subscribers[counter_id].add(queue)
            logger.info("SSE client connected to counter %s (total: %d)", counter_id, len(self._sse_subscribers[counter_id]))
        return queue

    async def unregister_sse(self, counter_id: str, queue: asyncio.Queue[dict[str, Any]]) -> None:
        async with self._lock:
            if counter_id in self._sse_subscribers:
                self._sse_subscribers[counter_id].discard(queue)
                if not self._sse_subscribers[counter_id]:
                    del self._sse_subscribers[counter_id]
            logger.info("SSE client disconnected from counter %s", counter_id)

    async def broadcast(self, counter_id: str, event_data: dict[str, Any]) -> None:
        """Broadcasts an event to all connected WebSockets and SSE queues for counter_id."""
        json_payload = json.dumps(event_data)

        # Copy current subscribers snapshot
        async with self._lock:
            ws_targets = list(self._ws_subscribers.get(counter_id, []))
            sse_targets = list(self._sse_subscribers.get(counter_id, []))

        # Send to WebSockets
        dead_ws = []
        for ws in ws_targets:
            try:
                await ws.send_text(json_payload)
            except Exception as e:
                logger.debug("Failed sending to WS: %s", e)
                dead_ws.append(ws)

        # Cleanup dead WebSockets
        if dead_ws:
            async with self._lock:
                for ws in dead_ws:
                    if counter_id in self._ws_subscribers:
                        self._ws_subscribers[counter_id].discard(ws)

        # Send to SSE queues
        for queue in sse_targets:
            try:
                queue.put_nowait(event_data)
            except asyncio.QueueFull:
                logger.warning("SSE queue full for counter %s; dropping event", counter_id)


hub = CounterHub()
