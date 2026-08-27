# Issue #358 — Gruppengebundene Spaces: Mitgliedschaft aus dem Verzeichnis ableiten
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:M, auth
- PRs: keine

**Laut Issue:** Mit #333 ist `SpaceKind` entfallen, `isDefault` ist umgesetzt, `memberSource` bisher nicht — jeder Space ist implizit `MANUAL`. Gefordert: `Space.memberSource` (`MANUAL`/`GROUP`) und `Space.groupId`, bei `GROUP` wird die Mitgliederliste aus einer Verzeichnisgruppe abgeleitet statt gepflegt, nur der System-Admin legt solche Spaces an, samt Folgen für Autoren-Benachrichtigung, Strikt-Modus-Prüfung und Plausibilitätsschwelle bei Verzeichnisläufen.

**Geliefert:** Nicht umgesetzt. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme.

**Verifikation:** `grep` auf `memberSource` in `backend/src/main/java/io/opaa/space` ohne Treffer — bestätigt Nichtumsetzung.

**Themen:** spaces, rechteverwaltung, auth, ordner
