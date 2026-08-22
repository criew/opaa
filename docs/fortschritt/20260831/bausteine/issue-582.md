# Issue #582 — feat(backend): Branding-Systemeinstellungen mit API
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:M
- PRs: #630 (2026-08-20)

**Laut Issue:** Backend-Systemeinstellung für Branding (Produktname, Claim, Logo, Primärfarbe, Farbschema-Vorgabe) mit `GET /api/v1/branding` (lesbar für angemeldete Nutzer) und `PUT /api/v1/system/branding` (nur `SYSTEM_ADMIN`), spec-first per ADR-0006, Liquibase-Persistenz, Validierung, Audit-Ereignis, sichere Logo-Behandlung (kein Skript-Risiko durch SVG).

**Geliefert:** Endpunkte wie gefordert plus eigene Logo-Endpunkte (`GET/PUT/DELETE /api/v1/branding(/system)/branding/logo`); SVG wird komplett abgelehnt statt gesäubert (bewusste Entscheidung gegen das im Issue vorgeschlagene „ablehnen oder säubern"), akzeptiert werden nur PNG/JPEG mit Bytes-basierter Typprüfung via Tika, Größen-/Maßgrenzen. Migration 041 (Tabelle) und 042 (Audit-Event-Typ `BRANDING_SETTINGS_CHANGED`). Wichtige Abweichung, im PR selbst dokumentiert: Der Merge-Commit auf `main` enthielt **nicht** den letzten Commit des Branches („Branding ohne Anmeldung lesbar machen"), der die Lesepfade für `permitAll` öffnet — dieser fehlende Teil wurde in #583 nachgezogen (dort korrigiert).

**Verifikation:** `backend/src/main/java/io/opaa/branding/BrandingSettingsService.java` sowie die übrigen im PR gelisteten Branding-Klassen existieren im aktuellen Code.

**Themen:** backend, branding, api, sicherheit, audit, migration
