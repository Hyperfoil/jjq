# jjq Examples

This module contains runnable examples demonstrating how to use jjq both as a CLI tool and as a Java library for querying in-memory JSON data.

## Building

```bash
mvn install -pl jjq-core,jjq-fastjson2,jjq-examples -am
```

## Running the Examples

Each example class has a `main` method and can be run directly:

### BasicExamples

Core jq operations: field access, iteration, filtering, map, reduce, object construction, string operations, and bytecode VM execution.

```bash
mvn -pl jjq-examples exec:exec \
  -Dexec.mainClass=io.hyperfoil.tools.jjq.examples.BasicExamples
```

### InMemoryQueryExamples

Real-world patterns for using jq expressions to query application data held in memory — server monitoring, dynamic user queries, parameterized filters with variables, transformation pipelines, metrics aggregation, and config extraction.

```bash
mvn -pl jjq-examples exec:exec \
  -Dexec.mainClass=io.hyperfoil.tools.jjq.examples.InMemoryQueryExamples
```

### FastjsonEngineExamples

Using the `FastjsonEngine` high-level API for applications that already use fastjson2 — quick one-liner queries, fastjson2 `JSONObject`/`JSONArray` interop, lazy conversion for large documents, byte buffer processing, JSON stream (NDJSON) processing, and round-tripping back to fastjson2.

```bash
mvn -pl jjq-examples exec:exec \
  -Dexec.mainClass=io.hyperfoil.tools.jjq.examples.FastjsonEngineExamples
```

### CliExamples

Prints a comprehensive reference of CLI usage examples covering all options and advanced patterns. Use this as a cheat sheet.

```bash
mvn -pl jjq-examples exec:exec \
  -Dexec.mainClass=io.hyperfoil.tools.jjq.examples.CliExamples
```

## Example Classes Overview

| Class | Focus |
|-------|-------|
| `BasicExamples` | Core API — compile, apply, iterate, filter, reduce, VM |
| `InMemoryQueryExamples` | Querying application data, variables, pipelines, aggregation |
| `FastjsonEngineExamples` | fastjson2 integration, lazy loading, byte buffers, streams |
| `CliExamples` | CLI command reference and cheat sheet |

## Sample Data

The `src/main/resources/` directory contains sample JSON files:

- `users.json` — user records with names, roles, and scores
- `orders.json` — order records with line items and prices
