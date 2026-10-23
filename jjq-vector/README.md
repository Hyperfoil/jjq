# jjq-vector

Experimental explicit-vector kernels ([issue #187](https://github.com/Hyperfoil/jjq/issues/187)).
Strictly opt-in: nothing references this module from any default path.

## Status: stage 1 (escape-scan kernel)

- `VectorKernels.scanVector` is an explicit Vector API kernel
  (compare/mask/early-exit, scalar tail); scalar arm stays as fallback
  and differential oracle. Both arms pinned to identical output
  (corpus, block-boundary, 500 randomized differentials).
- `VectorScanBenchmark` (provided-scope JMH, run on demand) gates it:
  13.3x scalar on 4KB-clean, 5.8x on 512-clean, ties on dense,
  zero allocation delta (Temurin 27).

- Module joins the reactor on **JDK 27+ only** (profile-gated);
  older JDKs never see it.
- `VectorKernels.scanFirstEscape` dispatches on `isSupported()`
  (explicitly enabled plus a usable species); both arms currently share
  one scalar loop, so output is identical by construction. Stage 1
  replaces the vector arm with a Vector API kernel.
- Requires `--add-modules jdk.incubator.vector` to compile and run
  (wired in this module's build); do not load these classes on JDK < 27.

## Constraints (non-negotiable)

- `jjq-core` stays dependency-free; this module is never mandatory.
- Scalar fallback always present, always tested.
- Every future kernel ships with differential tests vs the scalar path
  and end-to-end (never kernel-only) benchmark verdicts.
- Incubator churn (JEP 537) is accepted experimental risk.
