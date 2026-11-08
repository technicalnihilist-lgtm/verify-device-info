#!/usr/bin/env bash
# Fails if a change touches the UI surface.
#
# The 1-20 November campaign allows internal quality work only: no new screens, controls, styling,
# spacing, colours or user-visible copy. Every screen, control, style, string and drawable in this
# app lives under the two paths below, so "the UI did not change" is exactly "no file under them
# differs from the reference" — apart from the handful of files listed in NON_RENDERING_UI_FILES,
# which live under `ui/` but hold no rendering, and which this script proves render nothing before
# it lets them through.
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

# Files that live under a UI path but draw nothing: the view model holds state and orchestration and
# has no rendering in it at all. A file is listed here only if the check below can prove that about
# it — no `@Composable`, no Compose import, no resource reference — so the guarantee this script
# exists for ("no file that renders differs") still holds, and the list cannot quietly grow into a
# hole. Adding an entry that renders fails the run.
NON_RENDERING_UI_FILES=(
  "app/src/main/kotlin/com/verifydeviceinfo/ui/AppViewModel.kt"
)

renders() { # renders <path> — true if the file can draw anything
  [ -f "$1" ] || return 0
  grep -qE '@Composable|androidx\.compose|\bR\.[a-z]' "$1"
}

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

# Drop the declared non-rendering files, but only after proving each one still renders nothing.
for exempt in "${NON_RENDERING_UI_FILES[@]}"; do
  [ -n "$changed" ] || break
  case $'\n'"$changed"$'\n' in
    *$'\n'"$exempt"$'\n'*)
      if renders "$exempt"; then
        echo "check-no-ui-change: $exempt is exempt from the UI gate but no longer provably renders nothing" >&2
        echo "  (it matches @Composable, a Compose import, or a resource reference, or it is gone). Remove it from" >&2
        echo "  NON_RENDERING_UI_FILES, or move the work to a design pass." >&2
        exit 1
      fi
      changed=$(printf '%s\n' "$changed" | grep -vxF "$exempt" || true)
      ;;
  esac
done

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
