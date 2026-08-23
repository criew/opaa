# Issue #50 — feat: make server bind address configurable
- Geschlossen: 2026-02-26 (completed)
- Labels: enhancement, mvp
- PRs: #51 (2026-02-26)

**Laut Issue:** Spring Boot band standardmäßig an `localhost`, wodurch das Backend von anderen Geräten im Netzwerk (Demos, LAN-Tests) nicht erreichbar war. Gefordert war eine konfigurierbare `server.address`-Property über `OPAA_SERVER_ADDRESS` (Default `localhost`, kompatibel zum bisherigen Verhalten).

**Geliefert:** PR #51 (gemeinsam mit #49) setzt genau das um: `server.address: ${OPAA_SERVER_ADDRESS:localhost}` in `application.yml`, zusätzlich `docker-compose.yml`- und `docs/deployment.md`-Anpassungen für den Docker-Kontext (dort Default `0.0.0.0`).

**Verifikation:** `backend/src/main/resources/application.yml` enthält weiterhin `address: ${OPAA_SERVER_ADDRESS:localhost}`.

**Themen:** backend, deployment, netzwerkzugriff, konfiguration
