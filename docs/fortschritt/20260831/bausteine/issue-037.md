# Issue #37 — feat(query): filter source references by actual LLM citations
- Geschlossen: 2026-02-27 (completed)
- Labels: enhancement, mvp, backend, frontend, size:M
- PRs: #55 (2026-02-27)

**Laut Issue:** Aus dem Review von PR #36: Bisher werden alle per Ähnlichkeitssuche gefundenen Quellen als Referenzen angezeigt, unabhängig davon, ob das LLM sie tatsächlich zitiert hat. Vorschlag: LLM-Antwort auf zitierte Dateinamen parsen, `SourceReference` um ein `cited`-Flag erweitern, zitierte Quellen prominent und unzitierte gedimmt/eingeklappt oder gar nicht anzeigen.

**Geliefert:** PR #55 geht über den Vorschlag hinaus: strukturiertes Zitatformat `【source: document_id#chunk_index | file_name】`, per Systemprompt erzwungen; `CitationParser` extrahiert zitierte Dokument-IDs per Regex; `SourceReference` um `cited`, `matchCount`, `indexedAt` erweitert (statt `excerpt` entfernt); zitierte Quellen werden direkt angezeigt, unzitierte in einem einklappbaren Bereich in `MessageBubble`. Umfang wie gefordert, technisch solider gelöst als im Issue skizziert (kein reines Text-Parsing von Dateinamen, sondern strukturierte Marker).

**Verifikation:** `backend/src/main/java/io/opaa/query/CitationParser.java` existiert weiterhin im heutigen Code. Die Frontend-Seite (`SourceCard.tsx`, prominente/eingeklappte Darstellung in `MessageBubble.tsx`) wurde inzwischen abgelöst: `git log` zeigt `feat(frontend): Antworten mit Fußnoten-Fundstellen statt Quellkarten` — die Quellenanzeige läuft heute über `SourceFootnotes.tsx`/`SourceEvidenceDrawer.tsx` statt über Quellkarten. Das Kernprinzip „nur tatsächlich zitierte Quellen hervorheben" besteht konzeptionell fort, die konkrete UI-Umsetzung wurde ersetzt.

**Themen:** retrieval, quellenreferenzen, backend, frontend, mvp
