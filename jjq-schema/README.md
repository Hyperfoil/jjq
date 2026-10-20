# jjq-schema

Zero-dependency JSON Schema validation over jjq's [JqValue](../jjq-core) tree
(pragmatic 2020-12 subset with draft-07 compatibility).

## Quick Start

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-schema</artifactId>
    <version>${jjq.version}</version>
</dependency>
```

```java
import io.hyperfoil.tools.jjq.schema.JqSchema;
import io.hyperfoil.tools.jjq.value.JqValues;

// Compile once, reuse across documents and threads
JqSchema schema = JqSchema.compile(JqValues.parse(schemaJson));

boolean valid = schema.isValid(JqValues.parse(documentJson));

// Diagnostics only on failure (empty array means valid)
JqArray errors = schema.validate(JqValues.parse(documentJson));
```

Each error object carries `instanceLocation` (JSON Pointer, `""` for the
root), `keywordLocation` (URI fragment, e.g. `#/properties/age/type`),
`keyword`, and `message`.

## Supported Subset

| Area | Keywords |
|------|----------|
| Any instance | boolean schemas, `type`, `enum`, `const` |
| Numbers | `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf` |
| Strings | `minLength`, `maxLength`, `pattern` (unanchored search) |
| Arrays | `items`, `prefixItems`, `minItems`, `maxItems`, `contains`, `minContains`, `maxContains`, `uniqueItems` |
| Objects | `properties`, `patternProperties`, `required`, `additionalProperties`, `minProperties`, `maxProperties` |
| Combinators | `allOf`, `anyOf`, `oneOf`, `not`, `if`/`then`/`else` |
| References | local `$ref`, `$defs`, `definitions` |
| Ignored | `format`, `title`, `description`, `default`, unknown keywords (annotations) |

Out of scope (rejected at compile time rather than silently dropped):
`propertyNames`, `dependencies`, `dependentRequired`, `dependentSchemas`,
`unevaluated*`, dynamic references, remote references, named anchors,
nested `$id`, vocabularies. `format` is annotation-only by design.

## Draft-07 Compatibility

Schemas declaring draft-07 get draft-07 semantics: tuple-form `items` with
`additionalItems`, and `$ref` siblings ignored. In 2020-12 mode, tuple
validation uses `prefixItems` with tail `items`, and `$ref` siblings apply
alongside. Both drafts use numeric (not boolean) exclusive bounds.

## Semantics Worth Knowing

- `integer` means zero fractional part: `1`, `1.0`, and `18446744073709551615`
  all qualify; `1.5` does not.
- Numbers compare exactly: `1` equals `1.0`, but `18446744073709551615`
  does not equal `18446744073709551616`.
- String bounds count Unicode code points, not UTF-16 units.
- `pattern` and `patternProperties` use search semantics (`"es"` matches
  `"expression"`). Patterns are Java regex: ECMA-only constructs
  (`\c` escapes, `\p{Letter}`-style classes beyond Java's set) fail
  compilation with a location instead of silently diverging.
- `then`/`else` without `if` are entirely ignored (never even compiled).
- Cyclic references without descent (`{"$ref": "#"}`) fail compilation;
  recursion through properties/items is allowed.
- Validation never coerces, inserts defaults, or mutates the instance.

## yaup Migration

`yaup`'s `JsonValidator` (Vert.x `vertx-json-schema`) maps directly:

```java
// yaup:  Json errors = new JsonValidator(schema).validate(input);
// jjq:   JqArray errors = JqSchema.compile(schemaTree).validate(inputTree);
//        // empty array means valid; each element has instanceLocation,
//        // keywordLocation, keyword, and message
```

## Performance

Measured on Temurin 25.0.4, 3 forks, `-prof gc`
(see `JqSchemaCompileBenchmark`, `JqSchemaValidateBenchmark`,
`JqSchemaSharedBenchmark`):

- Boolean validation allocates 0 B/op on every row (tiny object 12ns,
  20-field object ~910ns, real 9KB run schema ~150ns).
- Early failure stops fast (20-field object 258ns vs 942ns for a late failure).
- Reporting on valid documents costs exactly the boolean check;
  error serialization of precomputed diagnostics is ~115ns.
- One compiled schema validates safely from 4 threads with no slowdown
  versus single-threaded use.
- One-time compile: tiny schema 246ns, 20-field schema 1.6µs,
  9KB schema 9.5µs.

## Conformance

The official JSON-Schema-Test-Suite (vendored snapshot, draft-07 plus
draft-2020-12) runs report-only: 1648 passed, 0 mismatched of 4210.
Skipped cases are categorized (remote references, format/content
assertion, out-of-subset keywords, ECMA-only regex); see
`json-schema-test-suite/README.md` and `schema-skips.txt`.

## Dependencies

This module depends only on `jjq-core` (plus JUnit test scope).
