import asyncio
import uuid
from typing import Any
from counter.db import (
    hash_token,
    init_db,
    check_tokens_exist,
    insert_counter,
    resolve_token,
    update_counter_value,
    get_counter_by_id,
    get_counter_events,
    delete_counter_by_id,
)
from counter.models import ROLE_PERMISSIONS
from counter.hub import hub
from counter import config


class CounterServiceError(Exception):
    """Base exception for counter service."""
    pass


class InvalidTokenError(CounterServiceError):
    """Raised when an provided token/UUID does not match any registered counter."""
    pass


class PermissionDeniedError(CounterServiceError):
    """Raised when an authenticated role does not have permission for an action."""
    pass


class DuplicateTokenError(CounterServiceError):
    """Raised when registering identical UUIDs for multiple roles within the same request."""
    pass


class TokenAlreadyRegisteredError(CounterServiceError):
    """Raised when one or more UUIDs are already registered to an existing counter room."""
    pass


class InvalidUUIDError(CounterServiceError, ValueError):
    """Raised when a provided token is not formatted as a valid UUIDv4."""
    pass


def _validate_uuid_str(val: str, field_name: str) -> str:
    cleaned = str(val).strip()
    if not cleaned:
        raise InvalidUUIDError(f"'{field_name}' cannot be empty")
    try:
        parsed = uuid.UUID(cleaned)
    except (ValueError, TypeError, AttributeError):
        raise InvalidUUIDError(
            f"'{field_name}' must be formatted as a valid UUIDv4 (e.g. '{uuid.uuid4()}')"
        )

    if parsed.version != 4:
        raise InvalidUUIDError(
            f"'{field_name}' must be a UUID version 4 (e.g. '{uuid.uuid4()}'), got version {parsed.version or 'unknown'}"
        )

    return str(parsed)


async def register_async(
    viewer_uuid: str | None = None,
    second_uuid: str | None = None,
    third_uuid: str | None = None,
    *,
    initial_value: int = 0,
    db_path: str | None = None,
) -> dict[str, Any]:
    """Registers a new counter room with 2 or 3 UUIDs.

    Signature styles supported:
    1. register(viewer, editor, owner) or register(readOnlyUUID, readWriteUUID, adminUUID)
    2. register(viewer, owner) or register(readOnlyUUID, adminUUID)
    3. register() -> auto-generates all 3 UUIDs
    """
    await init_db(db_path)

    # Determine role assignment based on arguments passed
    if third_uuid is not None:
        # 3 UUIDs provided: (viewer, editor, owner)
        v_uuid = viewer_uuid
        e_uuid = second_uuid
        o_uuid = third_uuid
    elif second_uuid is not None:
        # 2 UUIDs provided: (viewer, owner)
        v_uuid = viewer_uuid
        e_uuid = None
        o_uuid = second_uuid
    else:
        # 0 or 1 provided: if 0, auto-generate
        v_uuid = viewer_uuid or str(uuid.uuid4())
        e_uuid = str(uuid.uuid4())
        o_uuid = str(uuid.uuid4())

    # If any specific role was None when 3 expected, allow auto-generation
    if v_uuid is None:
        v_uuid = str(uuid.uuid4())
    if o_uuid is None:
        o_uuid = str(uuid.uuid4())

    v_uuid = _validate_uuid_str(v_uuid, "viewer")
    o_uuid = _validate_uuid_str(o_uuid, "owner")
    if e_uuid is not None:
        e_uuid = _validate_uuid_str(e_uuid, "editor")

    # Check for uniqueness among provided tokens
    tokens_to_check = [v_uuid, o_uuid]
    if e_uuid is not None:
        tokens_to_check.append(e_uuid)

    normalized_tokens = [t.lower() for t in tokens_to_check]
    if len(normalized_tokens) != len(set(normalized_tokens)):
        raise DuplicateTokenError("All UUIDs assigned to a counter room must be distinct.")

    # Verify none of the provided UUIDs are already registered to an existing room
    already_registered = await check_tokens_exist(tokens_to_check, db_path=db_path)
    if already_registered:
        raise TokenAlreadyRegisteredError(
            f"UUID(s) already registered: {', '.join(already_registered)}. "
            "Cannot register the same UUID more than once. Connect directly using this UUID or generate new UUIDs."
        )

    token_role_map: dict[str, str] = {
        v_uuid: "viewer",
        o_uuid: "owner",
    }
    if e_uuid is not None:
        token_role_map[e_uuid] = "editor"

    counter_id = str(uuid.uuid4())
    await insert_counter(
        counter_id=counter_id,
        token_role_map=token_role_map,
        initial_value=initial_value,
        db_path=db_path,
    )

    roles_dict = {
        "viewer": v_uuid,
        "owner": o_uuid,
    }
    if e_uuid is not None:
        roles_dict["editor"] = e_uuid

    roles_dict = config.maybe_add_legacy_roles(roles_dict)

    token_hashes = {
        role: hash_token(token)
        for role, token in roles_dict.items()
    }

    result = {
        "counter_id": counter_id,
        "value": initial_value,
        "roles": roles_dict,
        "token_hashes": token_hashes,
        "message": f"Counter room created with {len(token_role_map)} access tokens.",
    }
    return result


def register(
    viewer_uuid: str | None = None,
    second_uuid: str | None = None,
    third_uuid: str | None = None,
    *,
    initial_value: int = 0,
    db_path: str | None = None,
) -> dict[str, Any]:
    """Synchronous interface for register().
    Can be called directly as:
      register(viewer, editor, owner)
      register(viewer, owner)
    """
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        loop = None

    coro = register_async(
        viewer_uuid=viewer_uuid,
        second_uuid=second_uuid,
        third_uuid=third_uuid,
        initial_value=initial_value,
        db_path=db_path,
    )

    if loop and loop.is_running():
        # If running inside an existing loop, create task or run in thread
        import concurrent.futures
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            return pool.submit(asyncio.run, coro).result()
    else:
        return asyncio.run(coro)


async def get_counter_info_by_token(token: str, db_path: str | None = None) -> dict[str, Any]:
    result = await resolve_token(token, db_path=db_path)
    if not result:
        raise InvalidTokenError("Provided token/UUID is invalid or does not exist.")

    counter, role = result
    permissions = ROLE_PERMISSIONS[role]
    resp = {
        "counter_id": counter["id"],
        "value": counter["value"],
        "access": role,
        "permissions": permissions,
        "created_at": counter["created_at"],
        "updated_at": counter["updated_at"],
    }
    return config.maybe_add_legacy_access(resp)


async def mutate_counter(
    token: str,
    action: str,
    delta: int | None = None,
    db_path: str | None = None,
) -> dict[str, Any]:
    """Validates token and role permissions, performs increment/decrement,
    updates database, and broadcasts event to WebSockets and SSE clients.
    """
    result = await resolve_token(token, db_path=db_path)
    if not result:
        raise InvalidTokenError("Provided token/UUID is invalid or does not exist.")

    counter, role = result
    permissions = ROLE_PERMISSIONS[role]

    if action in ("increment", "decrement"):
        if not permissions["can_write"]:
            raise PermissionDeniedError(
                f"Role '{role}' has read-only access and cannot perform '{action}'."
            )
        step = delta if delta is not None else 1
        new_val = counter["value"] + step if action == "increment" else counter["value"] - step
        actual_delta = step if action == "increment" else -step
    else:
        raise ValueError(f"Unknown counter action: '{action}'")

    # Update SQLite database
    event_data = await update_counter_value(
        counter_id=counter["id"],
        action=action,
        delta=actual_delta,
        new_value=new_val,
        role=role,
        db_path=db_path,
    )

    # Broadcast real-time update to all active WS and SSE clients
    broadcast_payload = {
        "type": "update",
        "counter_id": counter["id"],
        "action": action,
        "delta": actual_delta,
        "value": new_val,
        "role": role,
        "access": role,
        "timestamp": event_data["timestamp"],
    }
    if config.DUAL_ROLE_KEYS and role in config.LEGACY_ROLE_MAP:
        broadcast_payload["role_legacy"] = config.LEGACY_ROLE_MAP[role]
        broadcast_payload["access_legacy"] = config.LEGACY_ROLE_MAP[role]
    await hub.broadcast(counter["id"], broadcast_payload)

    res = {
        "counter_id": counter["id"],
        "access": role,
        "action": action,
        "delta": actual_delta,
        "value": new_val,
        "timestamp": event_data["timestamp"],
        "event_id": event_data["id"],
    }
    return config.maybe_add_legacy_access(res)


async def get_recent_events_by_token(
    token: str, limit: int = 50, db_path: str | None = None
) -> dict[str, Any]:
    result = await resolve_token(token, db_path=db_path)
    if not result:
        raise InvalidTokenError("Provided token/UUID is invalid or does not exist.")

    counter, role = result
    events = await get_counter_events(counter["id"], limit=limit, db_path=db_path)
    if config.DUAL_ROLE_KEYS:
        for ev in events:
            if "role" in ev and ev["role"] in config.LEGACY_ROLE_MAP:
                ev["role_legacy"] = config.LEGACY_ROLE_MAP[ev["role"]]
    resp = {
        "counter_id": counter["id"],
        "access": role,
        "events": events,
    }
    return config.maybe_add_legacy_access(resp)


async def delete_counter(token: str, db_path: str | None = None) -> dict[str, Any]:
    """Validates token and owner role permissions, deletes the counter from the database,
    broadcasts deletion event to subscribers, and closes room connections.
    """
    result = await resolve_token(token, db_path=db_path)
    if not result:
        raise InvalidTokenError("Provided token/UUID is invalid or does not exist.")

    counter, role = result
    permissions = ROLE_PERMISSIONS[role]
    if not permissions["can_delete"]:
        raise PermissionDeniedError(
            f"Role '{role}' cannot delete counter. Only 'owner' role can delete counter."
        )

    counter_id = counter["id"]
    deleted = await delete_counter_by_id(counter_id, db_path=db_path)

    # Broadcast deletion event and close all connections in hub
    await hub.broadcast(
        counter_id,
        {
            "type": "deleted",
            "counter_id": counter_id,
            "message": "Counter room has been permanently deleted by owner.",
        },
    )
    await hub.close_room(counter_id, reason="Counter room deleted by owner")

    return {
        "counter_id": counter_id,
        "deleted": deleted,
        "message": "Counter room successfully deleted.",
    }
