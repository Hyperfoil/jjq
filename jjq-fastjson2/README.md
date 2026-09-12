# jjq-fastjson2

fastjson2 integration for jjq — provides `JSONObject`/`JSONArray` <-> `JqValue`
conversion with lazy wrappers, plus a high-level `FastjsonEngine` for jq evaluation.

## Dependencies

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-fastjson2</artifactId>
    <version>${jjq.version}</version>
</dependency>
```

Depends on `jjq-core` and `fastjson2` (version managed by parent POM via
`${fastjson2.version}`).

## FastjsonEngine

High-level API for applying jq filters to fastjson2 values:

```java
import io.hyperfoil.tools.jjq.fastjson2.FastjsonEngine;
import io.hyperfoil.tools.jjq.JqProgram;

FastjsonEngine engine = new FastjsonEngine();
// Or with custom builtins: new FastjsonEngine(builtins)

// One-shot: parse, compile, execute
List<JqValue> results = engine.apply(".users[] | .name", jsonString);

// Pre-compiled for repeated use (recommended)
JqProgram program = engine.compile(".users[] | {name, email}");

// Serialize results back to strings or bytes
List<String> strings = engine.applyToStrings(".users[] | .name", jsonString);
byte[] bytes = engine.applyToBytes(".users[] | .name", jsonBytes);
```

For streaming and buffer slices:

```java
// Stream results from an InputStream
Stream<JqValue> stream = engine.applyStream(".items[]", inputStream);

// Evaluate against a byte[] slice
Stream<JqValue> slice = engine.applyBuffer(program, buffer, offset, length);
byte[] out = engine.applyToBytes(program, buffer, offset, length);
```

## Lazy Conversion

`LazyConverter` wraps fastjson2 objects without deep-copying — fields convert
on first access:

```java
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.hyperfoil.tools.jjq.fastjson2.LazyConverter;

JSONObject obj = JSON.parseObject(jsonString);
JqValue lazy = LazyConverter.lazyObject(obj);   // zero-copy wrapper
JqValue arr = LazyConverter.lazyArray(jsonArray);
```

Only touched fields are converted. Best for large objects with sparse access patterns.

## Eager Conversion

For small objects or full traversals, eager conversion avoids wrapper overhead:

```java
JqValue value = FastjsonEngine.fromJson(jsonString);
JqValue fromObj = FastjsonEngine.fromFastjson(fastjsonObject);
Object back = FastjsonEngine.toFastjson(jqValue);
```

## When to Use fastjson2 vs Jackson

Use `jjq-fastjson2` when the application already depends on fastjson2 (common in
Alibaba-ecosystem and high-throughput services). Use `jjq-jackson` when the
application uses Jackson. Both modules are optional — `jjq-core` has zero
dependencies either way.
