# Issue #349 — Verhältnis von Plugin-Architektur und MCP klären
- Geschlossen: 2026-08-24 (not planned)
- Labels: documentation, size:S
- PRs: keine

**Laut Issue:** Teil von #344 (Backlog-Sichtung). Die Plugin-Architektur für Konnektoren (#106, #126–#130) ist als Konzept angelegt, die neue Produktausrichtung nennt daneben MCP als Werkzeug-/Systemanbindung — beide lösen überlappende Probleme. Gefordert war eine Entscheidungsvorlage (möglichst ADR-Entwurf), ob es konkurrierende oder ergänzende Wege sind und was das für die offenen Plugin-Issues #126–#130 bedeutet.

**Geliefert:** Keine Entscheidung, bewusst zurückgestellt. Laut Kommentar (14.08.2026) ist bis dahin weder eine Plugin-Schnittstelle noch MCP gebaut, und es existiert kein einziger Konnektor — eine Festlegung würde auf dem Papier getroffen, ohne dass ein realer Fall sie geprüft hätte. Festgehalten wurde immerhin eine konzeptionelle Einordnung (Konnektor zieht/tief in die Pipeline vs. MCP ruft ab/klar begrenzt) und dass #127 (WebAssembly-Laufzeit) eine eigenständige Isolationsfrage ist, die bei negativer Plugin-Entscheidung zu Themenbereich D wandert. Die sechs abhängigen Vorgänge (#106, #126–#130) sowie der offene PR #161 eines Beitragenden bleiben bis zur Entscheidung offen und unangetastet. Geschlossen mit dem Hinweis, dass die Klärung unabhängig vom Ticketbestand erfolgt (Maintainer-Entscheidung) — bei konkretem Ergebnis entstehen neue, passend geschnittene Tickets.

**Verifikation:** Nicht code-relevant (reine Konzeptklärung, kein Code betroffen).

**Themen:** konnektoren, mcp, doku, architektur
