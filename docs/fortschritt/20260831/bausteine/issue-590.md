# Issue #590 — feat(frontend): Chat-Verlauf im neuen Design — Antworten mit Fußnoten-Fundstellen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:L
- PRs: #668 (2026-08-20)

**Laut Issue:** `MessageList`/`MessageBubble`/`MarkdownRenderer` umbauen: Antworten als Fließtext ohne Blase, hochgestellte Fußnotenziffern statt `SourceCard`-Karten, Fundstellen-Block je Antwort mit Dokumentgruppierung, einklappbare nicht zitierte Treffer, Verweigerungs-Antwort als ruhig gestalteter Antworttyp; Fundort-Metadaten mit dem tatsächlichen API-Stand abgleichen und Lücken als Backend-Folge-Issues festhalten.

**Geliefert:** Fußnoten-Auflösung der Zitatmarker (`buildCitationIndex`), Fundstellen-Block mit Zählzeile, gruppierten Ziffern und Aufklapper für nicht zitierte Treffer; `SourceCard` vollständig entfernt (Komponente + Test). Bewusst offen gelassen, wie im Issue vorgesehen: Fundort je Stelle (Abschnitt/Seite/Paragraf) und durchsuchte Bestände der Verweigerungsantwort — als Folge-Issue #667 festgehalten, da die API diese Daten noch nicht liefert. Der PR-Titel benennt nur die Fußnoten/Fundstellen, die Verweigerungs-Antwort als eigener „ruhig gestalteter Antworttyp" wird im PR-Body nicht explizit erwähnt — möglicherweise Teilumfang, nicht vollständig verifizierbar aus den Daten.

**Verifikation:** `frontend/src/components/chat/SourceFootnotes.tsx` existiert; `frontend/src/components/chat/SourceCard.tsx` existiert nicht mehr (erwartungsgemäß entfernt laut PR-Body).

**Themen:** frontend, chat, retrieval, ui, quellenangaben
