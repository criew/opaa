#!/usr/bin/env bash
# The offline bundle of a release (docs/releases.md, "Offline-Bündel"): everything a network
# without internet access needs to verify and install OPAA, in one archive.
#
#   collect <version> <dir>     images as OCI archives (both architectures, index digests as in
#                               GHCR), chart, update package, SBOMs, their Sigstore bundles and
#                               LIESMICH.md; needs skopeo, helm and gh with network access
#   sums <dir>                  SHA256SUMS over every file of <dir>; its attestation covers the
#                               whole bundle
#   pack <dir> <out>            <out>/<name of dir>.tar and .sha256
#   split <tar>                 above the size limit of a release asset: replaces <tar> by the
#                               parts <tar>.part-00, -01, ... of at most that size, which cat joins
#                               again; <tar>.sha256 keeps naming the whole archive
#   join <tar>                  <tar> from its parts, if only the parts exist
#   check <tar> <trusted-root>  the verification of the handbook, without network, then two
#                               manipulations that must be detected; joins the parts first
#   chart-digests <dir>         the image digests the chart's values pin are those in DIGESTS; a
#                               chart without digests installs by tag and passes (part of check)
#
# collect reads IMAGE_PREFIX, CHART_REPOSITORY, GH_REPO and UPDATE_PACKAGE_DIR (update package and
# SBOMs of the version); a missing SBOM fails it unless REQUIRE_SBOMS is false, a missing image
# attestation unless REQUIRE_IMAGE_ATTESTATIONS is false, and with REQUIRE_SBOMS false an OCR image
# that the version does not have is skipped. check reads GH_REPO
# and the expected signers: BUNDLE_SIGNER_WORKFLOW and BUNDLE_SOURCE_REF for SHA256SUMS,
# RELEASE_SIGNER_WORKFLOW and RELEASE_SOURCE_REF for the update package.
set -euo pipefail

# A release asset may not exceed 2 GiB; each part of the bundle stays a margin below.
MAX_BYTES="${OFFLINE_BUNDLE_MAX_BYTES:-2040109465}"
HERE="$(cd "$(dirname "$0")" && pwd)"

usage() {
  sed -n '2,27p' "$0" >&2
  exit 2
}

need() {
  local tool
  for tool in "$@"; do
    command -v "$tool" >/dev/null || { echo "$tool is missing" >&2; exit 1; }
  done
}

raw_digest() {
  skopeo inspect --raw "$1" | sha256sum | cut -d' ' -f1
}

# gh attestation download writes <algorithm>-<digest>.jsonl into the working directory. With a
# third argument "optional", a subject without attestation is reported and skipped.
attestation() {
  local subject="$1" target="$2" mode="${3:-required}" work found
  work="$(mktemp -d)"
  if [[ "$subject" != oci://* ]]; then
    subject="$(cd "$(dirname "$subject")" && pwd)/$(basename "$subject")"
  fi
  (cd "$work" && gh attestation download "$subject" --repo "$GH_REPO" >&2) || [[ "$mode" == optional ]]
  found="$(find "$work" -name '*.jsonl' -size +0 | head -n 1)"
  if [[ -z "$found" && "$mode" == optional ]]; then
    echo "::warning::No attestation for $subject; the bundle carries none for it" >&2
    rm -rf "$work"
    return 0
  elif [[ -z "$found" ]]; then
    echo "No attestation for $subject" >&2
    exit 1
  fi
  mv "$found" "$target"
  rm -rf "$work"
}

collect() {
  local version="$1" dir="$2" name source archive published copied images=()
  need skopeo helm gh sha256sum
  : "${IMAGE_PREFIX:?}" "${CHART_REPOSITORY:?}" "${GH_REPO:?}" "${UPDATE_PACKAGE_DIR:?}"
  mkdir -p "$dir/images" "$dir/chart" "$dir/update-paket" "$dir/sbom" "$dir/attestations"
  : >"$dir/DIGESTS"
  for name in backend frontend ocr; do
    source="docker://$IMAGE_PREFIX-$name:$version"
    # Releases before the OCR image have none; a release run always requires it.
    if [[ "$name" == ocr && "${REQUIRE_SBOMS:-true}" != true ]] &&
      ! skopeo inspect --raw "$source" >/dev/null 2>&1; then
      echo "::warning::$IMAGE_PREFIX-$name:$version does not exist; the bundle carries no OCR image" >&2
      continue
    fi
    images+=("$name")
    archive="$dir/images/opaa-$name-$version.oci.tar"
    skopeo copy --all --preserve-digests --quiet "$source" "oci-archive:$archive:$version"
    published="$(raw_digest "$source")"
    copied="$(raw_digest "oci-archive:$archive")"
    if [[ "$published" != "$copied" ]]; then
      echo "Index digest of $archive is $copied, the registry has $published" >&2
      exit 1
    fi
    echo "$IMAGE_PREFIX-$name:$version@sha256:$published" >>"$dir/DIGESTS"
  done

  helm pull "oci://$CHART_REPOSITORY/opaa" --version "$version" --destination "$dir/chart" >&2

  local package="$UPDATE_PACKAGE_DIR/opaa-update-paket-$version.tar.gz" sbom
  [[ -f "$package" ]] || { echo "$package is missing" >&2; exit 1; }
  cp "$package" "$dir/update-paket/"
  for name in backend frontend ocr; do
    sbom="$UPDATE_PACKAGE_DIR/opaa-$name-$version.cdx.json"
    if [[ -f "$sbom" ]]; then
      cp "$sbom" "$dir/sbom/"
    elif [[ "${REQUIRE_SBOMS:-true}" == true ]]; then
      echo "$sbom is missing" >&2
      exit 1
    else
      echo "::warning::$(basename "$sbom") is missing; the bundle carries no SBOM for $name" >&2
    fi
  done

  # The Sigstore bundles of what the release workflow attested for these files and the chart.
  local subject
  for subject in "$dir"/update-paket/* "$dir"/sbom/*; do
    [[ -f "$subject" ]] || continue
    attestation "$subject" "$dir/attestations/$(basename "$subject").jsonl"
  done
  attestation "oci://$CHART_REPOSITORY/opaa:$version" "$dir/attestations/chart-opaa-$version.jsonl"
  local mode=required
  if [[ "${REQUIRE_IMAGE_ATTESTATIONS:-true}" != true ]]; then
    mode=optional
  fi
  for name in "${images[@]}"; do
    attestation "oci://$IMAGE_PREFIX-$name:$version" "$dir/attestations/opaa-$name-$version.jsonl" "$mode"
  done

  sed -e "s/@VERSION@/$version/g" -e "s#@REPO@#$GH_REPO#g" \
    "$HERE/offline_bundle_readme.md" >"$dir/LIESMICH.md"
}

# Compares the digest under backend.image and frontend.image of the chart's values with DIGESTS.
chart_digests() {
  local dir="$1" values name pinned line
  need helm
  values="$(helm show values "$dir"/chart/*.tgz)"
  for name in backend frontend; do
    pinned="$(awk -v name="$name" '
      /^[^ #]/ { top = $0; sub(/:.*/, "", top); image = 0 }
      /^  [^ #]/ { image = ($0 ~ /^  image:[ ]*$/) }
      image && top == name && /^    digest:/ { value = $2; gsub(/"/, "", value); print value; exit }
    ' <<<"$values")"
    line="$(grep -F -- "-$name:" "$dir/DIGESTS" || true)"
    if [[ -z "$pinned" ]]; then
      echo "ok   the chart pins no digest for $name and installs it by tag"
    elif [[ -z "$line" || "$pinned" != "sha256:${line##*@sha256:}" ]]; then
      echo "The chart pins $name to $pinned, DIGESTS names '$line'" >&2
      exit 1
    else
      echo "ok   the chart pins $name to $pinned, as in DIGESTS"
    fi
  done
}

sums() {
  local dir="$1"
  need sha256sum
  (cd "$dir" && find . -type f ! -name SHA256SUMS ! -name 'SHA256SUMS.*' -printf '%P\n' |
    LC_ALL=C sort | xargs -r -d '\n' sha256sum >SHA256SUMS)
}

pack() {
  local dir="$1" out="$2" name
  dir="$(cd "$dir" && pwd)"
  name="$(basename "$dir")"
  mkdir -p "$out"
  tar --force-local -C "$(dirname "$dir")" --sort=name --owner=0 --group=0 --numeric-owner \
    -cf "$out/$name.tar" "$name"
  (cd "$out" && sha256sum "$name.tar" >"$name.tar.sha256")
  echo "$out/$name.tar"
}

split_archive() {
  local tar="$1" size
  size="$(stat -c %s "$tar")"
  if ((size <= MAX_BYTES)); then
    echo "$(basename "$tar") has $size bytes and stays whole"
    return 0
  fi
  split --bytes="$MAX_BYTES" --numeric-suffixes --suffix-length=2 "$tar" "$tar.part-"
  rm -f "$tar"
  echo "$(basename "$tar") has $size bytes, split into parts of at most $MAX_BYTES:"
  ls -l "$tar".part-*
}

join_archive() {
  local tar="$1" parts
  if [[ -f "$tar" ]]; then
    return 0
  fi
  parts="$(compgen -G "$tar.part-*" || true)"
  if [[ -z "$parts" ]]; then
    echo "neither $tar nor its parts exist" >&2
    exit 1
  fi
  # The glob sorts .part-00, -01, ... in the order split wrote them.
  cat "$tar".part-* >"$tar"
  rm -f "$tar".part-*
  echo "joined $(basename "$tar") from its parts"
}

verify_attestation() {
  local file="$1" bundle="$2" root="$3" signer="$4" ref="$5"
  gh attestation verify "$file" --bundle "$bundle" --custom-trusted-root "$root" \
    --repo "$GH_REPO" --signer-workflow "$signer" --source-ref "$ref" >/dev/null
}

check() {
  local tar="$1" root work name dir
  root="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
  need gh skopeo helm sha256sum
  : "${GH_REPO:?}" "${BUNDLE_SIGNER_WORKFLOW:?}" "${BUNDLE_SOURCE_REF:?}"
  : "${RELEASE_SIGNER_WORKFLOW:?}" "${RELEASE_SOURCE_REF:?}"
  work="$(mktemp -d)"
  name="$(basename "$tar" .tar)"
  join_archive "$tar"
  (cd "$(dirname "$tar")" && sha256sum -c --quiet "$name.tar.sha256")
  verify_attestation "$tar" "$tar.sigstore.json" "$root" \
    "$BUNDLE_SIGNER_WORKFLOW" "$BUNDLE_SOURCE_REF"
  echo "ok   the archive carries the attestation of $BUNDLE_SIGNER_WORKFLOW at $BUNDLE_SOURCE_REF"
  tar --force-local -C "$work" -xf "$tar"
  dir="$work/$name"

  verify_attestation "$dir/SHA256SUMS" "$dir/SHA256SUMS.sigstore.json" "$root" \
    "$BUNDLE_SIGNER_WORKFLOW" "$BUNDLE_SOURCE_REF"
  echo "ok   SHA256SUMS carries the attestation of $BUNDLE_SIGNER_WORKFLOW at $BUNDLE_SOURCE_REF"
  (cd "$dir" && sha256sum -c --quiet SHA256SUMS)
  echo "ok   every file matches SHA256SUMS"
  local package
  for package in "$dir"/update-paket/*.tar.gz; do
    verify_attestation "$package" "$dir/attestations/$(basename "$package").jsonl" "$root" \
      "$RELEASE_SIGNER_WORKFLOW" "$RELEASE_SOURCE_REF"
    echo "ok   $(basename "$package") carries the attestation of the release"
  done
  local line image digest archive
  while IFS= read -r line; do
    image="${line%@sha256:*}"
    digest="${line##*@sha256:}"
    archive="$dir/images/$(basename "${image%:*}")-${image##*:}.oci.tar"
    if [[ "$(raw_digest "oci-archive:$archive")" != "$digest" ]]; then
      echo "$archive does not hold $line" >&2
      exit 1
    fi
    echo "ok   $(basename "$archive") holds the index sha256:$digest"
  done <"$dir/DIGESTS"
  helm show chart "$dir"/chart/*.tgz >/dev/null
  echo "ok   the chart is readable by helm"
  chart_digests "$dir"

  local forged="$work/forged"
  cp -r "$dir" "$forged"
  archive="$(find "$forged/images" -name '*backend*.oci.tar')"
  printf 'X' | dd of="$archive" bs=1 seek=4096 conv=notrunc status=none
  if (cd "$forged" && sha256sum -c --quiet SHA256SUMS >/dev/null 2>&1); then
    echo "an image archive with one changed byte passed SHA256SUMS" >&2
    exit 1
  fi
  echo "ok   an image archive with one changed byte fails SHA256SUMS"
  sums "$forged"
  if verify_attestation "$forged/SHA256SUMS" "$forged/SHA256SUMS.sigstore.json" "$root" \
    "$BUNDLE_SIGNER_WORKFLOW" "$BUNDLE_SOURCE_REF" 2>/dev/null; then
    echo "a SHA256SUMS adjusted to the changed archive passed the attestation" >&2
    exit 1
  fi
  echo "ok   a SHA256SUMS adjusted to the changed archive fails the attestation"
  rm -rf "$work"
}

(($# >= 1)) || usage
command="$1"
shift
case "$command" in
collect) (($# == 2)) || usage; collect "$@" ;;
sums) (($# == 1)) || usage; sums "$@" ;;
pack) (($# == 2)) || usage; pack "$@" ;;
split) (($# == 1)) || usage; split_archive "$@" ;;
join) (($# == 1)) || usage; join_archive "$@" ;;
check) (($# == 2)) || usage; check "$@" ;;
chart-digests) (($# == 1)) || usage; chart_digests "$@" ;;
*) usage ;;
esac
