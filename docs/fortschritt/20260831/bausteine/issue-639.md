# Issue #639 — feat(query): sourceEntryUrl in Belegangaben (SourceReference) durchreichen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, frontend
- PRs: #666 (2026-08-20)

**Laut Issue:** `sourceEntryUrl` (#493) war bereits in `LibraryDocumentResponse` sichtbar und auf der Bibliotheksdetailseite angezeigt, fehlte aber in der Belegangabe einer Chat-Antwort (`SourceReference`, `QueryService#mapSources`) — eine Anlage aus einem RSS-Feed-Eintrag ließ sich damit nicht direkt aus der Antwort ihrem Eintrag zuordnen. Gefordert: `sourceEntryUrl` als optionales Feld in `SourceReference` (OpenAPI zuerst), Auflösung per `document_id`-Lookup wie beim bestehenden `indexedAt`-Muster, Anzeige im Frontend (`SourceCard`). Explizit außerhalb des Umfangs: Chunk-Metadaten im Vektorspeicher anreichern.

**Geliefert:** `SourceReference` trägt jetzt `sourceEntryUrl` (OpenAPI-Spec, generierte DTOs). `QueryService#lookupSourceDocuments` (umbenannt aus `lookupIndexedAt`) löst `indexedAt` und `sourceEntryUrl` in einem gemeinsamen `DocumentRepository`-Lookup auf, kein zweiter Lookup. `mergeSourceReferences` gibt den Wert beim Deduplizieren weiter. Im Frontend zeigt die Belegkarte die Herkunft als Link, sofern gesetzt — laut PR-Body dasselbe Muster wie `LibraryDetailPage.tsx`. Entspricht dem Issue-Umfang vollständig; die Ausschlussgrenze (keine Chunk-Metadaten-Anreicherung) wurde eingehalten.

**Verifikation:** Die Komponente wird im PR-Body und in der Dateiliste als `SourceCard.tsx` geführt; im heutigen Code trägt die entsprechende Anzeigelogik andere Dateinamen (`SourceFootnotes.tsx`, `SourceEvidenceDrawer.tsx` in `frontend/src/components/chat/`), die `sourceEntryUrl` verwenden — die Komponente wurde also seither umbenannt/aufgeteilt, die Funktionalität ist aber im heutigen Code vorhanden.

**Themen:** backend, frontend, query, retrieval, spaces, knowledge-sources
