# Issue #16 — chore: create Docker Compose deployment for full stack
- Geschlossen: 2026-02-20 (completed)
- Labels: mvp, backend, frontend, size:L
- PRs: #33 (2026-02-20)

**Laut Issue:** Vollständige Docker-Compose-Konfiguration mit drei Diensten (postgres, backend, frontend/Nginx), Multi-Stage-Dockerfiles, `.env.example` mit allen Umgebungsvariablen, Deployment-Dokumentation, persistentes DB-Volume.

**Geliefert:** PR #33 liefert genau das: `backend/Dockerfile`, `frontend/Dockerfile`, Nginx-Reverse-Proxy-Konfiguration, `.env.example`, `docs/deployment.md`. Keine Abweichung vom Issue.

**Verifikation:** `docker-compose.yml` existiert weiterhin und wurde deutlich erweitert — heute sind sieben Dienste definiert (`postgres`, `backend`, `frontend`, `keycloak`, `demo-corpus`, `demo-presse` sowie das Daten-Volume `opaa-postgres-data`). Die drei ursprünglichen Kern-Dienste bestehen fort, Keycloak (Auth) und Demo-Korpus-Dienste kamen später hinzu.

**Themen:** deployment, docker-compose, mvp
