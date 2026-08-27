# Issue #667 — feat(query): Fundort je Zitatstelle und durchsuchte Bestände in der Query-API ergänzen
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, backend, size:M
- PRs: #753 (2026-08-23)

**Laut Issue:** Mit #590 zeigt der Chat Antworten mit Fußnoten und Fundstellen-Block nach Mockup 1a, aber zwei Angaben fehlten: ein menschenlesbarer Fundort je zitierter Stelle (z. B. "Abschn. 4.2", "§ 7 Abs. 2", "S. 2–4") und die Liste der tatsächlich durchsuchten Bibliotheken in der Verweigerungsantwort ("Durchsucht wurden: …"). Gefordert war eine spec-first-Erweiterung von `SourceReference` und `QueryMetadata` sowie der Frontend-Anschluss.

**Geliefert:** Beide Lücken geschlossen. `SourceReference.chunkLocations[{chunkIndex, location}]` liefert je Chunk einen Fundort (`null` wo nicht ermittelbar); `QueryMetadata.searchedLibraries[{id, name}]` die tatsächlich durchsuchten Bibliotheken. Neue Indexing-Komponenten `PageMarkingContentHandler` (Seitenmarker bleiben im extrahierten Text erhalten) und `ChunkLocationResolver` (Seitenbereich + Überschriftenpfad, kombiniert wo beides bekannt). Frontend: `SourceFootnotes` zeigt Fundorte, `MessageBubble` zeigt "Durchsucht wurden: …" nur bei Antworten ohne Zitat. Bekannte Grenze, im PR selbst benannt: Bestehende Indizes tragen noch keinen Fundort (erst ab Neu-Indizierung), `searchedLibraries` wird nicht persistiert (verschwindet nach Neuladen des Chats), Überschriften werden nur im Markdown-Stil erkannt.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/ChunkLocationResolver.java` existiert im Worktree mit den beschriebenen Methoden (`forText`, Zeile 30/54).

**Themen:** retrieval, query, indexing, frontend
