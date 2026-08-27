# Issue #739 — feat(search): Deeplinks auf Originaldokumente in Fundstellen und Belegfenster
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #745 (2026-08-22)

**Laut Issue:** Maintainer-Feedback aus dem Klick-Test der Demo-Instanz: Unter zitierten Quellen und im Belegfenster sollte das Originaldokument per Deeplink erreichbar sein. `SourceReference` trug bisher weder `documentId` noch `libraryId`; der Merge der Quellenliste lief über den Dateinamen. Teil des Epics #740. Gefordert war die OpenAPI-Erweiterung, Backend-Befüllung, Umstellung der Merge-Logik auf `documentId`, und Frontend-Links.

**Geliefert:** Wie gefordert, mit einer bewussten Abweichung: `SourceReference` erhielt `documentId`, `sourceType` und zusätzlich `sourceUrl` (mehr als im Issue explizit gefordert). `mergeSourceReferences`/`mapSources`/`countMatchesPerDocument` schlüsseln jetzt auf `document_id` statt Dateiname — zwei gleichnamige Dokumente fallen nicht mehr zusammen. Frontend: "Im Dokument öffnen" nutzt für lokale Originale ein gemeinsames Hilfsmodul (`documentContent.ts`, aus #738), für Remote-Quellen die Quell-URL. Ausdrücklich als Annahme vermerkt: `citations.ts` ändert den bestehenden Zuordnungsschlüssel zwischen Zitat-Text und Quellenliste (weiterhin Dateiname) nicht — das wäre eine separate, im Issue nicht geforderte Änderung.

**Verifikation:** Nicht erneut im Code geprüft — Änderung baut auf #736/#738 auf (außerhalb dieses Chunks), PR-Beschreibung dokumentiert Backend- und Frontend-Testabdeckung ausführlich.

**Themen:** retrieval, query, frontend, deeplinks, wissensbibliotheken
