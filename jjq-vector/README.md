# jjq-vector

Optional SIMD acceleration for jjq ([issue #187](https://github.com/Hyperfoil/jjq/issues/187),
[integration for #211](https://github.com/Hyperfoil/jjq/issues/211)).
Strictly opt-in: `jjq-core` never depends on this module, and every
accelerated path has a scalar fallback that is always present and always tested.

## What it accelerates

| Kernel | Accelerates | Measured effect |
|---|---|---|
| `VectorByteSkipKernel` | Container skipping in on-demand extraction — `JqProgram.apply(byte[])` and `applyStrict(byte[], ParseOptions)` when skipping large subtrees | **5.9–8.2x** over the scalar SWAR skipper; `.search_metadata.count` on a 631KB twitter.json drops 307 → 50 us (12.7 GB/s scan); 14MB production queries drop to 22–286 us |
| `VectorKernels.scanFirstEscape` | First-escape scanning in string serialization | 13.3x on 4KB clean strings, ties on escape-dense (scalar fallback shape) |

The biggest wins come from queries that skip a lot: a field near the end of
a large document, or a projection that touches a small slice of a big tree.
Short skips and tiny documents stay on the scalar path — the kernel engages
per skip call only when it is enabled and the species is usable, so there is
no fixed overhead when it cannot help.

## Requirements

- **JDK 27+** (class files are release 27; older JDKs cannot load the module)
- **`--add-modules jdk.incubator.vector`** on the `java` command line — the
  Vector API is still incubating (JEP 537); without the flag the module's
  classes fail to load, jjq-core's `ServiceLoader` lookup catches that, and
  the scalar path runs unchanged
- x86-64 with AVX2/AVX-512 or AArch64 NEON (any species of 128+ bits)

## Using it

Add the module and the JVM flag — **no code changes**. The kernel registers
itself via `META-INF/services` and `jjq-core` discovers it through
`ServiceLoader` on first use:

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-vector</artifactId>
    <version>0.2.5-SNAPSHOT</version>
</dependency>
```

```bash
java --add-modules jdk.incubator.vector -jar app.jar
```

Everything that worked before keeps working, identically: the on-demand
extraction APIs produce the same results with or without the module
(pinned by differential tests that run both arms and compare).

## Disabling it

Force the scalar path at runtime without removing the dependency:

```bash
java -Djjq.vector.enabled=false ...
```

The flag is read dynamically, so tests can toggle it per case
(`VectorByteSkipKernelTest.programApplyParityWithVectorToggled` does
exactly that to prove both arms agree).

## What happens without it

`jjq-core` alone is fully functional: the `JsonByteSkipKernel` lookup
returns nothing and the scalar SWAR skipper handles every call. GraalVM
native images and any JDK below 27 are unaffected. The only cost of
omitting the module is the SIMD speedups above.

## Verification discipline (non-negotiable)

- `jjq-core` stays dependency-free; this module is never mandatory.
- Scalar fallback always present, always tested.
- Every kernel ships with differential tests vs the scalar path
  (3000-round randomized documents, escape runs at every block-boundary
  residue, deep-nesting and truncation cases) and end-to-end — never
  kernel-only — benchmark verdicts.
- Incubator churn (JEP 537) is accepted experimental risk; the module is
  excluded from GraalVM native-image builds by construction.

## Benchmarks

`VectorScanBenchmark` (provided-scope JMH, run on demand from this
module's `target/classes` plus the JMH jars — see the class header for
the exact command) gates the escape-scan kernel. The container skipper
is gated end-to-end through `JqProgram.apply(byte[])` benchmarks with
`-Djjq.vector.enabled` toggled on an identical classpath.
