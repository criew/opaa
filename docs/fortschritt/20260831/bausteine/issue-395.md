# Issue #395 — feat(audit): Aufbewahrung der Protokolldaten mit automatischer Löschung
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #454 (2026-08-17)

**Laut Issue:** Letzter Baustein der Audit-Serie. Konfigurierbare Frist (1–10 Jahre, Default 3), automatische monatliche Löschung über ein getrenntes Wartungskonto, nie einzelne Sätze sondern vollständige Zeitscheiben, Warnung bei Protokollfrist kürzer als Inhaltsaufbewahrung, Verkürzung wirkt nur nach vorn und ist selbst protokollpflichtig.

**Geliefert:** Wie beschrieben, mit einer offen benannten Lücke: Das Abnahmekriterium „Protokollfrist kürzer als Inhaltsaufbewahrung erzeugt Warnung" ist **nicht erfüllt**, nur als Erweiterungspunkt (`ContentRetentionProvider`) vorbereitet — eine konfigurierbare Inhaltsaufbewahrung existiert erst mit #216, dort per Kommentar vermerkt. Löschmechanismus über `SECURITY DEFINER`-Funktion mit eigenem `opaa_audit_owner`. Review-Runde 1 fand und behob drei blockierende Sicherheitsbefunde vor dem finalen Merge: ein `pg_temp`-Schattenangriff, mit dem das Anwendungskonto den Fortschrittsdeckel umgehen konnte (in der Reviewer-Reproduktion 39 von 51 Partitionen in einem Aufruf gelöscht), ein an der eigenen Härtung scheiterndes JPA-`save`, und ein ungedeckelter allererster Aufruf nach der Migration. Alle drei mit Regressionstests belegt. Der bereits in #391 offen benannte Superuser-Schwachpunkt (#426) wird durch diesen PR laut eigener Aussage „dringlicher, nicht gelöst".

**Verifikation:** `AuditRetentionSettingsService.java` und `AuditRetentionDeletionService.java` existieren im Worktree unter `backend/src/main/java/io/opaa/audit/`.

**Themen:** audit, aufbewahrung, löschung, security, backend, dsgvo
