# jjq-vector

Experimental explicit-vector kernels ([issue #187](https://github.com/Hyperfoil/jjq/issues/187)).
Strictly opt-in: nothing references this module from any default path.

## Status: stage 0 (plumbing only)

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
