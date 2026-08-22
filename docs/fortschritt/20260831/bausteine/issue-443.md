# Issue #443 — fix(library): Löschen von FILESYSTEM-/HTTP_DIRECTORY-Dokumenten wirkt nur bis zum nächsten Indizierungslauf
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, frontend, size:S
- PRs: keine

**Laut Issue:** Der Löschen-Knopf auf der Dokumentenseite (`DocumentsPage.tsx`) wurde für alle Herkünfte gleich angezeigt, obwohl das Löschen bei `FILESYSTEM`- und `HTTP_DIRECTORY`-Dokumenten nur scheinbar dauerhaft war — der nächste Indizierungslauf legt dieselbe Dokumentzeile erneut an, weil nur bei `UPLOAD` die Quelldatei entfernt wird. Vorgeschlagen wurden zwei Richtungen: entweder ein Hinweistext/Ausblenden in der Oberfläche, oder ein echter Lebenszyklus-Übergang „ausgeschlossen" im Backend, zur Entscheidung durch Product Manager/Maintainer.

**Geliefert:** Kein PR verknüpft. Die im Issue genannte Datei `frontend/src/pages/DocumentsPage.tsx` existiert im heutigen Stand nicht mehr — sie wurde mit PR #506 („feat(frontend): Bibliotheksdetailseite mit typspezifischem Bereich") durch `LibraryDetailPage.tsx` abgelöst, und mit PR #503 („feat(library): Upload nur in UPLOAD-Bibliotheken und Löschverhalten für Konnektorbibliotheken") wurde offenbar das Löschverhalten für Konnektorbibliotheken grundsätzlich neu geregelt. Das Issue wurde damit vermutlich durch die größere Bibliotheks-Restrukturierung überholt statt gezielt behoben — ohne verknüpften PR lässt sich aus den vorliegenden Daten nicht sicher sagen, ob der ursprüngliche Fehler in dieser Restrukturierung mitgelöst wurde oder das Issue nur gegenstandslos wurde, weil die betroffene Seite verschwand.

**Verifikation:** `frontend/src/pages/DocumentsPage.tsx` ist im Worktree nicht auffindbar; `git log` zeigt PR #506 und PR #503 als letzte Änderungen an diesem Pfad, danach vermutlich gelöscht/umbenannt. `LibraryDetailPage.tsx` existiert stattdessen. Ob das Löschverhalten für Nicht-UPLOAD-Dokumente heute klar kommuniziert ist, wurde nicht tiefer geprüft (außerhalb des kostengünstigen Rahmens dieser Verifikation).

**Themen:** library, indexing, frontend, dokumenten-lebenszyklus, ungeklärt
