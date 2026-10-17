import asyncio
import json
import logging
from typing import AsyncGenerator
from fastapi import APIRouter, WebSocket, WebSocketDisconnect, HTTPException, Query, Body, status
from fastapi.responses import StreamingResponse

from counter.models import (
    RegisterRequest,
    RegisterResponse,
    CounterInfoResponse,
    ActionRequest,
    ActionResponse,
    EventListResponse,
    ROLE_PERMISSIONS,
)
from counter.service import (
    register_async,
    get_counter_info_by_token,
    mutate_counter,
    get_recent_events_by_token,
    InvalidTokenError,
    PermissionDeniedError,
    DuplicateTokenError,
)
from counter.db import resolve_token, get_counter_events
from counter.hub import hub

logger = logging.getLogger("counter.api")
router = APIRouter()


@router.post("/api/counters/register", response_model=RegisterResponse, status_code=status.HTTP_201_CREATED)
async def api_register_counter(payload: RegisterRequest) -> RegisterResponse:
    """Registers a new counter room with 2 or 3 UUIDs.
    Can accept:
      - readOnlyUUID, readWriteUUID, adminUUID (3 UUIDs)
      - readOnlyUUID, adminUUID (2 UUIDs)
    """
    try:
        result = await register_async(
            read_only_uuid=payload.readOnlyUUID,
            second_uuid=payload.readWriteUUID if payload.readWriteUUID else payload.adminUUID,
            third_uuid=payload.adminUUID if payload.readWriteUUID else None,
            name=payload.name,
            initial_value=payload.initial_value,
        )
        return RegisterResponse(**result)
    except DuplicateTokenError as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))
    except ValueError as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))


@router.get("/api/counters/{token}", response_model=CounterInfoResponse)
async def api_get_counter_info(token: str) -> CounterInfoResponse:
    """Fetches counter state and user's role/permissions."""
    try:
        info = await get_counter_info_by_token(token)
        return CounterInfoResponse(**info)
    except InvalidTokenError as e:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(e))


@router.post("/api/counters/{token}/increment", response_model=ActionResponse)
async def api_increment_counter(token: str, req: ActionRequest = Body(default_factory=ActionRequest)) -> ActionResponse:
    """Increments the counter. Forbidden for read_only role."""
    try:
        res = await mutate_counter(token=token, action="increment", delta=req.delta)
        return ActionResponse(**res)
    except InvalidTokenError as e:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(e))
    except PermissionDeniedError as e:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=str(e))


@router.post("/api/counters/{token}/decrement", response_model=ActionResponse)
async def api_decrement_counter(token: str, req: ActionRequest = Body(default_factory=ActionRequest)) -> ActionResponse:
    """Decrements the counter. Forbidden for read_only role."""
    try:
        res = await mutate_counter(token=token, action="decrement", delta=req.delta)
        return ActionResponse(**res)
    except InvalidTokenError as e:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(e))
    except PermissionDeniedError as e:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=str(e))


@router.post("/api/counters/{token}/reset", response_model=ActionResponse)
async def api_reset_counter(token: str, req: ActionRequest = Body(default_factory=ActionRequest)) -> ActionResponse:
    """Resets the counter. Admin role only."""
    try:
        res = await mutate_counter(token=token, action="reset", target_value=req.value)
        return ActionResponse(**res)
    except InvalidTokenError as e:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(e))
    except PermissionDeniedError as e:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=str(e))


@router.get("/api/counters/{token}/events", response_model=EventListResponse)
async def api_get_events(token: str, limit: int = Query(default=50, ge=1, le=200)) -> EventListResponse:
    """Gets recent event stream history for the counter."""
    try:
        res = await get_recent_events_by_token(token, limit=limit)
        return EventListResponse(**res)
    except InvalidTokenError as e:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(e))


# =========================================================================
# Server-Sent Events (SSE)
# =========================================================================

async def sse_event_generator(token: str) -> AsyncGenerator[str, None]:
    """Generates SSE stream for a subscriber."""
    resolved = await resolve_token(token)
    if not resolved:
        error_payload = json.dumps({"error": "Unauthorized: invalid token/UUID"})
        yield f"event: error\ndata: {error_payload}\n\n"
        return

    counter, role = resolved
    counter_id = counter["id"]
    queue = await hub.register_sse(counter_id)

    try:
        # 1. Send initial connection payload with access role and current value
        recent_events = await get_counter_events(counter_id, limit=20)
        initial_msg = {
            "type": "connected",
            "protocol": "sse",
            "counter_id": counter_id,
            "counter_name": counter["name"],
            "access": role,
            "permissions": ROLE_PERMISSIONS[role],
            "value": counter["value"],
            "recent_events": recent_events,
        }
        yield f"event: connected\ndata: {json.dumps(initial_msg)}\n\n"

        # 2. Stream subsequent update events and heartbeat pings
        while True:
            try:
                # Wait for next event with a 15-second timeout for keepalive
                event_data = await asyncio.wait_for(queue.get(), timeout=15.0)
                yield f"event: update\ndata: {json.dumps(event_data)}\n\n"
            except asyncio.TimeoutError:
                # Send SSE keep-alive comment
                yield ": keepalive\n\n"
    except asyncio.CancelledError:
        logger.info("SSE client cancelled stream for counter %s", counter_id)
    finally:
        await hub.unregister_sse(counter_id, queue)


async def _sse_stream_response(effective_token: str | None) -> StreamingResponse:
    if not effective_token:
        raise HTTPException(status_code=400, detail="Missing access token/UUID.")

    resolved = await resolve_token(effective_token)
    if not resolved:
        raise HTTPException(status_code=401, detail="Unauthorized: invalid token/UUID.")

    return StreamingResponse(
        sse_event_generator(effective_token),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@router.get("/sse/{token}")
async def sse_endpoint_path(token: str) -> StreamingResponse:
    """Server-Sent Events (SSE) streaming endpoint via path parameter."""
    return await _sse_stream_response(token)


@router.get("/sse")
async def sse_endpoint_query(token: str = Query(...)) -> StreamingResponse:
    """Server-Sent Events (SSE) streaming endpoint via query parameter."""
    return await _sse_stream_response(token)


# =========================================================================
# WebSockets
# =========================================================================

async def _handle_websocket(websocket: WebSocket, effective_token: str | None) -> None:
    await websocket.accept()

    if not effective_token:
        await websocket.send_text(json.dumps({
            "type": "error",
            "code": "missing_token",
            "message": "Missing access token/UUID parameter."
        }))
        await websocket.close(code=4401)
        return

    # Authenticate via SHA-256 hash lookup in SQLite
    resolved = await resolve_token(effective_token)
    if not resolved:
        await websocket.send_text(json.dumps({
            "type": "error",
            "code": "invalid_token",
            "message": "Unauthorized: provided UUID token does not match any registered counter."
        }))
        await websocket.close(code=4401)
        return

    counter, role = resolved
    counter_id = counter["id"]
    await hub.register_ws(counter_id, websocket)

    try:
        # Send initial connected greeting with user's access role and current value
        recent_events = await get_counter_events(counter_id, limit=20)
        await websocket.send_text(json.dumps({
            "type": "connected",
            "protocol": "websocket",
            "counter_id": counter_id,
            "counter_name": counter["name"],
            "access": role,
            "permissions": ROLE_PERMISSIONS[role],
            "value": counter["value"],
            "recent_events": recent_events,
        }))

        # Message loop
        while True:
            raw_text = await websocket.receive_text()
            try:
                msg = json.loads(raw_text)
            except json.JSONDecodeError:
                await websocket.send_text(json.dumps({
                    "type": "error",
                    "code": "invalid_json",
                    "message": "Payload must be valid JSON object.",
                }))
                continue

            action = msg.get("action")
            if not action:
                await websocket.send_text(json.dumps({
                    "type": "error",
                    "code": "missing_action",
                    "message": "Payload must specify an 'action' field.",
                }))
                continue

            if action == "ping":
                await websocket.send_text(json.dumps({"type": "pong"}))
                continue

            if action == "get_status":
                info = await get_counter_info_by_token(effective_token)
                await websocket.send_text(json.dumps({"type": "status", **info}))
                continue

            delta = msg.get("delta", 1)
            target_value = msg.get("value", 0)

            try:
                result = await mutate_counter(
                    token=effective_token,
                    action=action,
                    delta=delta,
                    target_value=target_value,
                )
                # Confirm back to this socket as well (it also receives the broadcast)
                await websocket.send_text(json.dumps({
                    "type": "action_ack",
                    "status": "success",
                    "action": action,
                    "value": result["value"],
                    "access": role,
                }))
            except PermissionDeniedError as e:
                await websocket.send_text(json.dumps({
                    "type": "error",
                    "code": "permission_denied",
                    "access": role,
                    "message": str(e),
                }))
            except ValueError as e:
                await websocket.send_text(json.dumps({
                    "type": "error",
                    "code": "bad_request",
                    "message": str(e),
                }))

    except WebSocketDisconnect:
        logger.info("WebSocket disconnected for counter %s", counter_id)
    finally:
        await hub.unregister_ws(counter_id, websocket)


@router.websocket("/ws/{token}")
async def websocket_endpoint_path(websocket: WebSocket, token: str) -> None:
    await _handle_websocket(websocket, token)


@router.websocket("/ws")
async def websocket_endpoint_query(websocket: WebSocket, token: str | None = None) -> None:
    effective = token or websocket.query_params.get("token")
    await _handle_websocket(websocket, effective)
