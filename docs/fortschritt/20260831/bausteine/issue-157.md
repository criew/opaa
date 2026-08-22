# Issue #157 — Externalize docker-compose environment variables into .env file
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement, setup, size:S
- PRs: #158 (2026-03-08)

**Laut Issue:** Die 30+ inline definierten Umgebungsvariablen im `backend`-Service von `docker-compose.yml` sollten über `env_file` in eine `.env`-Datei ausgelagert und `.env.example` entsprechend erweitert werden.

**Geliefert:** PR #158 lagert die Variablen in `.env.docker` aus (Name abweichend vom Issue-Titel, der `.env` nannte), erweitert `.env.example` um 50+ Variablen mit Kategorien/Beschreibungen, benennt eine Variable um (`OPAA_DOCUMENTS_PATH_HOST` → `OPAA_INDEXING_DOCUMENT_PATH_HOST`), behebt zusätzlich einen OIDC-Callback-Race-Condition-Bug und überarbeitet die Deployment-Doku. Der PR ging damit über den reinen Refactoring-Scope hinaus (Bugfix „im Vorbeigehen" enthalten).

**Verifikation:** `docker-compose.yml` verwendet `env_file: ${OPAA_ENV_FILE:-.env.docker}` an mehreren Services; `.env.docker.example` existiert im Worktree — die Struktur besteht im heutigen Deployment-Setup fort (später ergänzt um `.env.docker.example`, siehe Issue/PR #719 aus früheren Chunks).

**Themen:** deployment, docker-compose, konfiguration, setup
