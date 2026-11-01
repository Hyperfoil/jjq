# Coming from Jackson / JSON-B to jjq-quarkus

A migration checklist in dependency order, mined from a completed
cutover. Each item: symptom if skipped, fix, how to verify. Every
behavior below is verified against the current tree; test references
are the executable version of each claim.

## 1. Jackson capability conflict

**Symptom if skipped:** build fails fast as soon as `quarkus-jackson`
is anywhere on the classpath — frequently transitively (e.g. via Roq).
The failure is loud, but finding the carrier is the actual work.

**Fix:** never both extensions at once. Find the carrier with
`mvn dependency:tree | grep -i jackson`, then either remove
`quarkus-jackson` (including `quarkus-rest-jackson` /
`quarkus-resteasy-jackson`, which depend on it) or drop `jjq-quarkus`.
Profile-split when one module genuinely needs Jackson.

**Verify:** the build error names both extensions and the remedy
(`JjqProcessor.rejectJacksonExtension`). If the build passes, there is
no Jackson on the classpath.

## 2. Annotation processor discovery

**Symptom if skipped:** an explicit `annotationProcessorPaths` block
(aesh, jmh, mapstruct, …) silently disables `jjq-mapper-processor`.
No error — generated mappings vanish and everything falls back to
reflection.

**Fix:** add the processor to the block, or drop the block. Then grep
the build log for the warn-level hint.

**Verify:** the log must NOT contain
`generated mapping %s not found, type will use reflection-based
mapping`. That line means the processor did not run for that type.

## 3. Null semantics deltas

**Symptom if skipped:** subtle behavioral drift on nullable fields —
each rule is defensible, none is discoverable.

**The pinned rules** (`JqMapperBoxedNullTest`):
- Absent keys and explicit JSON nulls bind `null` for boxed targets
  (Jackson/JSON-B parity).
- Primitives keep their zero values on absent/null.
- Round trips preserve nulls.

**Fix:** null fields are included by default; exclude them per type
with `@JqInclude(JqInclude.Include.NON_NULL)` (see `JqMapperTest`).

**Verify:** port the null-field cases of your existing Jackson tests
first — they are the cheapest migration gate. Empty-vs-null
collection binding is shape-dependent; verify it against your shapes
rather than assuming either convention.

## 4. String JSON columns

**Symptom if skipped:** confusion when a `String` column holds JSON
text and native queries keep working "by accident."

**The contract** (`JqValueFormatMapper`): `fromString` yields raw text
for `String` targets, and `toString` mirrors it — a String column
carries raw JSON text in both directions. Native queries re-parsing
that text keep working, but only because this contract exists.

**Verify:** round-trip a JSON-text column through the mapper and assert
the text is byte-identical.

## 5. Scalar collections and unmapped POJOs decline

**Symptom if skipped:** homogeneous scalar containers have no writer;
with no container default the runtime falls back to `toString()` with
HTTP 200 — silent garbage. The h5m instance was
`Map<String, Integer>` rendering `{count-test=0}` as
`application/json`.

**Fix:** change the endpoint shape (e.g. to `JqObject`), keep a
fallback engine, or bind through the mapper with annotated element
types. Do not discover this in production.

**Verify:** read the build log. The #198 endpoint report lists every
JSON endpoint as served, declined (with reason), or unverifiable —
`raw-collection-element` and `unmapped-pojo` lines are the migration
TODO list. Add an endpoint returning an unhandled type and the report
tells you immediately.

## 6. OpenAPI stays hand-maintained

**Symptom if skipped:** generated schemas drift from wire shapes on
polymorphic families and renames.

**The contract:** `@JqName` is jjq's own rename mechanism (wire names,
discriminator values — Jackson equivalent: `@JsonProperty`), and it is
correct for binding. But schema *generation* (SmallRye OpenAPI) reads
Jackson/JSON-B annotations, not jjq's. So: keep `@JqName` for binding
and add `@JsonbProperty` for renames plus
`@Schema(oneOf/discriminatorMapping)` for poly families.

**Verify:** keep an `openapi.yaml` diff gate per change; any unexpected
hunk blocks the migration step.

## 7. http-problem no longer keeps the JSON-B engine

**Symptom if skipped (historical):** after the cutover, `quarkus-jsonb`
stayed solely for `JsonbProblemSerializer`.

**Fix:** it can go now. When `quarkus-http-problem` is present and no
JSON engine is on the classpath, the extension registers jjq's
`JqProblemWriter` conditionally (`problemWriterWanted`), covering
403/400/500 shapes byte-identically to the engine — including
validation `Violation` lists.

**Verify:** remove `quarkus-jsonb`, then exercise 403, 400 (including a
422-style violations body), and 500 responses and diff the bytes
against engine rendering. If any problem shape diverges, that shape —
not the engine — is the remaining work.
