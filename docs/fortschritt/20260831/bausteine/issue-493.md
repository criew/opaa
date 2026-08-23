# Issue #493 — feat(library): Herkunft von Feed-Anlagen in API und Oberfläche sichtbar machen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, frontend, size:S
- PRs: #638 (2026-08-20)

**Laut Issue:** Die mit #468 eingeführte Spalte `documents.source_entry_url` wurde geschrieben, aber nirgends gelesen — sie sollte als optionales Feld in `LibraryDocumentResponse` erscheinen, in der Oberfläche sichtbar werden, und es sollte entschieden werden, ob Chunk-Metadaten die Herkunft mitführen sollen.

**Geliefert:** Wie gefordert. `LibraryDocumentResponse.sourceEntryUrl` ergänzt, Bibliotheksdetailseite zeigt es als Link unter dem betroffenen Dokument. Entscheidung zu den Chunk-Metadaten: keine Verdopplung im Vektorspeicher — `document_id` liegt bereits auf jedem Chunk, `sourceEntryUrl` folgt demselben Lookup-Muster wie `indexedAt` in `QueryService#lookupIndexedAt`, statt einen zweiten, driftenden Wert je Chunk zu pflegen. Begründung als Code-Kommentar in `FileProcessingService#storeChunks` dokumentiert statt in einem separaten Dokument.

**Verifikation:** `backend/src/main/java/io/opaa/library/LibraryDocumentResponses.java` existiert; `frontend/src/pages/LibraryDetailPage.tsx` enthält die Anzeige.

**Themen:** backend, frontend, feeds, retrieval, zitation
