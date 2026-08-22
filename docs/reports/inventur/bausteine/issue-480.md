# Issue #480 — feat(frontend): Bibliotheksanlage mit Typauswahl aus Templates
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #498 (2026-08-19)

**Laut Issue:** Der Anlagedialog sollte mit einer Template-Auswahl (= Quellentyp) beginnen, typspezifische Konfigurationsfelder zeigen, `RSS_FEED` automatisch mitführen und den Typ nach Anlage sichtbar, aber nicht änderbar machen.

**Geliefert:** Wie gefordert, in `CreateLibraryDialog` umgesetzt (zu diesem Zeitpunkt noch ein Dialog, kein eigener Seiten-Assistent). Die Vorlagenliste leitet sich per `Object.keys` aus der Label-Map ab, sodass ein künftiger Enum-Wert ohne Übersetzung die Kompilierung bricht — `RSS_FEED` erscheint dadurch ohne Dialoganpassung. Bekannte, im PR offen benannte Lücke: `LibraryListResponse` trug zu diesem Zeitpunkt noch kein `sourceType`, der Typ-Chip erschien deshalb erst nach Einzelabruf einer Bibliothek — diese Lücke wurde mit #481 geschlossen (`sourceType` in `LibraryListResponse` ergänzt).

**Verifikation:** `frontend/src/components/CreateLibraryDialog.tsx` existiert im heutigen Code nicht mehr — ein späterer Commit (`0c08e89f`, „Bibliothek-Anlage als Assistent mit Herkunfts-Auswahl") hat die Anlage in eine eigene Seite (`frontend/src/pages/LibraryCreatePage.tsx`) umgebaut. Die im Issue geforderte Funktionalität (Templatewahl, typspezifische Felder, unveränderlicher Typ) besteht dort fort — Umbau der Form, nicht Rücknahme der Funktion.

**Themen:** frontend, spaces, retrieval, adr
