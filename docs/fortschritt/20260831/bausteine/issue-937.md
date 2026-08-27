# Issue #937 — Query: Zitatvalidierung prüft nur Abruf, nicht Inhalt — falsche Zahl mit gültig wirkendem Zitat
- Geschlossen: 2026-08-26 (completed)
- Labels: bug, enhancement, backend, size:M
- PRs: keine (im Chunk nicht verlinkt — tatsächlich über PR #939 geliefert, siehe Verifikation)

**Laut Issue:** Fachliche Regressionsprüfung der Demo deckte auf: Die Antwort nannte 25,70 € mit Zitat auf `001_personalausweis.md`, obwohl dieser Wert dort nicht steht (er stammt aus einem anderen, ebenfalls abgerufenen Dokument). Die bestehende `CitationValidator` prüft nur, ob ein Zitat auf einen tatsächlich abgerufenen Chunk zeigt, nicht ob die Aussage im Chunk tatsächlich steht. Gefordert: eine deterministische Stufe-1-Faktenprüfung (Zahlen/Beträge/Daten/Paragraphen normalisiert gegen den Chunk-Text vergleichen), die ein Zitat auf `citationValid: false` zurückstuft statt die Antwort zu blockieren; Stufe 2 (LLM-Entailment) optional als Folgeausbau.

**Geliefert:** Neue Klasse `CitationFactChecker` (io.opaa.query) extrahiert harte Fakten aus dem Satz unmittelbar vor einem Zitatmarker (vier Fakttypen: Geld, Datum, Paragraph, sonstige harte Zahl) und vergleicht normalisiert gegen den zitierten Chunk. Konservativ: ein Satz ohne extrahierbaren Fakt wird nie geflaggt, ein bereits ungültiges Zitat wird nie „repariert“. Kein zusätzlicher LLM-Aufruf. Der konkrete Frage-1-Fall (25,70 € vs. 27,20/44,20/12 €) ist als Regressionstest abgesichert. Live-Stichprobe der acht Drehbuch-Fragen auf der Demo war laut PR explizit dem Koordinator nach Deploy überlassen, nicht Teil des PR-Diffs.

**Verifikation:** `backend/src/main/java/io/opaa/query/CitationFactChecker.java` existiert im Worktree.

**Themen:** retrieval, query, zitatvalidierung, qualitätssicherung, epic-912
