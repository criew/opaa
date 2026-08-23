# Issue #35 — feat: Erweiterte Job-Status API (Status pro Job, Liste laufender Jobs)
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:M
- PRs: keine

**Laut Issue:** Aus dem Review von #34 (Document Indexing Pipeline): `GET /api/v1/indexing/jobs` (Liste aller/laufender Jobs mit Pagination und Statusfilter) und `GET /api/v1/indexing/jobs/{id}` (Status eines einzelnen Jobs), da die damalige API nur den letzten Job zurückgab.

**Geliefert:** Kein eigener PR verlinkt — das Issue wurde nach Code-Prüfung ohne dedizierte Umsetzung geschlossen. Laut Schließkommentar (2026-08-21, per `gh issue view --comments` eingeholt) sind die Anforderungen „seit ADR-0018 in besserer Form erfüllt": Indizierung ist inzwischen bibliotheksbezogen (`POST /{libraryId}/indexing`, `GET /{libraryId}/indexing/status`, `GET /{libraryId}/indexing/runs`), pro Lauf gibt es Status, Zähler und vollständiges Ereignisprotokoll (Bezug #604), Bibliotheken laufen parallel mit Sperre je Bibliothek (Migration 028), verwaiste RUNNING-Läufe werden bereinigt (#649). Eine Statusfilterung wurde bei maximal 10 aufbewahrten Läufen je Bibliothek als nicht lohnend bewertet. Eine organisationsweite Übersicht aller laufenden Läufe (für die Systemverwaltung) ist explizit **nicht** umgesetzt und bewusst nicht beauftragt.

**Verifikation:** Bestätigt — im heutigen Code existiert keine globale `GET /api/v1/indexing/jobs`-Route; stattdessen `backend/src/main/resources/openapi/opaa-api.yaml` definiert `/api/v1/libraries/{libraryId}/indexing`, `/api/v1/libraries/{libraryId}/indexing/status` und `/api/v1/libraries/{libraryId}/indexing/runs`. Das ursprünglich in #10/#34 angelegte `IndexingController.java` existiert nicht mehr (durch die Bibliotheks-Architektur ersetzt). Diskrepanz „completed ohne PR" ist damit aufgeklärt: Das Issue wurde als durch spätere, anders benannte Arbeit (Bibliotheks-Indexing-API) faktisch erledigt eingestuft, nicht separat implementiert — die organisationsweite Job-Übersicht bleibt eine offene Lücke.

**Themen:** backend, indexing, api, retrofit-abgleich
