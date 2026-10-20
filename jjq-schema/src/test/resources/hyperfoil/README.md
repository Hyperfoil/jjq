# Hyperfoil fixture schemas (issue #167 acceptance)

Vendored from the Hyperfoil repository at commit
`e1b42fd736352402183806d09aad14cb0a1c825a` (Apache-2.0, same organization):

- `schema.json` — `distribution/src/main/resources/schema.json` (20,123 bytes,
  draft-07, 60 local `$ref`s, needs `minProperties`/`maxProperties`,
  `additionalItems` alongside schema-form `items`).
- `run-schema.json` — `distribution/src/main/resources/run-schema.json`
  (8,884 bytes, draft-07, 11 local `$ref`s).

These must compile unchanged and validate known-valid instances once the
corresponding keywords land. Merely compiling a schema is not a
document-validation test: every fixture needs valid instances plus
mutations expected to fail.
