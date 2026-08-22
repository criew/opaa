# Issue #439 — feat(frontend): SYSTEM-Bibliothek über die Oberfläche administrierbar machen
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:M, workspace
- PRs: keine

**Laut Issue:** Die SYSTEM-Bibliothek erscheint für System-Admins nicht in `GET /api/v1/libraries`, weil ihr ein `AssetGrant` fehlt. Gefordert war zu klären, ob `listLibraries` sie zusätzlich ausliefert oder ein separater admin-Endpunkt sie zugänglich macht, plus entsprechende Frontend-Darstellung.

**Geliefert:** Nicht umgesetzt — laut Issue-Kommentar des Maintainers obsolet geworden: „Die System-Wissensbibliothek wird entfernt (Entscheidung des Maintainers, siehe #521). Eine Administrationsoberfläche dafür wird damit nicht mehr benötigt." Das Konzept `SYSTEM` als Eigentümerart wurde durch #521 vollständig aus dem Modell entfernt statt administrierbar gemacht — der gegenteilige Weg zum ursprünglich vorgeschlagenen.

**Verifikation:** Der Javadoc-Kommentar in `backend/src/main/java/io/opaa/library/KnowledgeLibraryService.java` bestätigt dies ausdrücklich: „A third owner kind, SYSTEM, existed from #201 until #521 [...] Every library now has a real owner". `LibraryOwnerType.SYSTEM` existiert im heutigen Code nicht mehr.

**Themen:** workspace, spaces, backend, modellierung, rueckbau
