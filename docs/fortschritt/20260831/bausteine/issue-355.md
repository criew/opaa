# Issue #355 — Umfang des revisionssicheren Audit-Loggings schneiden
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #398 (2026-08-14)

**Laut Issue:** Teil von #344. Revisionssicheres Audit-Logging sei die größte Lücke gegenüber Phase 1. Zu klären: welche Ereignisse protokolliert werden müssen, was „revisionssicher" konkret heißt (Unveränderbarkeit, Aufbewahrung, Export, SIEM), wie sich das mit dem Verbot personenbezogener Auswertungspfade (#239) verträgt. Ergebnis sollte eine Entscheidungsvorlage, möglichst als ADR-Entwurf, sein.

**Geliefert:** Reine Dokumentationsänderung, die den Umfang schneidet. Protokolliert werden Rechteänderungen, Verwaltungshandeln, Verzeichnisabgleich, Systemeinstellungen und jeder — auch abgewiesene — Zugriff auf die Protokolldaten selbst; Abfragen und Antwortinhalte bleiben draußen. Sicherheitsgrad: einfaches Anfügen ohne Prüfsummenverkettung, mit offen benannter Grenze (Manipulation bei direktem DB-Zugang fällt nicht auf). Trennung von Speicherung und Auswertbarkeit als tragender Zielkonflikt-Lösung: kein Personenfilter außer im freigegebenen Vier-Augen-Vorgang. Aufbewahrung 1–10 Jahre, Voreinstellung 3 Jahre. Daraus wurden fünf Umsetzungsvorgänge geschnitten: #391–#395, alle laut Chunk vollständig als „completed" mit gemergten PRs umgesetzt (siehe eigene Bausteine).

**Verifikation:** `docs/features/security-and-compliance.md` existiert im Worktree; das Audit-Paket `backend/src/main/java/io/opaa/audit/` ist umfangreich vorhanden (siehe Bausteine #391–#395) — der hier geschnittene Umfang wurde tatsächlich gebaut.

**Themen:** audit, revisionssicherheit, protokoll, security, governance, produktausrichtung
