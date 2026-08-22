# Issue #625 — ci: Actions auf Node-24-Runtime aktualisieren (Node-20-Deprecation)
- Geschlossen: 2026-08-20 (completed)
- Labels: size:S, ci
- PRs: #627 (2026-08-20)

**Laut Issue:** Kein Issue-Body (leer) — der Titel benennt das Anliegen: gepinnte GitHub-Actions auf Node-24-kompatible Versionen anheben, wegen der Node-20-Deprecation-Warnung.

**Geliefert:** Alle gepinnten GitHub- und Docker-Actions in `.github/workflows/*.yml` auf die neueste stabile, Node-24-fähige Major-Version angehoben (`actions/checkout` v4→v7, `setup-java` v4→v5, `cache` v4→v6, `upload-artifact` v4→v7, `setup-python` v5→v7, `setup-node` v4→v7, `github-script` v7→v9, diverse `docker/*`-Actions). `contributor-assistant/github-action` blieb bewusst auf v2.6.1 (keine neuere Major-Version verfügbar). Breaking-Changes je übersprungener Major-Version wurden geprüft und als nicht relevant eingestuft. Entspricht dem Issue-Titel vollständig.

**Verifikation:** `ci.yml` verwendet aktuell `actions/checkout@v7`, `actions/setup-java@v5`, `actions/cache@v6` — Versionsstand deckt sich mit der PR-Beschreibung.

**Themen:** ci, projektsetup, wartung
