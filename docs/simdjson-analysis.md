# simdjson Techniques: Applicability Analysis for jjq

Analysis of Daniel Lemire's blog post on compile-time JSON parsing with C++26
static reflection and the simdjson paper "Parsing Gigabytes of JSON per Second"
(Langdale & Lemire, VLDB Journal 2019, arXiv:1902.08318).

## Sources

- Blog: https://lemire.me/blog/2026/06/14/parsing-json-at-compile-time-with-c26-static-reflection/
- Paper: https://arxiv.org/abs/1902.08318
- Library: https://github.com/simdjson/simdjson

## Compile-Time JSON Parsing (Blog Post)

C++26 introduces `#embed` (embed file bytes at compile time) and `constexpr`
evaluation with static reflection. Combined with simdjson, this allows JSON
configuration files to be parsed entirely at compile time -- the compiler reads
the file, parses it, and bakes the result into the binary as constants. No
runtime parsing, no file I/O.

**Not applicable to jjq.** Java has no equivalent of `constexpr` or `#embed`.
jjq processes dynamic JSON data at runtime (h5m receives JSON from uploads,
benchmarks, API calls). Compile-time parsing is for statically-known
configuration, which isn't jjq's use case.

**Related existing optimization:** jjq's bytecode compiler already performs
constant folding for arithmetic expressions (`2 + 3` -> `5` at compile time).
This is the closest conceptual equivalent, but applies to jq expressions rather
than JSON data.

## simdjson Paper: Key Techniques

### Two-Stage Parsing Architecture

simdjson splits parsing into two stages:

1. **Stage 1 (structural indexing):** Uses SIMD instructions to process 64
   bytes at a time, classifying every byte simultaneously. Identifies structural
   characters (`{`, `}`, `[`, `]`, `:`, `,`), string boundaries (quoted
   regions), and whitespace. Produces a compact index of structural positions.

2. **Stage 2 (tape construction):** Walks the structural index to build a
   "tape" of JSON events, validating structure and extracting values. This stage
   is largely sequential but benefits from the pre-computed index.

The key insight is that Stage 1 eliminates most branches from the critical path
by using bitmask operations instead of character-by-character decisions.

### SIMD Byte Classification

Uses `vpshufb` (byte shuffle) to map each byte to a character class using a
128-entry lookup table split across two SIMD registers. Processes 32 or 64
bytes per instruction.

### String Boundary Detection

Finds matching quotes using XOR-prefix-sum to track parity across 64-byte
blocks. Handles escaped quotes using carry-less multiplication (`vpclmulqdq`)
to detect odd-length backslash sequences.

### Branchless Number Parsing

Uses lookup tables and arithmetic to parse numbers without branches, avoiding
branch misprediction penalties on varied numeric formats.

### Performance

2-4 GB/s throughput on modern x86 CPUs, using ~4x fewer instructions than
RapidJSON. The speedup comes primarily from the SIMD classification in Stage 1.

## Applicability to jjq

### Not Directly Applicable

| Technique | Reason |
|-----------|--------|
| SIMD byte classification | Requires C intrinsics (SSE2/AVX2/AVX-512). Java's Vector API (JEP 338) is still incubating and not stable enough for jjq-core's zero-dependency constraint. |
| Carry-less multiplication for escapes | Hardware-specific instruction (`vpclmulqdq`), no Java equivalent. |
| `#embed` / `constexpr` parsing | C++26-specific, no Java equivalent. |
| Two-stage architecture | The overhead of an extra pass only pays off at gigabyte scale. jjq's typical inputs are kilobytes (h5m) to megabytes (CLI). |

### Already Implemented (Conceptual Equivalents)

| simdjson Technique | jjq Equivalent |
|-------------------|----------------|
| Fast string scanning (find closing `"` without branches) | `parseStringRaw()` fast path: scans for `"` without escape, returns `substring()` directly. JIT likely auto-vectorizes the inner loop. |
| Constant folding | Bytecode compiler folds `2 + 3` -> `5`, `true and false` -> `false` at compile time. |
| Branchless number parsing | `parseNumber()` uses direct digit accumulation into `long` without substring allocation. Not fully branchless but avoids the major allocation. |

### Marginally Applicable (Diminishing Returns)

| Technique | Potential | Why Not Worth Pursuing Now |
|-----------|-----------|---------------------------|
| Fully branchless number parsing | Could eliminate branches on decimal point/exponent detection | Numbers in h5m come through Jackson (not jjq's parser). CLI parser already within 2x of jq. |
| Two-stage structural indexing (without SIMD) | Could improve branch prediction on large inputs | Only benefits CLI on large files. h5m uses Jackson for parsing. |
| Lookup-table character classification | Could speed up `skipWs` and `parseValue` dispatch | The JIT compiler's branch prediction already handles the small character set well for typical JSON. |

### Future Possibility: Java Vector API

If Java's Vector API (JEP 338) graduates from incubation and becomes available
in standard JDKs, SIMD-accelerated techniques from simdjson could become
applicable to jjq's built-in parser:

- **Vectorized string scanning:** Find closing `"` and `\` positions using
  `ByteVector.compare()` across 16/32 bytes at a time.
- **Vectorized whitespace skipping:** Skip whitespace in bulk using vector
  comparison and mask operations.
- **Structural character classification:** Classify bytes into structural vs
  content using vector lookup tables.

This would only matter for the CLI parser path (h5m uses Jackson). It would
also require careful design to maintain jjq-core's zero-dependency constraint
-- the Vector API would need to be an optional acceleration path with a scalar
fallback.

**Current status:** Not actionable. The Vector API has been incubating since
Java 16 (2021) and is not yet stable. GraalVM native-image support for the
Vector API is also limited.

## Conclusion

The simdjson paper represents the state of the art in JSON parsing performance,
but its techniques are fundamentally about exploiting hardware-level parallelism
(SIMD) for raw byte processing. These techniques require C/C++ intrinsics that
Java cannot access directly.

For jjq's primary use case (h5m), JSON parsing is not the bottleneck -- Jackson
handles the parsing, and jjq's lazy conversion avoids processing most of the
document. The optimizations done in this session (fast-path string parsing,
direct integer accumulation, bulk segment appending in serialization) bring
jjq's built-in parser to within ~2x of jq's C parser, which is approximately
the best achievable in pure Java without JNI/FFI or the Vector API.

The most impactful next step for jjq performance is profiling real h5m workloads
with async-profiler (perf-backlog item 9) to identify whether jjq even appears
in the CPU profile at this point.

## References

- Langdale, G. and Lemire, D. "Parsing Gigabytes of JSON per Second."
  The VLDB Journal, 28(6), 2019. https://doi.org/10.1007/s00778-019-00578-5
- Lemire, D. "Parsing JSON at compile time with C++26 static reflection."
  Blog post, June 14, 2026.
- simdjson library: https://github.com/simdjson/simdjson
- Java Vector API: JEP 338, https://openjdk.org/jeps/338
