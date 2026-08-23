# Issue #23 — chore: set up GitHub Actions CI pipeline
- Geschlossen: 2026-02-18 (completed)
- Labels: mvp, setup, size:M, ci
- PRs: #24 (2026-02-18), #25 (2026-02-19)

**Laut Issue:** Workflow `.github/workflows/ci.yml` mit parallelen Jobs für Backend (Java 21 + Gradle, `./gradlew build`) und Frontend (Node 22 + npm, lint/test/build), Trigger auf Push/PR gegen `main`, Gradle-/npm-Caching.

**Geliefert:** PR #24 aktualisiert zunächst nur `AGENTS.md` (Tech-Stack- und Build-Befehle-Abschnitt) als Vorbereitung; PR #25 liefert den eigentlichen Workflow mit den zwei parallelen Jobs wie gefordert (Backend: Java 21/Temurin, Gradle-Cache; Frontend: Node 20 statt der im Issue genannten Node 22, npm-Cache). Kleine Abweichung: Node-Version im ersten Wurf 20 statt 22.

**Verifikation:** `.github/workflows/ci.yml` existiert weiterhin und ist seither erheblich gewachsen (Change-Detection je Bereich, zusätzlicher `backend-integration`-Job, weitere Workflows wie `e2e.yml`, `daily-report.yml`, `retrieval-regression.yml`, `demo-smoke.yml`). Die ursprüngliche Zwei-Job-Struktur bildet die Grundlage der heutigen, deutlich ausgebauten Pipeline.

**Themen:** ci, github-actions, projektsetup, mvp
