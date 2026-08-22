# Issue #394 — feat(audit): Zugriff auf Protokolldaten erzeugt selbst einen Eintrag
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:S, security
- PRs: #450 (2026-08-17)

**Laut Issue:** Baut auf #391/#393 auf. Jeder Lese-, Auswertungs- und Exportzugriff auf Protokolldaten — auch der abgewiesene — muss selbst einen nicht unterdrückbaren Eintrag mit Person, Zeitpunkt, Pflicht-Anlass und Umfang erzeugen, in derselben Ablage wie alle anderen Einträge.

**Geliefert:** Wie beschrieben, über alle fünf Zugriffswege aus #393 hinweg (`AUDIT_LOG_ACCESSED`). Wichtige Architekturentscheidung: `@PreAuthorize` auf den Controller-Endpunkten entfernt, weil eine dort abgewiesene Anfrage sonst nie den zentralen Lese-Trichter (`AuditQueryService`) erreicht und damit auch nicht protokollierbar wäre — die AUDITOR-Prüfung und die Anlass-Pflicht laufen jetzt im Service selbst. Ein Constraint-Detail: `reason` ist an der HTTP-Schicht bewusst nicht `required`, damit ein fehlender Anlass den Trichter erreicht statt von Spring MVC vorab abgefangen zu werden — für die übrigen Parameter gilt das nicht, was der PR-Autor selbst als Lücke benennt und als Folge-Issue #452 herausgelöst hat. Zweites Folge-Issue #451 zum Schutz gegen Fluten der Ablage durch wiederholte abgewiesene Zugriffe.

**Verifikation:** `AuditQueryService.java` (Selbstprotokollierung) und der Test `AuditFunnelStructureTest` existieren im Worktree unter `backend/src/main/java/io/opaa/audit/` bzw. `backend/src/test/java/io/opaa/audit/`.

**Themen:** audit, selbstprotokollierung, security, backend, revisionssicherheit
