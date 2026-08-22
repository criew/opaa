# Issue #738 — feat(library): Deeplink auf das Originaldokument in der Wissensbibliothek
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #743 (2026-08-22)

**Laut Issue:** In der Dokumentliste einer Wissensbibliothek soll ein Klick das Original öffnen (Blob-Download über den Endpunkt aus #736, neuer Tab, Browser-Vorschau bei PDF/Bildern, sonst Download); bei externen Quellen (`HTTP_DIRECTORY`/`RSS_FEED`) die Quell-URL öffnen; Fehlerfall mit deutscher Meldung statt leerem Tab; Vitest-Tests für beide Pfade.

**Geliefert:** Deckungsgleich, mit einer im PR offen benannten, notwendigen API-Erweiterung über den Issue-Text hinaus: `LibraryDocumentResponse` bekam ein neues, nicht-sensibles Feld `sourceUrl`, weil das bestehende `sourceEntryUrl` für `HTTP_DIRECTORY` immer `null` war und die Abnahmekriterien sonst nicht erfüllbar gewesen wären. Gemeinsames Hilfsmodul `documentContent.ts` bewusst generisch gehalten für die spätere Zitat-Deeplink-Arbeit (#739).

**Verifikation:** Der Worktree-Branch wurde vor dem Merge dieses PRs erstellt (letzter enthaltener Commit vom 22.08. früh, vor #742/#743) — `frontend/src/utils/documentContent.ts` ist im Worktree **nicht** vorhanden. Zeitfenster-Effekt des Worktrees, keine inhaltliche Auffälligkeit; Merge-Commit `5aa3130` ist laut PR-Angabe auf `main`.

**Themen:** frontend, dokumente, deeplinks, bibliothek, ux
