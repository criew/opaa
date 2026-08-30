# Issue #684 — feat(library): Letzten Indexstand (lastIndexedAt) in LibraryListResponse für die Stand-Spalte

- Geschlossen: 2026-08-28 (completed)
- Labels: enhancement, backend, size:S
- PRs: #962 (2026-08-28)

**Laut Issue:** Die „Stand"-Spalte der Wissensbibliotheken-Tabelle (#595) zeigt ohne aktiven
Lauf nur „–", weil `LibraryListResponse` den letzten erfolgreichen Indexlauf nicht ausweist.
Gefordert: spec-first-Erweiterung um `lastIndexedAt` (und Wortlaut-Basis je Quellentyp).

**Geliefert:** PR #962 ergänzt `lastIndexedAt` in `LibraryListResponse` (OpenAPI-Spec, ADR-0006)
und befüllt die Stand-Spalte der Bibliotheksübersicht.

**Verifikation:** Commit `e527993b` auf `main`; Feld in der OpenAPI-Spec vorhanden.

**Themen:** Wissensbibliotheken, Oberfläche, API
