# jjq-quarkus

Quarkus extension wiring `jjq-mapper` and the `jjq-jakarta` providers into
a Quarkus application: Jandex discovery of `@JqMapped` types, a CDI-managed
`JqMapper` singleton with all generated mappings pre-registered (no
`Class.forName`, no runtime reflection on the default path), JAX-RS
provider registration for both REST stacks, and optional Hibernate
`FormatMapper` support for Jackson-free applications.

## What gets registered

- `@JqMapped` records/classes: their `_JqMapping` classes are instantiated
  with direct constructor calls in a generated CDI producer. Types without
  a generated mapping keep the reflection fallback; bridges come only from
  explicit `jjq-mapper-jackson` / `jjq-mapper-jsonb` module dependencies.
- Enums with `@JsonValue`/`@JsonCreator` (or native equivalents) get
  compiled `_EnumBinding` classes when expressible; anything else stays
  reflective.
- Polymorphic families (`@JqType`, `@JqKeys`) resolve during augmentation
  and bake their dispatch tables into the producer (sealed permits metadata
  does not survive into native images).
- `JqMappedMessageBodyReader/Writer`, `JqValueMessageBodyReader/Writer`,
  the `JqValue` param converter, and the `JqBadRequestException` mapper are
  registered for RESTEasy Reactive and Classic; both stacks share the same
  injected mapper. `quarkus-jackson` on the classpath fails the build fast
  (the two JSON extensions are mutually exclusive).

## Configuration (`quarkus.jjq.*`, build time)

| Property | Default | Meaning |
|---|---|---|
| `quarkus.jjq.fail-on-unknown-properties` | `false` | Fail request binding on unrecognized JSON properties (Jackson `FAIL_ON_UNKNOWN_PROPERTIES` parity); otherwise unknown keys are ignored. |
| `quarkus.jjq.direct-readers` | `false` | Generate direct byte-to-record readers for eligible flat request records (see below) and consult them before the tree path. |

## Direct readers (opt-in)

With `quarkus.jjq.direct-readers=true`, augmentation compiles one reusable
reader per eligible request body type — flat `@JqMapped` records over
`String`, `int`, `long`, `boolean` and nullable boxed equivalents with
basic wire naming and required presence — from its immutable binding
schema, and registers it on the CDI mapper. Request types are the record
types taken directly as `@POST`/`@PUT`/`@PATCH` parameters; one reader is
shared across endpoints, and serialization-only models get none.

At request time `fromJsonStrict` consults the reader first: canonical
requests decode straight from bytes with no `JqValue` tree, while unknown
or escaped names, nested values, accepted coercions, duplicates, and
malformed input fall back to the identical strict parse-plus-map path —
accepted inputs and error behavior never change, and user constructors run
only after the whole body has passed strict validation. Nested records
and collections, aliases, `@JqField` programs, custom converters,
creators, builders, polymorphic families, identity, any-setters, POJO
setters, and records wider than 64 fields keep the existing path.

Verify registration with `mapper.directReaderFor(MyRequest.class)` (null
means the existing path serves the type). Readers are stateless and shared
across requests; no per-request introspection, no document retention.
