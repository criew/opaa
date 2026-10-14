#!/usr/bin/env bash
# Ratchet: configuration is bound through @ConfigurationProperties types; @Value( stays for a value
# without such a type. Counts @Value( in code lines of backend/src/main/java files whose class name
# does not end in Properties (ADR-0051, Entscheidung 2 und 6).
# The count must not exceed LIMIT; once it is lower, LIMIT is lowered to it.
set -euo pipefail

LIMIT=4
limit="${OPAA_RATCHET_LIMIT:-$LIMIT}"

cd "$(git rev-parse --show-toplevel)"

# A code line using @Value(; comment lines do not count.
USE='^(?!\s*(//|/?\*)).*@Value\('

hits=""
while IFS= read -r -d '' file; do
  case "$file" in *Properties.java) continue ;; esac
  n="$(grep -P "$USE" "$file" | grep -oF '@Value(' | wc -l || true)"
  [ "$n" -eq 0 ] || hits+="$n $file"$'\n'
done < <(git ls-files -z -- 'backend/src/main/java/*.java')

count="$(awk '{ s += $1 } END { print s + 0 }' <<<"$hits")"

if [ "$count" -gt "$limit" ]; then
  echo "::error::$count uses of @Value outside *Properties classes, the limit is $limit. Bind the value through a @ConfigurationProperties type."
  sed '/^$/d' <<<"$hits" | sort -k2
  exit 1
fi
if [ "$count" -lt "$limit" ]; then
  echo "::notice::$count uses of @Value outside *Properties classes, below the limit of $limit: lower the limit to $count in .github/scripts/check_value_annotations.sh."
  exit 0
fi
echo "$count uses of @Value outside *Properties classes, at the limit of $limit."
