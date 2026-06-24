# jjq-jsonata

Compile-time JSONata-to-jq transpiler for jjq. Parses [JSONata](https://jsonata.org) expressions and translates them to equivalent [jq](https://jqlang.github.io/jq/) expressions at compile time. The output is a standard `JqProgram` that executes through jjq's optimized bytecode VM — zero JSONata runtime overhead at evaluation time.

## Quick Start

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-jsonata</artifactId>
    <version>0.1.4-SNAPSHOT</version>
</dependency>
```

```java
import io.hyperfoil.tools.jjq.jsonata.JsonataCompiler;
import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.*;

// Compile JSONata to jq (one-time cost, ~microseconds)
JqProgram program = JsonataCompiler.compile("$sum(orders.price)");

// Execute through jjq's bytecode VM (same performance as native jq)
JqValue data = JqValues.parse("{\"orders\": [{\"price\": 10}, {\"price\": 20}]}");
JqValue result = program.apply(data);  // → 30
```

### Inspecting the Translation

```java
String jq = JsonataCompiler.toJq("Address.City");
// → ".Address.City"

String jq2 = JsonataCompiler.toJq("$sum(orders.price)");
// → "([.orders | if type == \"array\" then .[] else . end | (.price? // empty)] | add)"

String jq3 = JsonataCompiler.toJq("Phone[type='mobile'].number");
// → "[.Phone[] | select(.type == \"mobile\")].number"
```

## API

### `JsonataCompiler`

| Method | Description |
|--------|-------------|
| `compile(String jsonata)` → `JqProgram` | Parse, translate to jq, compile to bytecode. Thread-safe, reusable. |
| `toJq(String jsonata)` → `String` | Translate to jq without compiling. Useful for debugging/inspection. |

Both methods throw `JsonataException` for unsupported features or syntax errors. No silent semantic differences — if it compiles, it produces correct results.

### Performance

The translation happens **once at compile time**. At evaluation time, it's the same `JqProgram.apply()` that jjq has optimized to 3 ns field access with zero allocation. The JSONata source is gone by then.

```
JSONata string → [parse + translate] → jq string → JqProgram.compile() → Bytecode VM
                  ↑ one-time (~μs)                   ↑ cached, reused
```

## Supported Features

### Navigation

| JSONata | jq Translation | Example |
|---------|----------------|---------|
| `Surname` | `.Surname` | Field access |
| `Address.City` | `.Address.City` | Nested field |
| `Phone[0]` | `.Phone[0]` | Array index |
| `Phone[-1]` | `.Phone[-1]` | Negative index |
| `Phone[0].number` | `.Phone[0].number` | Chained access |
| `$` | `.` | Root reference |
| `` Other.\`Over 18 ?\` `` | `.Other."Over 18 ?"` | Quoted field names |

### Implicit Array Mapping

JSONata's most distinctive feature — when a path step encounters an array, it automatically iterates and collects results:

| JSONata | jq Translation | Notes |
|---------|----------------|-------|
| `foo.blah.baz` | `[.foo \| (.blah? // empty \| if type == "array" then .[] else . end) \| (.baz? // empty)] \| <singleton-unwrap>` | Auto-iterates arrays at each intermediate step |
| `$sum(orders.price)` | `([.orders \| if type == "array" then .[] else . end \| (.price? // empty)] \| add)` | Iteration in function args |

Two-step paths (`A.B`) use simple direct access (`.A.B`) for efficiency. Three-or-more step paths use the auto-mapping pattern.

### Operators

| JSONata | jq | Notes |
|---------|-----|-------|
| `a + b`, `a - b`, `a * b`, `a / b`, `a % b` | Same | Arithmetic |
| `a = b` | `a == b` | Equality (note: single `=` in JSONata) |
| `a != b` | `a != b` | Inequality |
| `a > b`, `a < b`, `a >= b`, `a <= b` | Same | Comparison |
| `a and b`, `a or b`, `not a` | Same | Boolean |
| `a & b` | `((a \| tostring) + (b \| tostring))` | String concatenation |
| `a ? b : c` | `if a then b else c end` | Ternary |
| `a in b` | `((b \| index(a)) != null)` | Array membership |

### Predicates

| JSONata | jq Translation |
|---------|----------------|
| `Phone[type='mobile']` | `[.Phone[] \| select(.type == "mobile")]` |
| `Phone[type='mobile'].number` | `[.Phone[] \| select(.type == "mobile")].number` |

### Functions

#### Aggregation

| JSONata | jq Translation | Notes |
|---------|----------------|-------|
| `$sum(array)` | `(array \| add)` | Null-safe: returns null for null input |
| `$max(array)` | `(array \| max)` | Wraps non-array in `[.]` |
| `$min(array)` | `(array \| min)` | Wraps non-array in `[.]` |
| `$average(array)` | `(array \| (add / length))` | |
| `$count(array)` | `(array \| length)` | |

#### String

| JSONata | jq Translation |
|---------|----------------|
| `$string(x)` | `(x \| tostring)` |
| `$length(str)` | `(str \| length)` |
| `$uppercase(str)` | `(str \| ascii_upcase)` |
| `$lowercase(str)` | `(str \| ascii_downcase)` |
| `$trim(str)` | `(str \| ltrimstr(" ") \| rtrimstr(" "))` |
| `$contains(str, pattern)` | `(str \| contains(pattern))` |
| `$split(str, sep)` | `(str \| split(sep))` |
| `$join(arr, sep)` | `(arr \| join(sep))` |
| `$substring(str, start, len)` | Custom expression with JSONata negative-index semantics |

#### Array

| JSONata | jq Translation |
|---------|----------------|
| `$sort(array)` | `(array \| sort)` |
| `$reverse(array)` | `(array \| reverse)` |
| `$distinct(array)` | `(array \| unique)` |
| `$append(arr1, arr2)` | `(arr1 + arr2)` |

#### Object

| JSONata | jq Translation |
|---------|----------------|
| `$keys(obj)` | `(obj \| keys)` |
| `$values(obj)` | `(obj \| [.[]])` |
| `$merge(arr)` | `(arr \| add)` |
| `$type(x)` | `(x \| type)` |
| `$exists(x)` | `(x != null)` |

#### Numeric

| JSONata | jq Translation |
|---------|----------------|
| `$number(x)` | Custom: handles boolean (true→1, false→0), string (→tonumber), null (→null) |
| `$abs(x)` | `(x \| fabs)` |
| `$floor(x)` | `(x \| floor)` |
| `$ceil(x)` | `(x \| ceil)` |
| `$round(x)` | `(x \| round)` |

### Construction

| JSONata | jq Translation |
|---------|----------------|
| `{"name": Surname, "age": Age}` | `{"name": .Surname, "age": .Age}` |
| `[1, 2, 3]` | `[1, 2, 3]` |

## Conformance

Tested against the upstream [JSONata conformance test suite](https://github.com/jsonata-js/jsonata/tree/master/test/test-suite) (527 test cases across 37 groups):

| Status | Count | % |
|--------|-------|---|
| **Passing** | 316 | 60.0% |
| Skipped (unsupported features) | 126 | 23.9% |
| Skipped (implementation gaps) | 85 | 16.1% |

Run the conformance tests:
```bash
mvn test -Pjsonata -pl jjq-jsonata -Dtest=JsonataConformanceTest
```

## Known Limitations

### Not Supported (throws `JsonataException`)

| Feature | Reason |
|---------|--------|
| Lambda expressions (`function($x) { ... }`) | jq doesn't have first-class functions |
| Higher-order functions (`$map`, `$filter`, `$reduce`) | Require lambda translation (Phase 3) |
| Variable binding (`$x := expr`) | Requires block expression parsing (Phase 2) |
| Block expressions (`(expr1; expr2)`) | Parser doesn't handle `;` yet (Phase 2) |
| Regular expressions (`/pattern/flags`) | Parser doesn't handle regex literals (Phase 3) |
| Recursive descent (`**`) | Not yet implemented (Phase 3) |
| Transform operator (`~>`) | Not yet implemented (Phase 3) |
| Parent operator (`%`) | No jq equivalent — cannot be translated |
| `$eval()` | Cannot compile at transpile time |
| User-defined functions (`$f := function...`) | jq `def` has different scoping |
| `$match()`, `$replace()` with regex | Requires regex literal support |

### Semantic Differences

| Area | JSONata | jjq-jsonata (via jq) |
|------|---------|---------------------|
| Division precision | IEEE 754 double | BigDecimal (higher precision) |
| Number formatting | `1e+100` | `1E+100` (case difference) |
| Unicode `$substring` | Counts codepoints | Counts code units (surrogate pair issue) |

### Implicit Array Mapping Limitations

- Two-step paths (`A.B`) use direct access — works for objects, fails silently for arrays
- Three+ step paths use auto-mapping — handles both objects and arrays
- Singleton unwrapping: multi-match paths return arrays; JSONata sometimes unwraps single-element arrays to scalars

## Roadmap

- **Phase 2** (issue #30): `$round` precision, `$split` limit, block expressions, `$` in predicates, variable bindings, `$substringBefore`/`$substringAfter` — target 60-65% conformance
- **Phase 3** (issue #31): Lambdas, `$map`/`$filter`/`$reduce`, regex, recursive descent, `$sort` with comparator — target 75-80% conformance

## Building

```bash
# Build (requires jjq-core to be installed first)
mvn install -DskipTests          # install jjq-core
mvn package -Pjsonata -pl jjq-jsonata

# Run tests
mvn test -Pjsonata -pl jjq-jsonata

# Run conformance suite only
mvn test -Pjsonata -pl jjq-jsonata -Dtest=JsonataConformanceTest
```

## License

This project is licensed under the [Apache License 2.0](../LICENSE).
