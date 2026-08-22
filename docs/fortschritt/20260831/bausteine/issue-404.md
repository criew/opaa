# Issue #404 — feat(indexing): Zulässige Dokumenttypen über den erkannten Inhalt statt über die Dateiendung bestimmen
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:M
- PRs: #704 (2026-08-21)

**Laut Issue:** Aus #375 herausgelöst. Auswahl erfolgte weiterhin über die Dateiendung, obwohl die Spezifikation Inhaltserkennung verspricht — Lücke zwischen Doku und Code. Verlangt: Zulassung anhand erkannten Medientyps, Meldung bei Abweichung Endung/Inhalt, Zuordnung Typ→Extraktionsstrategie, begründete Entscheidung über zusätzlich freigegebene Typen, gleiches Verhalten über beide Indizierungswege mit Test.

**Geliefert:** Wie beschrieben, auf allen drei dateibasierten Aufnahmewegen (Verzeichnis, Webverzeichnis, RSS-Anlagen). Tika-basierte Inhaltserkennung, neue Kategorie `FORMAT_MISMATCH` für abweichende Endung. Zulässige Typen bewusst **unverändert** belassen (die bisherigen sechs Endungen) — eine Erweiterung wird ausdrücklich als eigene fachliche Entscheidung außerhalb des Umfangs benannt. Bemerkenswerte fachliche Differenzierung: Für eindeutig erkennbare Binärformate entscheidet der Inhalt allein; für Markdown/Klartext bleibt die Endung die Disambiguierung, weil Tika inhaltlich nicht zwischen `.md`, `.txt` und z. B. CSV unterscheiden kann — sonst wäre jede lesbare Textdatei egal welchen Namens stillschweigend zugelassen worden, was die Abnahmekriterien ausdrücklich ausschließen. Der Upload-Weg bleibt bewusst strenger (eigene Prüfung, kein Fallback auf Inhaltserkennung). Reproduktionsnachweis rot/grün erbracht.

**Verifikation:** `SupportedDocumentFormats.java` (erweitert um `decideForFileName`) existiert im Worktree unter `backend/src/main/java/io/opaa/indexing/`.

**Themen:** indexierung, dateiformate, inhaltserkennung, backend, retrieval
