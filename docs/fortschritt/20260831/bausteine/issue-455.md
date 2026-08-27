# Issue #455 — chore(audit): Partitionshorizont von audit_log rechtzeitig verlängern
- Geschlossen: 2026-08-24 (not planned)
- Labels: backend, size:S, security
- PRs: keine

**Laut Issue:** Migration 017 (#391) legt beim Anwenden einen festen Horizont von Monatspartitionen für `audit_log` an (drei Monate zurück bis 16 Jahre in die Zukunft, ohne DEFAULT-Partition). Ohne Nachprovisionierung schlägt nach rund 16 Jahren jeder `INSERT` fehl. Gefordert war ein Mechanismus (Migration und/oder Scheduler, analog `AuditRetentionScheduler` aus #395), der den Horizont rechtzeitig verlängert.

**Geliefert:** Nichts. Sub-Issue von Epic #457 (Phase 2), bewusst zurückgestellt. Bei einem 16-Jahre-Puffer ist das betrieblich unkritisch für die aktuelle Projektphase.

**Verifikation:** Keine tiefergehende Prüfung vorgenommen — Betriebsrisiko liegt weit in der Zukunft, Rückstellung ist nachvollziehbar dokumentiert.

**Themen:** security, audit, backend, doku
