# Tracebox schema source

`events.json` is the only authoring format. It is strict JSON so every event and
field declares a stable numeric ID, privacy class, semantic type, encoded bound,
category, retention, transformation, visibility, Direct Boot eligibility, and
append-only evolution policy. The compiler rejects unknown keys, unbounded or
Prohibited semantic types, ID reuse, and non-C0 Direct Boot fields.

Run `python tooling\schema-compiler\compile_schema.py` to update every generated
consumer. Generated surfaces are the only recording contracts; there is no
generic event, map, label, object, or text collection construct.

The generated consumers include the offline Rust decoder and the shared schema
fingerprint embedded in `.tbdiag` manifests. A reader must match that fingerprint
before interpreting record payloads.

`compatibility/v1.json` freezes the released event prefix as canonical hashes.
The compiler requires every released event and field contract to remain exact;
v1 can grow only by appending a new event with a fresh ID. Reserved IDs cannot be
removed. A future incompatible schema version requires an explicit, reviewed
compatibility-baseline migration.
