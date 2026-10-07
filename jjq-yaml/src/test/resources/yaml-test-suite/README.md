# Vendored snapshot of https://github.com/yaml/yaml-test-suite (MIT license,
# see https://github.com/yaml/yaml-test-suite) for YamlConformanceTest.
#
# Source: `data-2022-01-17` data release (latest sanctioned data release;
# the `data` branch carries unreleased commits that may be wrong).
# Pinned commit: 6e6c296ae9c9d2d5c4134b4b64d01b29ac19ff6f
# ("Regenerated data from main v2022-01-17").
#
# Only the files the harness reads are vendored per test directory:
# `===` (name), `in.yaml` (input), `in.json` (expected tree),
# `error` (must-fail marker), plus `out.yaml`/`test.event` for reference.
# `in.yaml` files are kept VERBATIM (including special placeholder
# characters); the harness decodes them at runtime.
#
# Refresh (from the repo root):
#   rm -rf /tmp/yts && git clone --branch <data-YYYY-MM-DD> --depth 1 \
#     https://github.com/yaml/yaml-test-suite /tmp/yts
#   rm -rf jjq-yaml/src/test/resources/yaml-test-suite/*/
#   (cd /tmp/yts && for d in $(find . -name in.yaml -exec dirname {} \;); do
#      mkdir -p "jjq-yaml/src/test/resources/yaml-test-suite/$d"
#      for f in === in.yaml in.json out.yaml test.event error emit.yaml; do
#        [ -f "$d/$f" ] && cp "$d/$f" "jjq-yaml/src/test/resources/yaml-test-suite/$d/$f"
#      done; done)
#   cp /tmp/yts/License jjq-yaml/src/test/resources/yaml-test-suite/LICENSE
# Then update this file's pinned commit above and re-triage yaml-skips.txt.
