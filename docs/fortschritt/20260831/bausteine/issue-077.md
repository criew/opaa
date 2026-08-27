# Issue #77 — Vector Store Index Type Hardcoded
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, backend, size:S
- PRs: keine

**Laut Issue:** Der pgvector-Index-Typ (`index-type: hnsw`) ist in `application.yml` hart verdrahtet, was ADR-0002s Anspruch auf Konfigurationsflexibilität widerspricht. Gefordert war ein per Umgebungsvariable wählbarer Index-Typ (`none`/`ivfflat`/`hnsw`) je nach Datenmenge.

**Geliefert:** Kein Code-Fix. Laut Schließungskommentar bewusst nicht umgesetzt: `distance-type` und `dimensions` sind bereits per Env konfigurierbar, nur der Index-Typ bleibt fix `hnsw`. Begründung: Ein nachträglicher Wechsel des Index-Typs erfordert ohnehin einen Neuaufbau des Index; ein reiner Env-Schalter ohne Migrationskonzept würde Inkonsistenzen zwischen Konfiguration und bestehendem Index einladen. `hnsw` gilt als tragfähiger Default für den Einsatzzweck (eine Instanz je Behörde, wachsende Bestände). Bei konkretem Bedarf soll das Thema mit einem Migrationskonzept neu aufgesetzt werden.

**Verifikation:** `backend/src/main/resources/application.yml` zeigt weiterhin `index-type: hnsw` fest verdrahtet, während `distance-type` und `dimensions` tatsächlich über `${OPAA_PGVECTOR_DISTANCE_TYPE:...}` bzw. `${OPAA_PGVECTOR_DIMENSIONS:...}` konfigurierbar sind — deckt sich mit dem Schließungskommentar.

**Themen:** retrieval, konfiguration, pgvector
