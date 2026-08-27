# Issue #452 — fix(audit): Bindungsfehler an Audit-Endpunkten ebenfalls ueber den Selbstprotokoll-Trichter fuehren
- Geschlossen: 2026-08-24 (not planned)
- Labels: backend, security
- PRs: keine

**Laut Issue:** PR #450 protokolliert jeden Zugriff auf die Audit-Endpunkte, aber nur, wenn die Anfrage Spring MVCs Parameterbindung passiert — `reason` ist bewusst optional gebunden, `from`/`to`/`objectType`/`eventType`/`page`/`size` dagegen als echte Typen. Eine unparsebare Angabe bei einem dieser Parameter führt zu 400, bevor `AuditQueryService` erreicht wird, und erzeugt deshalb keinen `audit_log`-Eintrag — inkonsistent mit dem für `reason` bereits abgedeckten Fall.

**Geliefert:** Nichts. Sub-Issue von Epic #457 (Phase 2), bewusst zurückgestellt.

**Verifikation:** Keine Codeänderung im `AuditController`/`AuditQueryService`-Bereich zu diesem Thema erkennbar; als "not planned" konsistent mit Epic-Abschluss.

**Themen:** security, audit, backend
