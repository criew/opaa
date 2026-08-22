# Issue #526 — Suchbereich über Bibliotheksreferenzen und Schalter „Wissen nutzen“ im Query-Endpunkt
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:M
- PRs: #535 (2026-08-19)

**Laut Issue:** `QueryRequest.spaceIds` wird vom Backend komplett ignoriert und sollte ersatzlos entfallen. Stattdessen `useKnowledge` (Default true) und `libraryIds`: bei `true` alle lesbaren Bibliotheken (wie bisher), bei `false` nur `libraryIds ∩ lesbare`, bei leerer Schnittmenge kein Retrieval und Kennzeichnung in den Antwort-Metadaten. Frontend nur so weit anfassen, wie für einen grünen Build nötig.

**Geliefert:** PR #535 liefert genau diese Filterlogik in `QueryService`, `spaceIds` aus Spec und DTOs entfernt, neues Feld `QueryResponse.metadata.answeredWithoutKnowledge`. Frontend minimal angepasst (`services/api.ts` sendet nur `useKnowledge: true`, `spaceIds`-Parameter aus `chatStore` entfernt); die eigentliche UI-Space-Auswahl blieb bewusst vorerst stehen (wirkungslos), der Umbau war explizit Issue #528. Vier geforderte Testfälle sind laut PR-Body als eigene Testmethoden in `QueryServiceTest` vorhanden.

**Verifikation:** `QueryService.java` im Worktree führt `useKnowledge`/`requestedLibraryIds`-Parameter und die beschriebene Verzweigungslogik (aktueller Code, nach späteren Anpassungen durch #525 leicht erweitert um Chat-Vorrang, aber die Grundlogik aus #526 ist erkennbar erhalten).

**Themen:** retrieval, chats, spaces, backend, epic-523
