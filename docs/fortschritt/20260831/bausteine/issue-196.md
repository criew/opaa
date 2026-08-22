# Issue #196 — ci: publish backend and frontend Docker images to GHCR
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement
- PRs: #197 (2026-08-02)

**Laut Issue:** CI soll `backend/Dockerfile` und `frontend/Dockerfile` bei jedem Push auf `main` (plus manuellem Trigger) bauen und als `ghcr.io/criew/opaa-backend` bzw. `ghcr.io/criew/opaa-frontend` mit den Tags `main` und Commit-SHA nach GHCR veröffentlichen, damit ein Deployment-Ziel per `docker compose pull && docker compose up -d` arbeiten kann.

**Geliefert:** PR #197 fügt den Workflow `.github/workflows/publish-images.yml` genau in dieser Form hinzu und dokumentiert die Images sowie den Pull-basierten Deployment-Fluss in `docs/deployment.md`. Keine Abweichung vom Issue erkennbar.

**Verifikation:** `.github/workflows/publish-images.yml` existiert im heutigen Worktree unverändert an der erwarteten Stelle.

**Themen:** ci, deployment, docker
