# Issue #204 — Strict mode for spaces
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:M, security
- PRs: keine

**Laut Issue:** Strikt-Modus als einzige technische Zusicherung im Modell (statt eines verantworteten menschlichen Akts): Ein Space mit `strictKnowledge` darf nur Bibliotheken assoziieren, deren Leserkreis alle Mitglieder abdeckt; ein Agent mit nicht vollständig abgedeckten Bindungen kann nicht aufgerufen werden; bricht die Voraussetzung nachträglich (Verzeichnissync, Grant-Entzug), geht der Space in den Zustand "Voraussetzung verletzt" mit benanntem Adressaten, Frist und Eskalation.

**Geliefert:** Nicht umgesetzt. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme: Der Umfang ist bewusst noch nicht gebaut und wird später angegangen; bei Wiederaufnahme wird der Zuschnitt neu auf Basis des dann aktuellen Stands von `docs/features/spaces-and-assets.md` bewertet. Kein Widerspruch — die Arbeit ist tatsächlich offen, nicht heimlich erledigt.

**Verifikation:** Kein `strictKnowledge`-Feld oder Ähnliches im Space-Modell erwartet (nicht separat geprüft, da Schließungskommentar eindeutig "noch nicht umgesetzt" bestätigt und keine Datei-Hinweise auf Gegenteiliges vorliegen).

**Themen:** spaces, security, rechteverwaltung
