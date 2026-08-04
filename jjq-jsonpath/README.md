# jjq-jsonpath

Converts **PostgreSQL SQL/JSON path** expressions to jq, with lax and strict mode support.

## Important: SQL/JSON path vs JSONPath

This module targets **PostgreSQL's SQL/JSON path** language (ISO 9075-2), **not** the
JavaScript-ecosystem JSONPath (RFC 9535 / Goessner). These are different standards with
different syntax:

| Feature | SQL/JSON path (this module) | JSONPath (RFC 9535) |
|---------|----------------------------|---------------------|
| Filter syntax | `? (@.price < 10)` | `[?@.price < 10]` |
| Recursive descent | `$.**`, `$.**{2}` | `$..name` |
| Modes | `lax` (default), `strict` | No mode concept |
| Variables | `$varname` | Not supported |
| Methods | `.size()`, `.type()`, `.keyvalue()`, etc. | `length()`, `count()` |
| Regex | `like_regex "pat" flag "i"` | `match(@.x, "pat")` |
| Array slicing | `$[1 to 5]` | `$[1:5]` |

If you need RFC 9535 JSONPath support, this is not the right module. This module is
designed for migrating from PostgreSQL's `jsonb_path_query()` / `jsonb_path_query_first()`
/ `jsonb_path_query_array()` functions to application-side jq evaluation via jjq.

## Usage

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-jsonpath</artifactId>
    <version>${jjq.version}</version>
</dependency>
```

### Convert and compile (recommended)

```java
import io.hyperfoil.tools.jjq.jsonpath.JsonpathToJq;
import io.hyperfoil.tools.jjq.jsonpath.JsonpathToJq.Mode;

// Compile with validation — errors caught at conversion time
JqProgram program = JsonpathToJq.compile("$.results[*] ?(@.name == \"x\")");
JqValue result = program.apply(data);
```

### Convert to jq string

```java
// Lax mode (default — matches PostgreSQL default)
String jq = JsonpathToJq.convert("$.a.b.c");

// Strict mode (matches jq semantics)
String jq = JsonpathToJq.convert("$.a.b.c", Mode.STRICT);
// ".a.b.c"

// Array collection (jsonb_path_query_array equivalent)
String jq = JsonpathToJq.convertArray("$.items[*].name");
// "[.items[]?.name]"
```

## Modes

### Strict

Direct translation — dot-access on arrays fails (matches standard jq semantics):

| SQL/JSON path | jq |
|---|---|
| `$.a.b.c` | `.a.b.c` |
| `$.a[*].b` | `.a[]?.b` |
| `$[*]` | `.[]?` |

### Lax (default)

PostgreSQL's default mode — auto-unwraps arrays at intermediate dot-access segments:

```sql
-- PostgreSQL lax: auto-unwraps array at b
SELECT jsonb_path_query_first('{"a":{"b":[{"c":"one"}]}}', '$.a.b.c');
-- Returns: "one"
```

The converter produces conditional unwrapping at each intermediate segment:

```jq
if (.a | type) == "array" then .a[] else .a end |
if (.b | type) == "array" then .b[] else .b end |
.c
```

## Supported conversions

| SQL/JSON path | jq (strict) | Notes |
|---|---|---|
| `$` | `.` | Identity |
| `$.a.b.c` | `.a.b.c` | Field access |
| `$.a[*].b` | `.a[]?.b` | Array iteration |
| `$.*` / `$.foo.*` | `.[]?` / `.foo[]?` | Wildcard |
| `$.**{N}` | `. \| recurse` | Recursive descent |
| `$.config.size()` | `.config \| length` | Array/object length |
| `$.data.keyvalue()` | `.data \| to_entries[]` | Key-value pairs |
| `$.value.double()` | `.value \| tonumber` | Type conversion |
| `$.value.type()` | `.value \| type` | Type name |
| `$.value.abs()` | `.value \| fabs` | Math |
| `$.a ?(@.b == "x")` | `.a[]? \| select(.b == "x")` | Filter |
| `$.a ?(@.b > 5 && @.c < 10)` | `.a[]? \| select(.b > 5 and .c < 10)` | Logical operators |
| `$.a ?(@.name like_regex "pat" flag "i")` | `.a[]? \| select((.name \| test("pat"; "i")))` | Regex |
| `$."special.key"` | `."special.key"` | Quoted keys |
| `$.a ?(@ starts with "pre")` | `.a[]? \| select((. \| startswith("pre")))` | String prefix |
| `$[last]` | `.[-1]` | Last array element |
| `$[last - 2]` | `.[-3]` | Offset from last |
| `$[1 to 5]` | `.[1:6]` | Array range (inclusive → exclusive) |
| `$.data[$.data.size()-1]` | `.data[.data \| length-1]` | Size in bracket index |

## Mode prefix

Expressions can include a mode prefix that overrides the `Mode` parameter:

```java
// "strict" prefix forces strict mode even when Mode.LAX is passed
JsonpathToJq.convert("strict $.a.b", Mode.LAX);
// ".a.b" (no lax unwrapping)

// "lax" prefix forces lax mode even when Mode.STRICT is passed
JsonpathToJq.convert("lax $.a.b", Mode.STRICT);
// conditional unwrapping applied
```
