# Issue #124 — feat(audit): audit logging for workspace actions
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: keine

**Laut Issue:** Ein Append-only-Audit-Log für Workspace-Aktionen (Erstellung, Mitgliederänderungen, Dokumentaktionen) mit API für Workspace-Admins und System-Admins. Teil von Epic #107, Phase 6.

**Geliefert:** Nichts im Sinne des Issues — nicht umgesetzt. Geschlossen als „completed" ohne PR, weil der Tabellenschnitt als workspace-zentriert und fachlich unzureichend eingeschätzt wurde. Laut Schließungskommentar ist das Audit-Log in einer Behörde die mitbestimmungsrelevante Datenquelle mit eigenen Anforderungen (kein personenbezogener Auswertungspfad, Aufbewahrungsgrenzen, Datensparsamkeit, geregelter Zugriff, SIEM-Export) — dafür ist #239 der fachlich richtige Nachfolger, ergänzt um #238 (Historisierung von Rechten) für die „Negativfrage" von Prüfern.

**Verifikation:** Kein workspace-bezogenes Audit-Log im Code auffindbar; die Governance-Anforderungen sind in `docs/features/access-control.md` bzw. `docs/features/spaces-and-assets.md` dokumentiert, konsistent mit dem Schließungskommentar.

**Themen:** workspaces, audit, governance, mitbestimmung, migration, verworfen
