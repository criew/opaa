# Issue #242 — Konsistenzprüflauf zwischen Vektorspeicher und Datenbank
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, size:S
- PRs: keine

**Laut Issue:** `library_id` liegt als Kopie am Chunk im Vektorspeicher; nach einer Datenbanksicherung können Chunks mit inzwischen anders berechtigten oder gelöschten Bibliotheks-Kennungen existieren. Gefordert: Festlegung der führenden Quelle (relationale Datenbank), ein Konsistenzprüflauf (verwaiste Chunks, Dokumente ohne Chunks, abweichende Bibliotheks-Kennung), Bericht und Reparaturweg.

**Geliefert:** Nicht umgesetzt. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme, ohne inhaltlichen Kommentar über das Standardmuster hinaus.

**Verifikation:** Nicht separat geprüft.

**Themen:** retrieval, pgvector, wissensbibliotheken
