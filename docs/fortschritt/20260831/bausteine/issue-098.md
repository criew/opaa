# Issue #98 — PostgreSQL 18 Docker container fails to start due to volume mount path change
- Geschlossen: 2026-03-06 (completed)
- Labels: bug, setup, size:S
- PRs: #99 (2026-03-06)

**Laut Issue:** `pgvector/pgvector:pg18`-Container startete nicht, weil PostgreSQL 18+ die Datenverzeichnisstruktur geändert hat (`pg_ctlcluster`-kompatibel, versionsspezifische Unterverzeichnisse). Gefordert: Volume-Mount von `/var/lib/postgresql/data` auf `/var/lib/postgresql` ändern, betroffene Dateien `docker-compose.yml` und `docs/features/deployment-infrastructure.md` anpassen; Hinweis, dass bestehende Volumes gedroppt werden müssen.

**Geliefert:** PR #99 ändert den Mount-Pfad exakt wie beschrieben und passt zusätzlich `backend/src/main/resources/application.yml` an. Keine Abweichung.

**Verifikation:** `docker-compose.yml` mountet im heutigen Worktree weiterhin `opaa-postgres-data:/var/lib/postgresql` (ohne `/data`-Suffix).

**Themen:** deployment, docker, postgresql, bugfix
