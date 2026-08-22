# Issue #393 — feat(audit): Zugriffsweg für die Revision ohne personenbezogene Auswertung
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:L, security
- PRs: #449 (2026-08-17)

**Laut Issue:** Baut auf #391/#392 auf, setzt #239 konkret um. Genau vier Abfragewege (Objekt, Zeitraum, Ereignisart, Vorgang), jede mit Pflicht-Zeitraum und Ergebnisbegrenzung. Ausdrücklich nicht gebaut: Filter/Gruppierung/Sortierung nach Person, Zählung je Person, Freitextsuche, Vollabzug. Eine Ausnahme: anlassbezogene Klärung im Vier-Augen-Prinzip mit Zweckausschluss für arbeitsrechtliche Fragen.

**Geliefert:** Wie beschrieben. Vier Endpunkte unter `/api/v1/audit/events/...`, harte serverseitige Obergrenze der Seitengröße (`MAX_PAGE_SIZE=200`), kein Endpunkt akzeptiert Personenfilter als Eingabe. Vier-Augen-Ausnahme über `AuditIncidentScopeGrant` mit Anfrage/Freigabe/Abfrage-Dreischritt, Selbstfreigabe durch zweite Person technisch verhindert (Service-Check plus DB-Constraint). Neue eng begrenzte Rolle `SystemRole.AUDITOR`. Eine Annahme im PR ist bemerkenswert: Die im Issue verlangte „technisch durchgesetzte Trennung der Auswertungswege für Revision und Dienststellenleitung" wurde nur einseitig erfüllt — das Leitungs-Cockpit existiert laut `monitoring-and-governance.md` noch gar nicht, die Trennung ist also nur dadurch gegeben, dass der Revisionsweg heute der einzige ist.

**Verifikation:** `AuditQueryService.java` und `AuditIncidentScopeGrant.java` existieren im Worktree unter `backend/src/main/java/io/opaa/audit/`.

**Themen:** audit, revision, security, backend, mitbestimmungsfähigkeit, governance
