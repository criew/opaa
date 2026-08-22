# Issue #484 — feat(security): Pfad-Allowlist und Berechtigung für Konnektorbibliotheken
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #511 (2026-08-19)

**Laut Issue:** Vor Mehrbenutzer-Produktivbetrieb sollte eine Pfad-Allowlist für `FILESYSTEM`-Bibliotheken eingeführt und entschieden werden, welche Rolle Konnektorbibliotheken anlegen darf; Zusammenspiel mit #267 (Zielprüfung gegen private Adressbereiche) benennen.

**Geliefert:** Teilweise abweichend von der Ausgangsfrage: Die Rollenfrage wurde laut PR-Beschreibung als Maintainer-Entscheidung offen gelassen — weiterhin darf jeder mit Anlage-Recht jeden Bibliothekstyp anlegen. Stattdessen liegt die eigentliche Sicherung in einer betriebsseitig konfigurierten Pfad-Allowlist (`opaa.indexing.filesystem-allowlist`, `OPAA_INDEXING_FILESYSTEM_ALLOWLIST`), geprüft bei Anlage/Update und erneut bei jedem Lauf (Traversal-sicher über `Path.normalize()`). Eine leere Allowlist (Standard) deaktiviert `FILESYSTEM` faktisch. URL-Typen (`HTTP_DIRECTORY`, `RSS_FEED`) bleiben bewusst unberührt — #267 bleibt dafür offen.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/FilesystemPathAllowlist.java` existiert; `docs/deployment.md` und `.env.example` dokumentieren die Variable. `docs/decisions/0018-quellkonfiguration-in-der-bibliothek.md` enthält den entsprechenden Nachtrag.

**Themen:** backend, sicherheit, spaces, ssrf, adr
