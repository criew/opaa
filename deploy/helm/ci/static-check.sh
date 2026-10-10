#!/usr/bin/env bash
# Static check of the OPAA chart, run by .github/workflows/helm-chart.yml and locally from the
# repository root. Lints and renders the chart with every value set in <chart>/ci/, validates the
# rendered manifests against each supported Kubernetes version and asserts that the chart's guards
# still refuse the misconfigurations they exist for, and dry-runs the release scripts of
# publish-images.yml, including the release notes. Needs helm and kubeconform on the PATH, the dry
# run also curl and jq.
set -euo pipefail

CHART_DIR="${CHART_DIR:-deploy/helm/opaa}"
# The three youngest Kubernetes minor versions maintained upstream (ADR-0042, Entscheidung 8);
# moved up by hand with each new Kubernetes minor release.
KUBERNETES_VERSIONS="${KUBERNETES_VERSIONS:-1.35.0 1.36.0 1.37.0}"
# Schemas of custom resources such as the Gateway API HTTPRoute, pinned to one catalog commit so a
# catalog change cannot turn the check red without a change in this repository.
CRD_SCHEMA_LOCATION='https://raw.githubusercontent.com/datreeio/CRDs-catalog/63669a570e231d4f1f8396d229a1de512bcf0a34/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
mkdir -p "$work_dir/schemas"

shopt -s nullglob
value_sets=("$CHART_DIR"/ci/*-values.yaml)
if ((${#value_sets[@]} == 0)); then
  echo "No value sets found in $CHART_DIR/ci/" >&2
  exit 1
fi

echo "$(helm version --short), kubeconform $(kubeconform -v)"

for values in "${value_sets[@]}"; do
  name="$(basename "$values" -values.yaml)"
  echo "--- value set $name"
  helm lint --strict "$CHART_DIR" --values "$values"
  # helm lint reports a refusal of the chart (fail) only as INFO and exits 0; rendering fails on it.
  if ! helm template opaa "$CHART_DIR" --namespace opaa --values "$values" >"$work_dir/$name.yaml"; then
    echo "Value set $name does not render: the chart refuses it (message above)." >&2
    exit 1
  fi
  for version in $KUBERNETES_VERSIONS; do
    kubeconform -strict -summary -kubernetes-version "$version" -cache "$work_dir/schemas" \
      -schema-location default -schema-location "$CRD_SCHEMA_LOCATION" "$work_dir/$name.yaml"
  done
done

# Renders the minimal value set with extra arguments and fails unless Helm refuses it with a message
# containing the expected text.
expect_refusal() {
  local expected="$1"
  shift
  local output
  if output="$(helm template opaa "$CHART_DIR" --values "$CHART_DIR/ci/minimal-values.yaml" "$@" 2>&1)"; then
    echo "Expected the chart to refuse '$*', but it rendered." >&2
    return 1
  fi
  if [[ "$output" != *"$expected"* ]]; then
    echo "The chart refused '$*', but without naming '$expected':" >&2
    echo "$output" >&2
    return 1
  fi
  echo "refused as expected: $*"
}

echo "--- guards"
expect_refusal "ADR-0021" --set backend.replicas=2
expect_refusal "ADR-0005" --set 'backend.extraEnv[0].name=SPRING_PROFILES_ACTIVE' --set 'backend.extraEnv[0].value=dev'
expect_refusal "jwtSecret" --set secrets.jwtSecret=changeme-0123456789abcdefghijklmnopqrstuv
expect_refusal "backendReadTimeout" --set frontend.backendReadTimeout=600
# CSP keywords such as 'self' are quoted; quotes would break the nginx configuration.
expect_refusal "cspConnectSrcExtra" --set-string "frontend.cspConnectSrcExtra='self' https://login.example.org"

# On the first start the image's entrypoint runs a temporary server for initdb that answers on the
# socket only; a readiness probe over the socket reports ready while the service refuses TCP.
echo "--- evaluation database readiness"
probe="$(helm template opaa "$CHART_DIR" --values "$CHART_DIR/ci/evaluation-values.yaml" \
  --show-only templates/evaluation-database.yaml | sed -n '/readinessProbe:/,/periodSeconds:/p' |
  tr -s ' \n' ' ')"
if [[ "$probe" != *"- pg_isready - -h - 127.0.0.1 "* ]]; then
  echo "The readiness probe of the evaluation database must run pg_isready -h 127.0.0.1:" >&2
  echo "$probe" >&2
  exit 1
fi
echo "probes over TCP as expected"

# The release packaging of publish-images.yml without the push: a release and a pre-release tag are
# packaged, malformed tags and versions Helm does not accept as SemVer are refused. The tag format
# itself comes from release-version.sh, which the image release guard calls as well.
echo "--- release packaging"
for tag in v1.2.3 v1.2.3-rc.1; do
  version="$(deploy/helm/ci/release-version.sh "$tag")"
  if [[ "$version" != "${tag#v}" ]]; then
    echo "release-version.sh turned $tag into '$version' instead of '${tag#v}'" >&2
    exit 1
  fi
done
for tag in v1.2.3 v1.2.3-rc.1; do
  CHART_DIR="$CHART_DIR" deploy/helm/ci/package-chart.sh "$tag" "$work_dir/packages" >/dev/null
  echo "packaged as expected: $tag"
done
expect_tag_refusal() {
  local tag="$1" expected="$2" output
  if output="$(CHART_DIR="$CHART_DIR" deploy/helm/ci/package-chart.sh "$tag" "$work_dir/packages" 2>&1)"; then
    echo "Expected release packaging to refuse tag $tag, but it packaged." >&2
    return 1
  fi
  if ! grep -qi -- "$expected" <<<"$output"; then
    echo "Release packaging refused tag $tag, but without naming '$expected':" >&2
    echo "$output" >&2
    return 1
  fi
  echo "refused as expected: $tag"
}
for tag in 1.2.3 v1.2 v1.2.3+build.1 v01.2.3; do
  expect_tag_refusal "$tag" "is not a release version"
done
expect_tag_refusal v1.0.0-rc.01 "segment starts with 0"

# The installation in the release notes creates the namespace with both Pod Security labels of the
# handbook before helm install; --create-namespace would create it without them.
echo "--- release notes"
mkdir -p "$work_dir/upgrade-notes"
release_notes="$(UPGRADE_NOTES_DIR="$work_dir/upgrade-notes" deploy/helm/ci/release-notes.sh criew/opaa v1.2.3-rc.1)"
for expected in "kubectl create namespace opaa" "pod-security.kubernetes.io/enforce=restricted" \
  "pod-security.kubernetes.io/warn=restricted" "--version 1.2.3-rc.1" \
  "blob/v1.2.3-rc.1/docs/handbuch/kubernetes.md#installation" \
  "**Vorbereitung für Bestandsinstallationen**" "Keine Vorbereitung nötig." \
  "blob/v1.2.3-rc.1/docs/handbuch/kubernetes.md#aktualisierung-und-rückweg" \
  "blob/v1.2.3-rc.1/docs/handbuch/deployment.md#vorbereitungsschritte-für-bestandsinstallationen"; do
  if [[ "$release_notes" != *"$expected"* ]]; then
    echo "The release notes do not contain '$expected':" >&2
    echo "$release_notes" >&2
    exit 1
  fi
done
if [[ "$release_notes" == *"--create-namespace"* ]]; then
  echo "The release notes create the namespace with --create-namespace, without the Pod Security labels." >&2
  exit 1
fi
for label in enforce warn; do
  if [[ "${release_notes%%helm install*}" != *"pod-security.kubernetes.io/${label}=restricted"* ]]; then
    echo "The release notes must set the label ${label}=restricted before helm install." >&2
    exit 1
  fi
done
echo "namespace labelled before helm install as expected"
# Every handbook link of the notes points to a heading of this commit, by GitHub's anchor rules.
# The anchors go to a file first: grep -q in the pipeline would exit early, and under pipefail the
# SIGPIPE of jq would turn a found anchor into a failure.
heading_anchors() {
  awk '/^[[:space:]]*```/ { block = !block; next } !block && /^#+ / { sub(/^#+ +/, ""); print }' "$1" |
    jq -Rr 'gsub("Ä";"ä") | gsub("Ö";"ö") | gsub("Ü";"ü") | ascii_downcase | gsub("[^\\p{L}\\p{N} _-]";"") | gsub(" ";"-")'
}
links="$(grep -o 'blob/v1\.2\.3-rc\.1/docs/handbuch/[a-z0-9-]*\.md#[^)]*' <<<"$release_notes")"
while read -r link; do
  file="${link%%#*}"
  file="docs/handbuch/${file##*/}"
  heading_anchors "$file" >"$work_dir/anchors"
  if ! grep -qxF -- "${link#*#}" "$work_dir/anchors"; then
    echo "The release notes link $link, but $file has no heading with this anchor." >&2
    exit 1
  fi
done <<<"$links"
echo "handbook links resolve as expected: $(wc -l <<<"$links" | tr -d ' ')"
# A file docs/upgrade-notes/<version>.md replaces "Keine Vorbereitung nötig." with its text. A
# release X.Y.0 needs the file, since only it can bring a break; an empty or symlinked file and one
# with an unclosed code fence are refused, the rest of the notes would otherwise go missing.
upgrade_notes() {
  UPGRADE_NOTES_DIR="$work_dir/upgrade-notes" deploy/helm/ci/release-notes.sh criew/opaa "$1"
}
# Fails unless the release notes of the tag are refused with a message naming the expected text.
expect_notes_refusal() {
  local tag="$1" expected="$2" case="$3" output
  if output="$(upgrade_notes "$tag" 2>&1)" || [[ "$output" != *"$expected"* ]]; then
    echo "Expected the release notes of $tag to be refused naming '$expected' ($case):" >&2
    echo "$output" >&2
    exit 1
  fi
  echo "refused as expected: $case"
}
printf '%s\n' '- **Kubernetes:** `$(id)` ${HOME} vor dem Update setzen.' >"$work_dir/upgrade-notes/1.2.3-rc.1.md"
release_notes="$(upgrade_notes v1.2.3-rc.1)"
if [[ "$release_notes" != *'`$(id)` ${HOME} vor dem Update setzen.'* || "$release_notes" == *"Keine Vorbereitung nötig."* ]]; then
  echo "The release notes do not show the preparation of upgrade-notes/1.2.3-rc.1.md verbatim:" >&2
  echo "$release_notes" >&2
  exit 1
fi
echo "preparation taken from the upgrade notes as expected"
printf ' \n\n' >"$work_dir/upgrade-notes/1.2.3-rc.1.md"
expect_notes_refusal v1.2.3-rc.1 "must be a file naming the preparation steps" "empty upgrade notes"
printf '%s\n' '```bash' 'kubectl get pods' >"$work_dir/upgrade-notes/1.2.3-rc.1.md"
expect_notes_refusal v1.2.3-rc.1 "unclosed code fence" "unclosed code fence"
rm "$work_dir/upgrade-notes/1.2.3-rc.1.md"
printf '%s\n' '- Schritt' >"$work_dir/elsewhere.md"
ln -s "$work_dir/elsewhere.md" "$work_dir/upgrade-notes/1.2.3-rc.1.md"
expect_notes_refusal v1.2.3-rc.1 "must not be a symbolic link" "symlinked upgrade notes"
expect_notes_refusal v1.3.0 "1.3.0.md is missing" "missing upgrade notes for v1.3.0"
printf '%s\n' 'Keine Vorbereitung nötig.' >"$work_dir/upgrade-notes/1.3.0.md"
if [[ "$(upgrade_notes v1.3.0)" != *"Keine Vorbereitung nötig."* ]]; then
  echo "The release notes of v1.3.0 do not take the text of upgrade-notes/1.3.0.md." >&2
  exit 1
fi
echo "as expected: v1.3.0 with upgrade notes"
if [[ "$(upgrade_notes v1.2.4)" != *"Keine Vorbereitung nötig."* ]]; then
  echo "The release notes of the patch release v1.2.4 without upgrade notes must say 'Keine Vorbereitung nötig.'" >&2
  exit 1
fi
echo "as expected: v1.2.4 without upgrade notes"
# Each file in docs/upgrade-notes is named after a release version and yields release notes.
for file in docs/upgrade-notes/*; do
  name="$(basename "$file")"
  if [[ "$name" != *.md ]] || ! deploy/helm/ci/release-notes.sh criew/opaa "v${name%.md}" >/dev/null; then
    echo "$file must be named <version>.md after a release version X.Y.Z[-<pre>] and name the preparation steps." >&2
    exit 1
  fi
  echo "upgrade notes as expected: $file"
done
# The same holds for the installation commands of the chart README and docs/releases.md; prose may
# name the flag, a fenced code block may not.
for doc in "$CHART_DIR/README.md" docs/releases.md; do
  if awk '/^[[:space:]]*```/ { block = !block; next } block && /--create-namespace/ { found = 1 } END { exit !found }' "$doc"; then
    echo "A code block in $doc creates the namespace with --create-namespace, without the Pod Security labels." >&2
    exit 1
  fi
done
echo "no --create-namespace in the code blocks of the chart README and docs/releases.md"

# The provenance check of a re-run in the chart job: the same chart packaged twice matches by
# content, a chart of another state under the same version does not.
echo "--- re-run provenance"
CHART_DIR="$CHART_DIR" deploy/helm/ci/package-chart.sh v1.2.3 "$work_dir/again" >/dev/null
deploy/helm/ci/chart-matches.sh "$work_dir/packages/opaa-1.2.3.tgz" "$work_dir/again/opaa-1.2.3.tgz"
echo "matches as expected: same chart packaged twice"
cp -R "$CHART_DIR" "$work_dir/other-chart"
echo "# another commit" >>"$work_dir/other-chart/values.yaml"
CHART_DIR="$work_dir/other-chart" deploy/helm/ci/package-chart.sh v1.2.3 "$work_dir/other" >/dev/null
# Runs chart-matches.sh and fails unless it exits with the expected code and names the expected text.
expect_match_exit() {
  local code="$1" expected="$2" output rc=0
  shift 2
  output="$(deploy/helm/ci/chart-matches.sh "$@" 2>&1)" || rc=$?
  if ((rc != code)) || ! grep -q -- "$expected" <<<"$output"; then
    echo "Expected chart-matches.sh to exit $code naming '$expected', got $rc:" >&2
    echo "$output" >&2
    return 1
  fi
}
expect_match_exit 1 "differs in content" "$work_dir/packages/opaa-1.2.3.tgz" "$work_dir/other/opaa-1.2.3.tgz"
echo "refused as expected: chart of another state (exit 1)"
expect_match_exit 2 "is not a file" "$work_dir/packages/opaa-1.2.3.tgz" "$work_dir/other/missing.tgz"
echo "refused as expected: missing package (exit 2)"

# The registry lookup of the release guards counts a registry without an answer as an error, never
# as a free version. Port 9 on the loopback interface is closed on the runners.
echo "--- registry lookup"
if output="$(REGISTRY_SCHEME=http deploy/helm/ci/oci-published.sh 127.0.0.1:9/criew/opaa-backend 1.2.3 2>&1)"; then
  echo "Expected oci-published.sh to fail without a registry, but it answered '$output'." >&2
  exit 1
fi
echo "refused as expected: no registry"
# Upper case is no valid OCI repository name; such a name is refused before any request.
rc=0
output="$(deploy/helm/ci/oci-published.sh ghcr.io/Criew/opaa-backend 1.2.3 2>&1)" || rc=$?
if ((rc != 2)) || ! grep -q "not a valid OCI repository name" <<<"$output"; then
  echo "Expected oci-published.sh to refuse an upper-case repository name with exit 2, got $rc: $output" >&2
  exit 1
fi
echo "refused as expected: invalid repository name"

# NOTES.txt tells a first installation from an upgrade. helm template skips NOTES.txt, so a copy of
# the chart renders it as the value of a YAML document; --is-upgrade sets .Release.IsUpgrade.
echo "--- notes on install and upgrade"
cp -R "$CHART_DIR" "$work_dir/notes-chart"
{
  printf '{{- define "opaa.notesCheck" }}'
  cat "$CHART_DIR/templates/NOTES.txt"
  printf '{{- end }}\nnotes: {{ include "opaa.notesCheck" . | toJson }}\n'
} >"$work_dir/notes-chart/templates/notes-check.yaml"
rendered_notes() {
  helm template opaa "$work_dir/notes-chart" --namespace opaa --values "$CHART_DIR/ci/minimal-values.yaml" \
    --show-only templates/notes-check.yaml "$@" | sed -n 's/^notes: //p' | jq -r .
}
# Checks that the notes of one kind of release contain one text and lack the other.
notes_case() {
  local kind="$1" expected="$2" unexpected="$3" output
  shift 3
  output="$(rendered_notes "$@")"
  if [[ "$output" != *"$expected"* || "$output" == *"$unexpected"* ]]; then
    echo "The notes of $kind must name '$expected' and not '$unexpected':" >&2
    echo "$output" >&2
    exit 1
  fi
  echo "notes as expected: $kind"
}
notes_case "an installation" "OPAA ist installiert" "OPAA ist aktualisiert"
notes_case "an installation" "Der erste Start migriert" "Der Start migriert"
notes_case "an upgrade" "OPAA ist aktualisiert" "OPAA ist installiert" --is-upgrade
notes_case "an upgrade" "Der Start migriert" "Der erste Start migriert" --is-upgrade

# The CSP warning of NOTES.txt follows the issuer, the authority of the sign-in flow. Only install
# renders NOTES.txt; a dry run without a cluster exists from Helm 3.13 on, older Helm skips this.
echo "--- notes"
if [[ "$(helm install --help)" == *"--dry-run string"* ]]; then
  # Renders the NOTES of the minimal value set with extra arguments.
  notes() {
    helm install opaa "$CHART_DIR" --dry-run=client --namespace opaa \
      --values "$CHART_DIR/ci/minimal-values.yaml" "$@" | sed -n '/^NOTES:/,$p'
  }
  # Checks the CSP warning for an issuer (publicBaseUrl https://opaa.example.org) and a value of
  # cspConnectSrcExtra; "warn" expects the warning naming the issuer's origin, "silent" neither
  # warning nor path note, "path" the note that a path admits the discovery document only.
  # Every case runs; the block fails at its end if any of them did not hold.
  path_note="erlaubt nur das Discovery-Dokument"
  csp_failures=0
  csp_case() {
    local expectation="$1" issuer="$2" extra="$3" reason="${4:-}" output origin
    if ! output="$(notes --set-string "bootstrap.oidc.issuerUri=$issuer" --set bootstrap.oidc.clientId=opaa \
      --set-string "frontend.cspConnectSrcExtra=$extra" 2>&1)"; then
      echo "Issuer $issuer with cspConnectSrcExtra '$extra' does not install:" >&2
      echo "$output" >&2
      csp_failures=$((csp_failures + 1))
      return
    fi
    origin="$(sed -E 's#^([a-z]+://[^/]+).*#\1#' <<<"$issuer")"
    if [[ "$expectation" == warn && "$output" != *"ANMELDUNG ÜBER $origin WIRD BLOCKIERT"* ]]; then
      echo "Expected a CSP warning for issuer $issuer with cspConnectSrcExtra '$extra', but the notes show none." >&2
      csp_failures=$((csp_failures + 1))
    elif [[ "$expectation" == warn && "$output" != *"$reason"* ]]; then
      echo "Expected the warning for issuer $issuer with cspConnectSrcExtra '$extra' to name '$reason':" >&2
      sed -n '/WIRD BLOCKIERT/,+3p' <<<"$output" >&2
      csp_failures=$((csp_failures + 1))
    elif [[ "$expectation" != warn && "$output" == *"WIRD BLOCKIERT"* ]]; then
      echo "Expected no CSP warning for issuer $issuer with cspConnectSrcExtra '$extra':" >&2
      sed -n '/WIRD BLOCKIERT/,+2p' <<<"$output" >&2
      csp_failures=$((csp_failures + 1))
    elif [[ "$expectation" == silent && "$output" == *"$path_note"* ]]; then
      echo "Expected no path note for issuer $issuer with cspConnectSrcExtra '$extra'." >&2
      csp_failures=$((csp_failures + 1))
    elif [[ "$expectation" == path && "$output" != *"$path_note"* ]]; then
      echo "Expected the note that a path admits the discovery document only, for issuer $issuer with cspConnectSrcExtra '$extra':" >&2
      sed -n '/ORIGIN$/,+2p' <<<"$output" >&2
      csp_failures=$((csp_failures + 1))
    else
      echo "${expectation} as expected: issuer $issuer, cspConnectSrcExtra '$extra'"
    fi
  }
  issuer=https://login.example.org/realms/opaa
  csp_case warn "$issuer" ""
  csp_case silent "$issuer" "https://login.example.org"
  # CSP Level 3 host sources: without a scheme the page's scheme applies, a leading *. stands for
  # any subdomain, host names compare case-insensitively and the default port may be named.
  csp_case silent "$issuer" "login.example.org"
  csp_case silent "$issuer" "https://*.example.org"
  csp_case silent "$issuer" "*.example.org"
  csp_case silent "$issuer" "LOGIN.Example.ORG"
  csp_case silent "$issuer" "https://login.example.org:443"
  # A path restricts the source: ending in / it is a prefix, otherwise it must match exactly. Only
  # an empty path or / admits the whole origin; any other path that covers the discovery document
  # still may miss the token endpoint, the issuer's own path admits neither.
  csp_case silent "$issuer" "https://login.example.org/"
  csp_case path "$issuer" "https://login.example.org/realms/"
  csp_case path "$issuer" "https://login.example.org/realms/opaa/"
  csp_case path "$issuer" "https://login.example.org/realms/opaa/.well-known/"
  csp_case path "https://login.microsoftonline.com/t1/v2.0" "https://login.microsoftonline.com/t1/v2.0/"
  csp_case silent "https://login.microsoftonline.com/t1/v2.0" "https://login.microsoftonline.com"
  csp_case warn "$issuer" "https://login.example.org/realms/opaa"
  csp_case warn "$issuer" "https://login.example.org/other/"
  csp_case silent "$issuer" "https://other.example.net login.example.org"
  # An http source is upgraded to https, also as a scheme source; https: and * admit any https URL.
  csp_case silent "$issuer" "http://login.example.org"
  csp_case silent "$issuer" "https:"
  csp_case silent "$issuer" "http:"
  csp_case silent "$issuer" "*"
  csp_case silent "https://login.example.org:8443/realms/opaa" "login.example.org:8443"
  csp_case silent "https://login.example.org:8443/realms/opaa" "https://login.example.org:*"
  # The wildcard matches subdomains, not the domain itself; other ports and other hosts do not match.
  csp_case warn "https://example.org/realms/opaa" "https://*.example.org"
  csp_case warn "$issuer" "https://*.login.example.org"
  csp_case warn "$issuer" "https://login.example.org:8443"
  csp_case warn "https://login.example.org:8443/realms/opaa" "login.example.org"
  csp_case warn "$issuer" "https://login.example.org.evil.example"
  csp_case warn "$issuer" "data: blob:"
  # Without a scheme, an https page admits https only; a bare word is a host name, not a keyword.
  csp_case warn "http://login.example.org/realms/opaa" "login.example.org"
  csp_case warn "$issuer" "self"
  # An http issuer on an https page is mixed content, whatever the CSP admits; the browser only
  # makes an exception for the local machine (loopback addresses and *.localhost).
  csp_case warn "http://login.example.org/realms/opaa" "http://login.example.org" "Mixed Content"
  csp_case silent "http://localhost:8180/realms/opaa" "http://localhost:8180"
  csp_case silent "http://foo.localhost:8180/realms/opaa" "http://foo.localhost:8180"
  csp_case silent "http://127.0.0.2:8180/realms/opaa" "http://127.0.0.2:8180"
  # The browser also calls the token endpoint, which Helm cannot look up: an admitted issuer on
  # another origin still points to it, an issuer on the page's own origin needs no note.
  output="$(notes --set "bootstrap.oidc.issuerUri=$issuer" --set bootstrap.oidc.clientId=opaa \
    --set frontend.cspConnectSrcExtra=login.example.org)"
  if [[ "$output" != *"token_endpoint aus dem Discovery-Dokument"* ||
    "$output" != *"$issuer/.well-known/openid-configuration"* ]]; then
    echo "Expected the note on the token endpoint for an admitted issuer on another origin." >&2
    csp_failures=$((csp_failures + 1))
  fi
  output="$(notes --set bootstrap.oidc.issuerUri=https://opaa.example.org/realms/opaa \
    --set bootstrap.oidc.clientId=opaa)"
  if [[ "$output" == *"token_endpoint"* ]]; then
    echo "Expected no note on the identity provider for an issuer on the page's own origin." >&2
    csp_failures=$((csp_failures + 1))
  fi
  if ((csp_failures > 0)); then
    echo "$csp_failures CSP warning case(s) failed." >&2
    exit 1
  fi
else
  echo "skipped: Helm $(helm version --short) has no client-side dry run"
fi

echo "Static check passed: ${#value_sets[@]} value sets, Kubernetes $KUBERNETES_VERSIONS, release packaging, release notes, re-run provenance, registry lookup, notes"
