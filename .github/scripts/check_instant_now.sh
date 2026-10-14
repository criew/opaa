#!/usr/bin/env bash
# Ratchet: Spring beans take the time from the injected Clock (common.ClockConfiguration), so tests
# can fix it. Counts Instant.now() without argument in code lines of backend/src/main/java files
# annotated @Service, @Component, @Configuration, @RestController or @Controller; entities
# (@Entity) and records stamp themselves and are not counted (ADR-0051, Entscheidung 2 und 6).
# The count must not exceed LIMIT; once it is lower, LIMIT is lowered to it.
set -euo pipefail

LIMIT=0
limit="${OPAA_RATCHET_LIMIT:-$LIMIT}"

cd "$(git rev-parse --show-toplevel)"

BEAN='^\s*@(Service|Component|Configuration|RestController|Controller)\b'
EXEMPT='^\s*@Entity\b|^(public\s+)?(final\s+)?record\s'
# A code line calling Instant.now() without argument; comment lines do not count.
CALL='^(?!\s*(//|/?\*)).*Instant\.now\(\)'

hits=""
while IFS= read -r -d '' file; do
  grep -qP "$BEAN" "$file" || continue
  ! grep -qP "$EXEMPT" "$file" || continue
  n="$(grep -P "$CALL" "$file" | grep -oF 'Instant.now()' | wc -l || true)"
  [ "$n" -eq 0 ] || hits+="$n $file"$'\n'
done < <(git ls-files -z -- 'backend/src/main/java/*.java')

count="$(awk '{ s += $1 } END { print s + 0 }' <<<"$hits")"

if [ "$count" -gt "$limit" ]; then
  echo "::error::$count calls of Instant.now() in Spring beans, the limit is $limit. Inject the Clock and call Instant.now(clock)."
  sed '/^$/d' <<<"$hits" | sort -k2
  exit 1
fi
if [ "$count" -lt "$limit" ]; then
  echo "::notice::$count calls of Instant.now() in Spring beans, below the limit of $limit: lower the limit to $count in .github/scripts/check_instant_now.sh."
  exit 0
fi
echo "$count calls of Instant.now() in Spring beans, at the limit of $limit."
