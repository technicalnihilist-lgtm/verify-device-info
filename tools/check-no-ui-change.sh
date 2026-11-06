#!/usr/bin/env bash
# Fails if a change touches the UI surface.
#
# The 1-20 November campaign allows internal quality work only: no new screens, controls, styling,
# spacing, colours or user-visible copy. Every screen, control, style, string and drawable in this
# app lives under the two paths below, so "the UI did not change" is exactly "no file under them
# differs from the reference".
#
#   tools/check-no-ui-change.sh                 # working tree against HEAD
#   tools/check-no-ui-change.sh origin/main     # this branch against another ref
#   UI_BASELINE=<sha> tools/check-no-ui-change.sh
#
# Exit status: 0 = unchanged, 1 = the UI surface changed, 2 = cannot compare (not a git checkout,
# unknown reference).
set -uo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd) || exit 2
BASE=${1:-${UI_BASELINE:-HEAD}}

UI_PATHS=(
  "app/src/main/kotlin/com/verifydeviceinfo/ui"
  "app/src/main/res"
)

cd "$ROOT" || exit 2

if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "check-no-ui-change: not a git checkout, nothing to compare" >&2
  exit 2
fi

if ! git rev-parse --verify --quiet "${BASE}^{commit}" >/dev/null 2>&1; then
  echo "check-no-ui-change: unknown reference '${BASE}'" >&2
  exit 2
fi

# Tracked changes (staged and unstaged) against the reference, plus files that are not tracked yet
# but would land under the UI surface on the next commit.
changed=$(
  {
    git diff --name-only "$BASE" -- "${UI_PATHS[@]}"
    git ls-files --others --exclude-standard -- "${UI_PATHS[@]}"
  } | sort -u
)

if [ -n "$changed" ]; then
  echo "check-no-ui-change: the UI surface changed against ${BASE}:" >&2
  printf '%s\n' "$changed" | sed 's/^/  /' >&2
  echo >&2
  echo "This period allows internal quality work only — no new screens, controls, styling, spacing," >&2
  echo "colours or user-visible copy. Revert these files, or move the work to a design pass." >&2
  exit 1
fi

tracked=$(git ls-tree -r --name-only "$BASE" -- "${UI_PATHS[@]}" | wc -l | tr -d ' ')
echo "check-no-ui-change: OK — nothing under ui/ or res/ differs from ${BASE} (${tracked} files tracked there)."
exit 0
