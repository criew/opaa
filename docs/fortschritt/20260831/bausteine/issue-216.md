# Issue #216 — Governance controls for co-determination
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, size:M, security
- PRs: keine

**Laut Issue:** Aufbewahrung für Chats, Artefakte und private Inhalte, vom System-Admin voreingestellt, mit Vorwarnung vor Ablauf, Verlängerungsoption, einer nur dem Autor sichtbaren "meine privaten Inhalte"-Liste, Aggregation je Organisationseinheit mit Mindestgruppengröße, technisch durchgesetzten Speicherquoten ohne Auswertungspfad, keinen Ranglisten und protokollierten Governance-Änderungen. Der Audit-Teil wurde bereits nach #239 verlagert.

**Geliefert:** Nicht als eigenständiges Feature umgesetzt. Ein Nachtrag aus #395/#454 (Audit-Aufbewahrung) zeigt eine Vorbereitung: Ein Abnahmekriterium aus #395 ("Protokollfrist kürzer als Inhaltsaufbewahrung erzeugt Warnung") ist nur als Erweiterungspunkt vorbereitet (`io.opaa.audit.ContentRetentionProvider`), aber nicht wirksam — ohne registrierte Inhaltsaufbewahrung-Konfiguration liefert die Prüfung immer `false`. Sobald dieses Issue (oder ein Nachfolger) eine konfigurierbare Inhaltsaufbewahrung einführt, genügt eine `@Component`-Implementierung von `ContentRetentionProvider`, damit die Warnlogik in `AuditRetentionSettingsService` greift. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme.

**Verifikation:** `io.opaa.audit`-Paket im Backend existiert (`AuditRetentionSettingsService.java` u. a.) — Erweiterungspunkt `ContentRetentionProvider` plausibel, aber keine Inhaltsaufbewahrungs-Logik für Chats/Artefakte gefunden.

**Themen:** governance, security, retention, spaces
