# Issue #832 — ci: Gradle-Cache in der CI wird nie aktualisiert — auf setup-gradle umstellen
- Geschlossen: 2026-08-24 (completed)
- Labels: size:S, ci
- PRs: #841 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1 (Befund T1). `actions/cache` mit Build-Skript-only-Key speichert bei exaktem Treffer nie neu — jeder PR baut cache-kalt. Umstellung auf `gradle/actions/setup-gradle` in allen Gradle-Workflows.

**Geliefert:** Wie gefordert. `ci.yml` (Jobs `backend`, `backend-integration`) und `retrieval-regression.yml` auf `gradle/actions/setup-gradle@v4` umgestellt; `e2e.yml`/`demo-smoke.yml` führen kein Gradle aus und blieben unangetastet. Wrapper-Aufrufe selbst unverändert.

**Verifikation:** `.github/workflows/ci.yml` und `.github/workflows/retrieval-regression.yml` im Worktree vorhanden; PR-Beschreibung verweist auf den eigenen CI-Lauf als Nachweis (kein `actionlint` lokal verfügbar).

**Themen:** ci, build, gradle
