# Tracebox schema source

`events.json` is the only authoring format. It is strict JSON so every event and
field declares a stable numeric ID, privacy class, semantic type, encoded bound,
category, retention, transformation, visibility, Direct Boot eligibility, and
append-only evolution policy. The compiler rejects unknown keys, unbounded or
Prohibited semantic types, ID reuse, and non-C0 Direct Boot fields.

Run `python tooling\schema-compiler\compile_schema.py` to update every generated
consumer. Generated surfaces are the only recording contracts; there is no
generic event, map, label, object, or text collection construct.
