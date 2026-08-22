# Issue #272 — feat(frontend): Space-Sichtbarkeit in der Oberfläche nutzbar machen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #671 (2026-08-20)

**Laut Issue:** Die mit #199 eingeführte Sichtbarkeitsachse (`PRIVATE`/`DISCOVERABLE`/`OPEN`) war backendseitig vollständig umgesetzt, aber im Frontend nicht bedienbar — `CreateSpaceDialog.tsx` bot keine Auswahl, jeder neue Space blieb `PRIVATE`, `SpaceManagementPage.tsx` hatte keinen Bezug auf `visibility`. Gefordert: Auswahl im Anlagedialog (Voreinstellung `PRIVATE`), Änderbarkeit in der Verwaltung, durchgängige Verdrahtung von `spaceStore`/`api.ts`, verständliche deutsche Beschriftungen.

**Geliefert:** PR #671 ergänzt die Sichtbarkeitsauswahl in `CreateSpaceDialog.tsx` und `SpaceManagementPage.tsx`, verdrahtet `spaceStore.ts`/`api.ts` durchgängig und fügt `spaceVisibilityLabel`/`spaceVisibilityDescription` in `utils/labels.ts` hinzu. Die im Issue angemerkte uneinheitliche PUT-Semantik (name/description werden ersetzt, visibility gemerged) wurde bewusst nicht harmonisiert — als eigenständige, über dieses Ticket hinausgehende Entscheidung eingestuft. Keine sonstige Abweichung.

**Verifikation:** `CreateSpaceDialog.tsx` existiert im aktuellen Code nicht mehr — laut `git log` wurde die Space-Anlage in Commit `ff8de56b` („Space-Anlage als mehrstufiger Assistent“) zu `SpaceCreatePage.tsx` umgebaut, nach zuvor `389fd997` (dieser PR) und `ab7134a7` (Hilfetexte-Fix). `visibility` ist heute in `frontend/src/stores/spaceStore.ts`, `frontend/src/services/api.ts`, `frontend/src/pages/SpaceManagementPage.tsx`, `frontend/src/pages/SpaceCreatePage.tsx` und `frontend/src/utils/labels.ts` präsent — die Funktion wurde nicht wieder entfernt, nur die Anlage-UI später umgebaut.

**Themen:** spaces, frontend, doku
