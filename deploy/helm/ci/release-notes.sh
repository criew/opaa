#!/usr/bin/env bash
# Prints the fixed part of the GitHub release notes for a release tag (publish-images.yml, job
# release). The installation follows the order of the handbook chapter "Kubernetes": the namespace
# is created with the Pod Security labels for restricted before helm install, never by
# --create-namespace, which would create it without them.
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

Vor dem Update die Vorbereitungsschritte für Bestandsinstallationen im Handbuch prüfen (docs/handbuch/deployment.md).
EOF
