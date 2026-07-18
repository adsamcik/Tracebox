# Traceability Index

Baseline: `dc87c6f9e2a6576cc554f7cb181ce80a02bf0802`

`requirements.csv` is the source-to-implementation direction. Each row records source, locator, implementation path, evidence path, matrix, state, and only a prior satisfying commit SHA. `artifact-links.csv` is the reverse implementation-to-requirement index. Together they are the bidirectional, resumable traceability matrix.

Rows are conservative at bootstrap: no requirement is PASS merely because it is documented. Later commits update implementation/evidence paths and status only after verification, and may cite only an earlier commit.

Coverage includes normative bullets, numbered requirements, normative table rows, explicit must/never/prohibited statements from the assignment, architecture, ADR-0001, implementation plan, plus one explicit row for every work package.
