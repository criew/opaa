# Issue #211 — Asset versioning with immediate propagation and rollback
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:M
- PRs: keine

**Laut Issue:** Ein verteiltes Asset ist eine Referenz, keine Kopie — Verbesserungen wirken für alle Nutzer sofort. Das braucht eine vollständige Historie (`AssetVersion` mit Autor, Zeitstempel, Grund, Konfigurations-Snapshot), einen aktiven Versionszeiger und Rollback ohne Löschung älterer Versionen. Versioniert wird die Konfiguration, nicht der Dokumentbestand.

**Geliefert:** Nicht umgesetzt. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme. Abhängigkeit #209 (Agent-Assets) ist ebenfalls unerledigt, sodass eine Versionierung ohnehin keinen Gegenstand hätte.

**Verifikation:** Nicht separat geprüft — logische Konsequenz aus der Nichtumsetzung von #209.

**Themen:** agenten, spaces
