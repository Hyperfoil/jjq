# JSON-Schema-Test-Suite snapshot (issue #167 acceptance)

Vendored from https://github.com/json-schema-org/JSON-Schema-Test-Suite
at commit `55719286c33ae0d1823bfb65fea10338a19bc37c` (2026-10-13, BSD-3-Clause,
see LICENSE): `tests/draft7` and `tests/draft2020-12` only.

Run report-only via `JsonSchemaConformanceTest` (same culture as the
jq/jsonpath suites): mismatches abort as skips with reasons, and the summary
prints passed/unsupported/mismatch counts plus conformance-%. Supported
behavior stays pinned by ordinary assertions in `JqSchemaTest`, which fail
the build on divergence.
