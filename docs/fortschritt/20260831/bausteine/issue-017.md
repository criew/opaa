# Issue #17 — test: end-to-end integration tests and MVP verification
- Geschlossen: 2026-02-26 (completed)
- Labels: mvp, backend, frontend, size:L
- PRs: #39 (2026-02-26)

**Laut Issue:** Backend-Integrationstests mit Testcontainers (Indexierung von Markdown/PDF/DOCX, Query-Flow, OpenAI/Ollama-Konfiguration), GitHub-Actions-CI-Pipeline (Backend + Frontend), `docs/MVP-VERIFICATION.md` mit Zuordnung aller 8 MVP-Erfolgskriterien zu Prüfmethoden, aktualisiertes `AGENTS.md`.

**Geliefert:** PR #39 liefert Integrationstests (`DocumentIndexingIntegrationTest`, `ProviderConfigurationTest`, `MixedProviderConfigurationTest`, `OpenAiIntegrationTest`), erweiterte CI-Pipeline um einen `backend-integration`-Job und Prettier-Check, sowie `docs/MVP-VERIFICATION.md`. Zusätzlich, nicht im Issue gefordert: Vereinheitlichung der Zeilenenden (CRLF/LF) über `.gitattributes` und `.editorconfig`.

**Verifikation:** `.github/workflows/ci.yml` existiert weiterhin und wurde erheblich erweitert (Change-Detection je Backend/Frontend, weitere Jobs). `docs/MVP-VERIFICATION.md` existiert im heutigen Repo nicht mehr — laut Git-Log im Commit „docs: Einstieg und Umsetzungsstand auf die neue Ausrichtung angleichen" entfernt, im Zuge einer Doku-Neuausrichtung (heute u.a. `docs/STATUS.md`, `docs/USE-CASES.md`).

**Themen:** ci, tests, mvp, dokumentation
