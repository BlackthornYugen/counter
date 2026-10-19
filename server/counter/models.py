from typing import Any
import uuid
from pydantic import BaseModel, Field, model_validator, field_validator, PrivateAttr
from counter.metrics import classify_registration_payload


ROLE_PERMISSIONS = {
    "viewer": {
        "can_read": True,
        "can_write": False,
        "can_delete": False,
        "can_own": False,
        "can_admin": False,
    },
    "editor": {
        "can_read": True,
        "can_write": True,
        "can_delete": False,
        "can_own": False,
        "can_admin": False,
    },
    "owner": {
        "can_read": True,
        "can_write": True,
        "can_delete": True,
        "can_own": True,
        "can_admin": True,
    },
}

# Backward compatibility for legacy role lookups
ROLE_PERMISSIONS["read_only"] = ROLE_PERMISSIONS["viewer"]
ROLE_PERMISSIONS["read_write"] = ROLE_PERMISSIONS["editor"]
ROLE_PERMISSIONS["admin"] = ROLE_PERMISSIONS["owner"]


class RegisterRequest(BaseModel):
    viewer: str | None = None
    editor: str | None = None
    owner: str | None = None
    initial_value: int = Field(default=0)

    _key_format: str = PrivateAttr(default="legacy")

    model_config = {
        "populate_by_name": True,
    }

    @property
    def readOnlyUUID(self) -> str | None:
        return self.viewer

    @property
    def readWriteUUID(self) -> str | None:
        return self.editor

    @property
    def adminUUID(self) -> str | None:
        return self.owner

    @model_validator(mode="wrap")
    @classmethod
    def handle_field_aliases(cls, data: Any, handler: Any) -> Any:
        detected_format = "legacy"
        if isinstance(data, dict):
            # Detect key format for Prometheus metrics
            detected_format = classify_registration_payload(data)
            data = dict(data)

            # Map legacy aliases to new target fields
            # viewer: viewer, readOnlyUUID, read_only_uuid, read_only
            if "viewer" not in data or data["viewer"] is None:
                for k in ("readOnlyUUID", "read_only_uuid", "read_only"):
                    if k in data and data[k] is not None:
                        data["viewer"] = data[k]
                        break

            # editor: editor, readWriteUUID, read_write_uuid, read_write
            if "editor" not in data or data["editor"] is None:
                for k in ("readWriteUUID", "read_write_uuid", "read_write"):
                    if k in data and data[k] is not None:
                        data["editor"] = data[k]
                        break

            # owner: owner, adminUUID, admin_uuid, admin
            if "owner" not in data or data["owner"] is None:
                for k in ("adminUUID", "admin_uuid", "admin"):
                    if k in data and data[k] is not None:
                        data["owner"] = data[k]
                        break

        instance = handler(data)
        instance._key_format = detected_format
        return instance

    @field_validator("viewer", "editor", "owner")
    @classmethod
    def validate_uuid_v4(cls, v: str | None, info: Any) -> str | None:
        if v is None:
            return None
        cleaned = str(v).strip()
        if not cleaned:
            return None
        try:
            parsed = uuid.UUID(cleaned)
        except (ValueError, TypeError, AttributeError):
            raise ValueError(f"'{info.field_name}' must be formatted as a valid UUIDv4")
        if parsed.version != 4:
            raise ValueError(f"'{info.field_name}' must be a UUID version 4, got version {parsed.version or 'unknown'}")
        return str(parsed)


class RegisterResponse(BaseModel):
    counter_id: str
    value: int
    roles: dict[str, str]
    token_hashes: dict[str, str]
    message: str
    access: str | None = None
    access_legacy: str | None = None


class CounterInfoResponse(BaseModel):
    counter_id: str
    value: int
    access: str
    access_legacy: str | None = None
    permissions: dict[str, bool]
    created_at: str
    updated_at: str


class ActionRequest(BaseModel):
    delta: int = Field(default=1, description="Amount to increment or decrement")


class DeleteResponse(BaseModel):
    counter_id: str
    deleted: bool
    message: str


class ActionResponse(BaseModel):
    counter_id: str
    access: str
    access_legacy: str | None = None
    action: str
    delta: int | None
    value: int
    timestamp: str


class EventItem(BaseModel):
    id: int
    counter_id: str
    action: str
    delta: int | None
    value: int
    role: str
    role_legacy: str | None = None
    timestamp: str


class EventListResponse(BaseModel):
    counter_id: str
    access: str
    access_legacy: str | None = None
    events: list[EventItem]
