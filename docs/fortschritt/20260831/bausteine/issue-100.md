# Issue #100 — Expose Ollama model configuration in docker-compose
- Geschlossen: 2026-03-06 (completed)
- Labels: bug, backend, setup, size:S
- PRs: #101 (2026-03-06)

**Laut Issue:** `OPAA_OLLAMA_CHAT_MODEL` und `OPAA_OLLAMA_EMBEDDING_MODEL` wurden im Backend-Environment-Abschnitt von `docker-compose.yml` nicht durchgereicht — Modellwechsel (z. B. `phi3:mini` → `qwen2.5:7b`) war ohne Neubau nicht möglich. Gefordert: beide Variablen ergänzen.

**Geliefert:** PR #101 ergänzt die beiden Ollama-Variablen und behebt zusätzlich zwei bei einem vorherigen Squash-Merge (#99) verlorene Variablen (`OPAA_CORS_ALLOWED_ORIGINS`, `OPAA_PGVECTOR_DIMENSIONS`), fügt `extra_hosts` für `host.docker.internal` unter Linux hinzu und aktualisiert den Demo-Link. Umfang geht über das Issue hinaus (Reparatur eines Merge-Schadens, Demo-Link), was in der PR-Beschreibung offen benannt ist.

**Verifikation:** Abweichung im heutigen Code: In `docker-compose.yml` sind aktuell **keine** `OLLAMA`-Umgebungsvariablen mehr vorhanden — Ollama als Provider-Option wurde offenbar zu einem späteren Zeitpunkt aus dem Docker-Compose-Setup entfernt (nicht Teil dieses Untersuchungsauftrags, aber auffällig: die hier gelieferte Konfigurierbarkeit existiert im heutigen Stand nicht mehr).

**Themen:** deployment, docker, ollama, konfiguration
