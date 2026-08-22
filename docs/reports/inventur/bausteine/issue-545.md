# Issue #545 — fix(audit): Änderung der Quellkonfiguration erzeugt keinen Audit-Eintrag
- Geschlossen: 2026-08-20 (completed)
- Labels: backend, size:S, security
- PRs: #578 (2026-08-20)

**Laut Issue:** Aus dem Review zu PR #542 stammender, seit #476 vorbestehender Befund: Eine reine Quellkonfigurations-Änderung über `PUT /api/v1/libraries/{libraryId}` (URL, Pfad, Proxy, Zugangsdaten) erzeugte keinen Audit-Eintrag, da `KnowledgeLibraryService#updateLibrary` nur Name/Beschreibung/Sichtbarkeit/`listed` protokollierte. Gefordert: eigenes Audit-Ereignis, ohne sensible Werte zu protokollieren (nur welche Felder geändert wurden).

**Geliefert:** Neues Audit-Ereignis `LIBRARY_SOURCE_UPDATED`, das ausschließlich protokolliert, welche Felder (`sourcePath`, `sourceUrl`, `sourceProxy`, `sourceCredentials`, `sourceInsecureSsl`) geändert wurden — nie die Werte selbst, gemäß ADR-0018. Migration 035 weitet `chk_audit_log_event_type`. Reproduktionsnachweis erbracht (roter Test ohne Fix, grün mit Fix).

**Verifikation:** `backend/src/main/java/io/opaa/audit/AuditEventType.java` enthält `LIBRARY_SOURCE_UPDATED`.

**Themen:** audit, security, library, doku
