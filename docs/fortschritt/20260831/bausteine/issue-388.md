# Issue #388 — feat(query): Zitierzwang am Space schalten und mit der Systemvorgabe verrechnen
- Geschlossen: 2026-08-21 (not planned)
- Labels: enhancement, backend, frontend, size:L, workspace
- PRs: keine

**Laut Issue:** Teil von #354, baut auf #386 auf. Zitierzwang sollte am Space geschaltet werden können, verschärfbar durch eine Systemvorgabe (`aktiv = Systemvorgabe ∨ Space-Einstellung`). Dafür hätte die Abfrage (`POST /query`) erstmals einen Space-Bezug führen müssen — heute kennt sie keinen. Bekannte Schwäche im Issue selbst benannt: Umgehbarkeit durch Raumwechsel.

**Geliefert:** Nicht umgesetzt, verworfen im Zuge der Maintainer-Entscheidung vom 21.08.2026 zu #386 (siehe dortiger Baustein und Issue-Kommentar). Mit dem reduzierten Umfang von #386 (reine Belegvalidierung ohne Verweigerungsmodus) entfällt die fachliche Grundlage für einen Ein/Aus-Schalter — es gibt in der jetzigen Umsetzung keinen Modus mehr, der geschaltet werden müsste.

**Verifikation:** `POST /query` führt laut Grep im Worktree (`QueryController.java`) weiterhin keinen Space-Parameter — der im Issue beschriebene Ausgangszustand („kein Space-Bezug in der Abfrage") besteht unverändert fort, was zur Nichtumsetzung passt.

**Themen:** zitierzwang, spaces, query, workspace, produktausrichtung-revidiert, verworfen
