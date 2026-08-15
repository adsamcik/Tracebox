"""Authoritative, strict model for Tracebox's bounded event schema."""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any

PRIVACY = frozenset(("C0", "C1", "C2"))
SEMANTIC_TYPES = frozenset(
    ("u16", "u32", "u64", "i32", "enum", "fixed32", "bounded_utf8")
)


class SchemaError(ValueError):
    """Raised when a schema could admit data outside the privacy contract."""


def validate_compatibility(
    document: dict[str, Any],
    released_baseline: dict[str, Any],
) -> None:
    """Require the released event prefix to remain byte-contract equivalent.

    V1 can grow only by appending a new event. Existing event metadata and fields are frozen
    together because their generated Kotlin, C, Rust, protobuf, storage, and package record
    representations form one ABI. A future schema-version migration requires a new reviewed
    compatibility-baseline format rather than silently weakening this check.
    """
    expected_baseline_fields = {
        "contract",
        "schema_version",
        "reserved_event_ids",
        "event_prefix",
    }
    if set(released_baseline) != expected_baseline_fields:
        raise SchemaError("released compatibility baseline has unknown or missing fields")
    if released_baseline["contract"] != "tracebox-schema-compatibility-v1":
        raise SchemaError("unsupported schema compatibility baseline")
    if document.get("schema_version") != released_baseline["schema_version"]:
        raise SchemaError("schema version changes require an explicit compatibility-baseline migration")

    baseline_reserved = released_baseline["reserved_event_ids"]
    current_reserved = document.get("reserved_event_ids")
    if not isinstance(baseline_reserved, list) or not isinstance(current_reserved, list):
        raise SchemaError("compatibility reserved event IDs must be lists")
    if not set(baseline_reserved).issubset(current_reserved):
        raise SchemaError("released reserved event IDs cannot be removed")

    event_prefix = released_baseline["event_prefix"]
    current_events = document.get("events")
    if not isinstance(event_prefix, list) or not isinstance(current_events, list):
        raise SchemaError("compatibility event prefixes must be lists")
    if len(current_events) < len(event_prefix):
        raise SchemaError("released events cannot be removed")
    for index, locked in enumerate(event_prefix):
        if set(locked) != {"id", "sha256"}:
            raise SchemaError("released event lock has unknown or missing fields")
        current = current_events[index]
        if current.get("id") != locked["id"]:
            raise SchemaError(f"released event prefix changed at position {index}")
        canonical = json.dumps(
            current,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        if hashlib.sha256(canonical).hexdigest() != locked["sha256"]:
            raise SchemaError(f"released event {locked['id']} changed incompatibly")


@dataclass(frozen=True)
class Field:
    id: int
    name: str
    privacy: str
    semantic_type: str
    max_encoded_size: int
    transform: str


@dataclass(frozen=True)
class Event:
    id: int
    name: str
    category: str
    retention: str
    package_visibility: str
    direct_boot_eligible: bool
    fields: tuple[Field, ...]


@dataclass(frozen=True)
class Schema:
    version: int
    events: tuple[Event, ...]
    fingerprint_source: str


def _positive_id(value: Any, label: str) -> int:
    if not isinstance(value, int) or value <= 0:
        raise SchemaError(f"{label} must be a positive integer")
    return value


def parse_schema(document: dict[str, Any], fingerprint_source: str) -> Schema:
    """Validate and convert the only accepted schema source format.

    Unknown fields are rejected rather than inferred as a permissive privacy class.
    Prohibited types deliberately are not part of ``SEMANTIC_TYPES``.
    """
    expected = {"schema_version", "evolution", "reserved_event_ids", "events"}
    if set(document) != expected:
        raise SchemaError("schema has unknown or missing top-level fields")
    evolution = document["evolution"]
    if evolution != {
        "policy": "append_only",
        "ids_never_reused": True,
        "unknown_fields": "reject",
        "incompatible_change": "new_record_or_abi_version",
    }:
        raise SchemaError("schema evolution policy is not the frozen append-only policy")
    reserved = document["reserved_event_ids"]
    if not isinstance(reserved, list):
        raise SchemaError("reserved_event_ids must be a list")
    seen_events = {_positive_id(value, "reserved event id") for value in reserved}
    events: list[Event] = []
    for raw_event in document["events"]:
        allowed_event = {
            "id", "name", "category", "retention", "package_visibility",
            "direct_boot_eligible", "fields",
        }
        if set(raw_event) != allowed_event:
            raise SchemaError("event has unknown or missing metadata")
        event_id = _positive_id(raw_event["id"], "event id")
        if event_id in seen_events:
            raise SchemaError(f"reused event id {event_id}")
        seen_events.add(event_id)
        fields: list[Field] = []
        seen_fields: set[int] = set()
        for raw_field in raw_event["fields"]:
            allowed_field = {
                "id", "name", "privacy", "semantic_type", "max_encoded_size", "transform",
            }
            if set(raw_field) != allowed_field:
                raise SchemaError("unknown custom field rejected")
            field_id = _positive_id(raw_field["id"], "field id")
            if field_id in seen_fields:
                raise SchemaError(f"reused field id {event_id}.{field_id}")
            seen_fields.add(field_id)
            privacy = raw_field["privacy"]
            semantic_type = raw_field["semantic_type"]
            maximum = raw_field["max_encoded_size"]
            if privacy not in PRIVACY:
                raise SchemaError("Prohibited or unknown privacy class has no collection API")
            if semantic_type not in SEMANTIC_TYPES:
                raise SchemaError("Prohibited or unbounded semantic type has no collection API")
            if not isinstance(maximum, int) or not 0 < maximum <= 16_384:
                raise SchemaError("field requires an explicit bounded encoded size")
            if raw_event["direct_boot_eligible"] and privacy != "C0":
                raise SchemaError("Direct Boot events may contain C0 fields only")
            fields.append(Field(field_id, raw_field["name"], privacy, semantic_type, maximum, raw_field["transform"]))
        if not fields:
            raise SchemaError("events require at least one explicitly bounded field")
        events.append(Event(
            event_id, raw_event["name"], raw_event["category"], raw_event["retention"],
            raw_event["package_visibility"], raw_event["direct_boot_eligible"], tuple(fields),
        ))
    return Schema(_positive_id(document["schema_version"], "schema version"), tuple(events), fingerprint_source)
