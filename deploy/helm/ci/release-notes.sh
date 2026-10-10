#!/usr/bin/env bash
# Prints the fixed part of the GitHub release notes for a release tag (publish-images.yml). The
# namespace is created with the Pod Security labels for restricted before helm install, never by
# --create-namespace. The preparation of existing installations comes from
# docs/upgrade-notes/X.Y.Z[-<pre>].md of the tagged commit; without it the release needs none.
set -euo pipefail

if (($# != 2)); then
  echo "Usage: $0 <repository owner/name> <release tag vX.Y.Z[-<pre>]>" >&2
  exit 2
fi
repository="$1"
tag="$2"
owner="${repository%%/*}"
version="$(deploy/helm/ci/release-version.sh "$tag")"
handbook="https://github.com/${repository}/blob/${tag}/docs/handbuch"
# release-version.sh admits no slash in the version, so the file stays inside the directory.
upgrade_notes="${UPGRADE_NOTES_DIR:-docs/upgrade-notes}/${version}.md"

if [[ -e "$upgrade_notes" ]]; then
  if [[ ! -f "$upgrade_notes" ]] || ! grep -q '[^[:space:]]' "$upgrade_notes"; then
    echo "$upgrade_notes must be a file naming the preparation steps; delete it if the release needs none" >&2
    exit 1
  fi
  preparation="$(cat "$upgrade_notes")"
else
  preparation="Keine Vorbereitung nötig."
fi

cat <<EOF
**Images** (\`linux/amd64\`, \`linux/arm64\`)
- \`ghcr.io/${owner}/opaa-backend:${version}\`
- \`ghcr.io/${owner}/opaa-frontend:${version}\`

**Helm-Chart**
- \`oci://ghcr.io/${owner}/charts/opaa\`, Version \`${version}\`

Installation wie im Handbuch, Kapitel Kubernetes, [Installation](${handbook}/kubernetes.md#installation): zuerst den Namespace mit den Labels für Pod Security \`restricted\` anlegen, dann Datenbank, Geheimnisse und Werte-Datei vorbereiten, zuletzt installieren.

\`\`\`bash
kubectl create namespace opaa
kubectl label namespace opaa \\
  pod-security.kubernetes.io/enforce=restricted \\
  pod-security.kubernetes.io/warn=restricted
# Datenbank, Geheimnisse und opaa-werte.yaml wie im Handbuch vorbereiten
helm install opaa oci://ghcr.io/${owner}/charts/opaa --version ${version} \\
  -n opaa -f opaa-werte.yaml
\`\`\`

**Vorbereitung für Bestandsinstallationen**

EOF
printf '%s\n\n' "$preparation"
cat <<EOF
Ablauf der Aktualisierung im Handbuch: für Kubernetes [Aktualisierung und Rückweg](${handbook}/kubernetes.md#aktualisierung-und-rückweg), für Docker Compose [Vorbereitungsschritte für Bestandsinstallationen](${handbook}/deployment.md#vorbereitungsschritte-für-bestandsinstallationen). Wer Versionen überspringt, prüft die Vorbereitung jedes übersprungenen Release.
EOF
