# Issue #145 — feat(i18n): Sprachinfrastruktur mit Deutsch als Standard- und Ausgangssprache
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:L
- PRs: keine

**Laut Issue:** Die Anwendung hat keine Sprachinfrastruktur; sichtbare Texte sind hart verdrahtet, teils englisch, teils deutsch gemischt. Gefordert war eine durchgängige i18n-Infrastruktur (`i18next`/`react-i18next` im Frontend, `MessageSource` im Backend) mit Deutsch als Ausgangs- und Standardsprache, Englisch als Option, samt Sprachauswahl, Browser-Spracherkennung nur als Vorschlag, und einer Fuge für spätere Sprachvarianten (Leichte Sprache).

**Geliefert:** Nicht umgesetzt. Maintainer-Entscheidung beim Schließen: OPAA bleibt auf absehbare Zeit deutsch-only — eine Sprachinfrastruktur/i18n wird vorerst nicht gebaut; bei künftigem Bedarf wird der Zuschnitt neu bewertet. Ein Teilaspekt ist über einen anderen Weg erfüllt: Der persönliche Space heißt im Code bereits "Meine Dokumente" (`SpaceService.ensureDefaultSpace`), allerdings weiterhin hart verdrahtet statt aus einem Nachrichtenbündel. Die Navigation nennt Spaces korrekt "Spaces", nicht "Workspaces" — das war aber ohnehin schon Terminologie, keine i18n-Leistung.

**Verifikation:** Keine `i18n`-Dateien oder `public/locales`-Verzeichnis im Frontend gefunden (`find` auf `frontend/src` und `frontend` ohne Treffer). Deckt sich mit "nicht gebaut".

**Themen:** i18n, frontend, doku, spaces
