from typing import Any
from pydantic import BaseModel, Field, model_validator


ROLE_PERMISSIONS = {
    "read_only": {
        "can_read": True,
        "can_write": False,
        "can_admin": False,
    },
    "read_write": {
        "can_read": True,
        "can_write": True,
        "can_admin": False,
    },
    "admin": {
        "can_read": True,
        "can_write": True,
        "can_admin": True,
    },
}


class RegisterRequest(BaseModel):
    # Support camelCase and snake_case
    readOnlyUUID: str | None = Field(default=None, alias="read_only_uuid")
    readWriteUUID: str | None = Field(default=None, alias="read_write_uuid")
    adminUUID: str | None = Field(default=None, alias="admin_uuid")
    name: str = Field(default="Counter Room")
    initial_value: int = Field(default=0)

    model_config = {
        "populate_by_name": True,
    }

    @model_validator(mode="before")
    @classmethod
    def handle_field_aliases(cls, data: Any) -> Any:
        if isinstance(data, dict):
            # Map alternate spellings if provided
            if "readOnlyUUID" not in data and "read_only_uuid" in data:
                data["readOnlyUUID"] = data["read_only_uuid"]
            if "readWriteUUID" not in data and "read_write_uuid" in data:
                data["readWriteUUID"] = data["read_write_uuid"]
            if "adminUUID" not in data and "admin_uuid" in data:
                data["adminUUID"] = data["admin_uuid"]
        return data


class RegisterResponse(BaseModel):
    counter_id: str
    name: str
    value: int
    roles: dict[str, str]
    token_hashes: dict[str, str]
    message: str


class CounterInfoResponse(BaseModel):
    counter_id: str
    name: str
    value: int
    access: str
    permissions: dict[str, bool]
    created_at: str
    updated_at: str


class ActionRequest(BaseModel):
    delta: int = Field(default=1, description="Amount to increment or decrement")
    value: int = Field(default=0, description="Target value for reset action")


class ActionResponse(BaseModel):
    counter_id: str
    access: str
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
    timestamp: str


class EventListResponse(BaseModel):
    counter_id: str
    access: str
    events: list[EventItem]
