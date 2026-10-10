#!/usr/bin/env bash
# Prints the fixed part of the GitHub release notes for a release tag (publish-images.yml). The
# namespace is created with the Pod Security labels for restricted before helm install, never by
# --create-namespace. The preparation of existing installations comes from
# docs/upgrade-notes/X.Y.Z[-<pre>].md of the tagged commit; a release X.Y.0 needs the file, for
# any other a missing file means no preparation.
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

if [[ -L "$upgrade_notes" ]]; then
  echo "$upgrade_notes must not be a symbolic link" >&2
  exit 1
elif [[ -e "$upgrade_notes" ]]; then
  if [[ ! -f "$upgrade_notes" ]] || ! grep -q '[^[:space:]]' "$upgrade_notes"; then
    echo "$upgrade_notes must be a file naming the preparation steps; delete it if the release needs none" >&2
    exit 1
  fi
  # An open fence would swallow the rest of the notes into a code block.
  if (($(grep -c '^[[:space:]]*```' "$upgrade_notes" || true) % 2 != 0)); then
    echo "$upgrade_notes has an unclosed code fence (\`\`\`)" >&2
    exit 1
  fi
  preparation="$(cat "$upgrade_notes")"
elif [[ "$version" =~ ^[0-9]+\.[0-9]+\.0$ ]]; then
  # Only a release X.Y.0 can bring a break, so it states its preparation, if need be "none".
  echo "$upgrade_notes is missing: a release X.Y.0 names its preparation, \"Keine Vorbereitung nötig.\" if there is none (docs/releases.md)" >&2
  exit 1
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
