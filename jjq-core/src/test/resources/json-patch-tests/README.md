# json-patch-tests snapshot (issue #168, apply side)

Vendored from https://github.com/json-patch/json-patch-tests
(`tests.json`, MIT — see https://github.com/json-patch/json-patch-tests).
Downloaded 2026-10-20; the file carries no version tag, so refreshes
must re-verify the count below.

Run via `JsonPatchConformanceTest`: each case applies `patch` to `doc`
and compares against `expected`, or asserts a throw when `error` is set
(message parity not asserted). Cases with `"disabled": true` are skipped.
