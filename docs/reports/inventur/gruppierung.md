# Gruppierung der Bausteine nach Themenbereichen

Jeder Baustein ist genau **einem** Bereich zugeordnet (Querbezüge stehen im Baustein selbst).
Die Zuordnung ist ein Arbeitsstand und darf bis zum fertigen Report umgruppiert werden — die
Bausteine selbst bleiben dabei unverändert. `#N` = Issue, `PR#N` = PR ohne Issue-Verknüpfung.

Die Bereiche mischen bewusst Fachliches (entlang der Themenbereiche A–K der Vision) und
Technisches (Projektsetup, Arbeitsweise, Qualität) — beides ist gelieferte Leistung.

## 1 · Produktvision, Strategie & Konzeption

Vision, Neuausrichtung auf die öffentliche Verwaltung, Feature-Spezifikationen, Roadmap, Marketing.

#2, #29, #317, #326, #338, #339, #340, #341, #342, #343, #344, #348, #350, #351, #352, #354,
#356, #357, #360, #361, #362, #363, #367, #410, #461, #470, #533, #569,
PR#32, PR#91, PR#97, PR#217, PR#399

## 2 · Projektsetup, Build & Werkzeugkette

Scaffolding von Backend/Frontend, CI-Pipeline, Branch-Schutz, CLA & Lizenz (AGPL-3.0),
Versionskatalog, OpenAPI-DTO-Generierung, Dependency-Upgrades, Build-Performance.

#4, #6, #7, #8, #9, #17, #18, #19, #23, #58, #67, #72, #74, #86, #102, #133, #152, #162,
#188, #189, #310, #324, #625, #644,
PR#104, PR#105, PR#403

## 3 · Agenten-Organisation & Projektsteuerung

Rollenmodell der KI-Agenten, Arbeitsregeln (AGENTS.md), Projektsprache Deutsch,
Stakeholder-Agenten, Tagesreport, Epic-/Sub-Issue-Prozess, Review-Prozess.

#172, #174, #176, #178, #180, #182, #184, #186, #194, #218, #219, #245, #248, #261, #263,
#268, #276, #279, #285, #290, #295, #302, #312, #319, #321, #335, #346, #373, #383, #459,
#495, #566, #661,
PR#1, PR#92, PR#385

## 4 · Identität, Anmeldung & Benutzerverwaltung

OIDC/Keycloak-Anbindung, Nutzer-Provisionierung, Systemrollen, Auth-Modi-Konsolidierung
(mock/basic → oidc/dev), Verzeichnisabgleich, Rechte-Historisierung, Session-Verhalten.

#63, #73, #108, #109, #110, #120, #137, #138, #139, #153, #164, #237, #238, #255, #258,
#260, #293, #294, #300, #307, #323, #332, #737,
PR#287

## 5 · Spaces, Wissensbibliotheken & Rechtemodell

Workspace-Ära und Ablösung durch das Space-/Asset-Modell, Gruppen als Rechtesubjekt,
Bibliotheken als eigene Objekte mit Grants, Organisationsgrenze, Rechteverwaltung im Frontend.

#107, #111, #112, #113, #114, #115, #116, #117, #118, #119, #121, #122, #124, #125, #144,
#149, #199, #200, #201, #202, #203, #208, #265, #266, #271, #289, #330, #333, #390, #400,
#401, #418, #421, #423, #436, #438, #439, #441, #448, #507, #521, #522, #543, #677, #686,
PR#146, PR#147, PR#413

## 6 · Wissensquellen, Indizierung & Konnektoren

Indizierungspipeline, Verzeichnis-/URL-/RSS-Aufnahme, Upload, Formaterkennung,
Bibliothekstypen mit Quellkonfiguration (ADR-0018), Härtung der Quellenzugriffe.

#10, #15, #35, #41, #44, #53, #95, #165, #170, #267, #375, #404, #408, #419, #420, #422,
#433, #434, #435, #443, #463, #464, #465, #466, #467, #468, #469, #475, #476, #477, #478,
#479, #480, #481, #482, #483, #484, #485, #486, #491, #493, #501, #505, #513, #514, #515,
#516, #517, #518, #538, #544, #550, #551, #614, #617, #632, #636, #637, #646, #650, #651,
#659, #693,
PR#412, PR#502

## 7 · Retrieval, Antwortgenerierung & Zitierung

Vektorsuche, Antwortgenerierung, Quellenangaben, Belegvalidierung (Zitierzwang-Schnitt),
Chunking, Suchbereichssteuerung, Modellanbindung (lokal-first, vLLM/Ollama).

#11, #12, #13, #37, #42, #47, #66, #100, #353, #374, #386, #387, #388, #389, #406, #526,
#560, #639, #736, #738

## 8 · Chat & Gesprächsführung

Gesprächsgedächtnis, persistente Chats im Space, Chatliste, Chat-Titel, Race-Condition-Härtung.

#43, #49, #54, #69, #123, #523, #524, #525, #527, #528, #556, #557, #559, #565, #573, #619

## 9 · Weboberfläche, Design & Barrierefreiheit

Chat-UI, Redesign mit Designsystem (Tokens, Theme, Branding), App-Shell, Assistenten,
Barrierefreiheit (BITV/WCAG), deutsche Oberflächentexte.

#14, #40, #70, #75, #148, #193, #221, #272, #440, #572, #575, #580, #581, #582, #583, #584,
#585, #586, #587, #588, #590, #591, #592, #593, #594, #595, #596, #597, #654, #658, #731

## 10 · Betrieb, Deployment & Demo-Instanz

Docker Compose, Konfiguration, öffentliche Testinstanz, Demo Rheinfurt (Korpus, Seed, Rollout),
Observability.

#16, #50, #65, #98, #157, #196, #229, #230, #235, #244, #252, #519, #553, #708, #709, #711,
#712, #713, #716,
PR#269, PR#728, PR#732

## 11 · Sicherheit, Audit & Compliance

Security-Review-Funde der Frühphase, Ratenbegrenzung, CORS, Sicherheits-Header,
Audit-Trail (Schnitt #355, Umsetzung #391–#395), Härtungsdokumentation.

#60, #61, #62, #64, #71, #76, #250, #355, #391, #392, #393, #394, #395, #409, #545

## 12 · Qualität: Tests, E2E & Suchqualitäts-Evaluierung

E2E-Suite (Playwright), Testinfrastruktur (Template-DB, Kontext-Konsolidierung),
Flaky-Test-Härtung, Eval-Korpora, Golden Datasets, Regressionsprüfung in der CI.

#224, #225, #226, #227, #228, #231, #232, #233, #234, #256, #257, #274, #282, #288, #304,
#306, #308, #311, #407, #414, #416, #424, #471, #497, #508, #529, #547, #552, #606, #609,
#616, #623, #721, #734,
PR#236, PR#253, PR#275, PR#499, PR#648, PR#695, PR#698
