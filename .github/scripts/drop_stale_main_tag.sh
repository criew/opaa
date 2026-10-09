#!/usr/bin/env bash
# Tag filter of publish-images.yml: reads image tags (one per line) on stdin and writes them to
# stdout, minus <image>:main unless <commit> is still the tip of main on the remote "origin" of the
# current repository. An older or re-run main build thus never moves :main backwards; every other
# tag, sha-<commit> included, passes unchanged. Origin is only asked if <image>:main is in the list.
# Exit 1 if origin cannot tell where main is after three attempts (LS_REMOTE_RETRY_DELAY seconds
# apart, default 5) - nothing is tagged rather than a guess.
set -euo pipefail

if (($# != 2)); then
  echo "Usage: $0 <image> <commit> <tags" >&2
  exit 2
fi
image="$1"
commit="$2"
main_tag="$image:main"

tags="$(cat)"
if ! grep -qxF "$main_tag" <<<"$tags"; then
  [[ -z "$tags" ]] || printf '%s\n' "$tags"
  exit 0
fi

# A single network error must not cost the run all its tags; a lasting one still fails it.
attempts=3
delay="${LS_REMOTE_RETRY_DELAY:-5}"
for ((attempt = 1; ; attempt++)); do
  if remote="$(git ls-remote origin refs/heads/main)"; then
    break
  elif ((attempt == attempts)); then
    echo "::error::Stand von main ließ sich nach $attempts Versuchen nicht abfragen (git ls-remote origin); $main_tag bleibt unberührt, nichts getaggt" >&2
    exit 1
  fi
  echo "Abfrage von main gescheitert (Versuch $attempt von $attempts), neuer Versuch in ${delay}s" >&2
  sleep "$delay"
done
# ls-remote matches the pattern against the end of a ref name; only the exact ref counts.
head="$(awk '$2 == "refs/heads/main" { print $1 }' <<<"$remote")"
if ! [[ "$head" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]]; then
  echo "::error::Kein eindeutiger Stand von main in der Antwort von origin: '$remote'" >&2
  exit 1
fi

if [[ "$head" == "$commit" ]]; then
  printf '%s\n' "$tags"
  exit 0
fi
echo "::notice::$commit ist nicht mehr der Stand von main ($head); $main_tag wird nicht gesetzt, die übrigen Tags schon (docs/releases.md)" >&2
grep -vxF "$main_tag" <<<"$tags" || true
