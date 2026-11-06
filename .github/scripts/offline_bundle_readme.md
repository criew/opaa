# OPAA @VERSION@ – Offline-Bündel

Dieses Bündel enthält alles, um OPAA @VERSION@ in einem Netz ohne Internetzugang zu prüfen und zu
installieren. Maßgeblich ist die Anleitung im Handbuch, Kapitel „Images und Laufzeit“, Abschnitt
„Installation ohne Internetzugang“. Es liegt dem Update-Paket bei und ist auch außerhalb des Bündels
erhältlich. Die Befehle unten sind eine Kurzfassung. Vor der Prüfung ist auch diese Datei
ungeprüft. Wer die Befehle von hier übernimmt, gleicht sie mit dem Handbuch ab.

| Pfad | Inhalt |
|---|---|
| `images/` | `opaa-backend`, `opaa-frontend` und `opaa-ocr` (Texterkennung, optional) als OCI-Archiv, je mit `linux/amd64` und `linux/arm64` |
| `DIGESTS` | die Images mit dem Digest ihres Index, wie in der Registry des Projekts |
| `chart/` | das Helm-Chart |
| `update-paket/` | das Update-Paket der Version |
| `sbom/` | die CycloneDX-Stücklisten von Backend, Frontend und OCR-Image, dazu die Lizenzübersicht des OCR-Images |
| `attestations/` | die Sigstore-Bundles der Attestierungen von Update-Paket, SBOMs, Chart und Images; die von Chart und Images gelten dem Manifest in der Registry, ihr Digest steht in `DIGESTS` bzw. im Chart |
| `SHA256SUMS`, `SHA256SUMS.sigstore.json` | Prüfsummen aller Dateien und ihre Attestierung |

## Prüfen

Für jedes Release auf einem Rechner mit Internetzugang den Vertrauensanker holen und mit dem Bündel
hineinbringen:

```bash
gh attestation trusted-root > trusted_root.jsonl
```

Dann ohne Internetzugang im entpackten Verzeichnis:

```bash
gh attestation verify SHA256SUMS --bundle SHA256SUMS.sigstore.json \
  --custom-trusted-root ../trusted_root.jsonl --repo @REPO@ \
  --signer-workflow @REPO@/.github/workflows/offline-bundle.yml \
  --source-ref refs/tags/v@VERSION@
sha256sum -c SHA256SUMS
```

Beide Befehle müssen ohne Fehler enden. Danach ist jede in `SHA256SUMS` genannte Datei die des
Release. Das Archiv selbst prüft das Handbuch vor dem Entpacken gegen
`opaa-offline-@VERSION@.tar.sigstore.json`; hing es in Teilen am Release, setzt
`cat opaa-offline-@VERSION@.tar.part-* > opaa-offline-@VERSION@.tar` es vorher zusammen.

## Einspielen

```bash
skopeo copy --all --preserve-digests \
  oci-archive:images/opaa-backend-@VERSION@.oci.tar \
  docker://registry.example/opaa/opaa-backend:@VERSION@
skopeo copy --all --preserve-digests \
  oci-archive:images/opaa-frontend-@VERSION@.oci.tar \
  docker://registry.example/opaa/opaa-frontend:@VERSION@
skopeo copy --all --preserve-digests \
  oci-archive:images/opaa-ocr-@VERSION@.oci.tar \
  docker://registry.example/opaa/opaa-ocr:@VERSION@
helm install opaa chart/opaa-@VERSION@.tgz -n opaa -f opaa-werte.yaml \
  --set backend.image.repository=registry.example/opaa/opaa-backend \
  --set frontend.image.repository=registry.example/opaa/opaa-frontend
```
