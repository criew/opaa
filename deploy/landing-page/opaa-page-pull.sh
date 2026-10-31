#!/usr/bin/env bash
# Mirrors the public branch gh-pages into the web root of opaa.io.
#
# Pull only: needs neither credentials nor any access from GitHub to this host. An unchanged
# branch leaves the web root untouched; a failed fetch keeps the last deployed state online.
# Run by opaa-page-pull.service as the unprivileged user opaa-page.
set -euo pipefail

repo_url="${OPAA_PAGE_REPO_URL:-https://github.com/criew/opaa.git}"
branch="${OPAA_PAGE_BRANCH:-gh-pages}"
cache_dir="${OPAA_PAGE_CACHE_DIR:-/var/cache/opaa-page}"
web_root="${OPAA_PAGE_WEB_ROOT:-/var/www/opaa.io}"

if [ ! -d "$cache_dir/.git" ]; then
  git init -q "$cache_dir"
  git -C "$cache_dir" remote add origin "$repo_url"
fi

git -C "$cache_dir" fetch -q --depth 1 origin "$branch"
fetched="$(git -C "$cache_dir" rev-parse FETCH_HEAD)"
state_file="$cache_dir/.git/opaa-deployed"
deployed="$(cat "$state_file" 2>/dev/null || true)"

if [ "$fetched" = "$deployed" ]; then
  exit 0
fi

git -C "$cache_dir" checkout -q --force --detach "$fetched"
git -C "$cache_dir" clean -q -f -d -x

# .nojekyll only matters to GitHub Pages; everything else is published as is.
rsync -a --delete --exclude '.git/' --exclude '.nojekyll' "$cache_dir/" "$web_root/"
printf '%s\n' "$fetched" > "$state_file"

# A shallow fetch per change piles up unreachable objects; drop them right away.
git -C "$cache_dir" reflog expire --expire=now --all
git -C "$cache_dir" gc -q --prune=now

echo "opaa-page: deployed $fetched"
