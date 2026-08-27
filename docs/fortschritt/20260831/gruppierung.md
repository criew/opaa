# Gruppierung der Bausteine nach Themenbereichen

Gruppiert wird entlang der **elf Themenbereiche A–K der [Produktvision](../../VISION.md)**,
ergänzt um vier Bereiche, die die Vision nicht abdeckt, aber Leistung sind: Produktvision selbst,
Projektsetup, Agenten-Organisation und Testinfrastruktur.

Jeder Baustein ist genau **einem** Bereich zugeordnet (Querbezüge stehen im Baustein selbst).
Die Zuordnung ist ein Arbeitsstand und darf bis zum fertigen Report umgruppiert werden — die
Bausteine selbst bleiben dabei unverändert. `#N` = Issue, `PR#N` = PR ohne Issue-Verknüpfung.

**Regel für den Report:** Der Report zeigt nur den **Endzustand**. Gebautes, das später wieder
zurückgebaut oder ersetzt wurde (basic-/mock-Auth, Workspace-Modell, MVP-Admin-Oberfläche,
System-Bibliothek, Ollama-Compose-Konfiguration), taucht dort nicht als Leistung auf — es waren
Versuche auf dem Weg. Die Bausteine dokumentieren sie weiterhin vollständig.

---

## A · Wissensschicht & Retrieval

Vektorsuche, Antwortgenerierung, Quellenangaben, Belegvalidierung (Zitierzwang-Schnitt),
Chunking, Suchbereichssteuerung — und die Messbarkeit der Suchqualität (Eval-Korpora,
Golden Datasets, Regressionsprüfung), die laut Vision zu A gehört.

#11, #12, #13, #37, #42, #66, #224, #225, #226, #227, #228, #234, #274, #282, #304, #306,
#311, #374, #386, #387, #388, #389, #406, #407, #414, #416, #526, #552, #560, #639, #721,
#734, #736, #738,
PR#236, PR#253, PR#275

Nachzug 27.08.: #77, #78, #242, #667, #739, #740, #747, #769, #773, #863, #912, #913, #914,
#923, #932, #933, #937, #938, #941, PR#804, PR#936, PR#939, PR#943 — u. a. Multi-Query-RAG,
MMR-Diversität, Dokument-Vervollständigung, Contextual Chunking, deterministische Faktenprüfung
der Zitate, Beleg-Deeplinks samt Content-Proxy und die dokumentierte Grenze der reinen Vektorsuche.

## B · Wissensquellen & Konnektoren

Indizierungspipeline, Verzeichnis-/URL-/RSS-Aufnahme, Upload, Formaterkennung,
Bibliothekstypen mit Quellkonfiguration (ADR-0018), Härtung der Quellenzugriffe.

#10, #15, #35, #41, #44, #53, #95, #165, #170, #267, #375, #404, #408, #419, #420, #422,
#433, #434, #435, #443, #463, #464, #465, #466, #467, #468, #469, #475, #476, #477, #478,
#479, #480, #481, #482, #483, #484, #485, #486, #491, #493, #501, #505, #513, #514, #515,
#516, #517, #518, #538, #544, #550, #551, #614, #617, #632, #636, #637, #646, #650, #651,
#659, #693,
PR#412, PR#502

Nachzug 27.08.: #207, #824, #836, #837, #838, #839, #876, #877, #886, PR#882, PR#901 — u. a.
Crawler-Limits, Dokumentidentität je (Bibliothek, Quelle), Aufräumen verschwundener Quellen,
Quellenzugriff als eigenes Paket.

## C · Spaces, Assets & Verteilung

Workspace-Ära und Ablösung durch das Space-Modell, Wissensbibliotheken als eigenständige
Objekte, Space-Bibliothek-Assoziation, Verwaltungsoberflächen.

#107, #111, #112, #113, #114, #115, #116, #117, #118, #121, #122, #124, #125, #149, #199,
#201, #203, #265, #266, #333, #418, #421, #438, #439, #441, #448, #507, #521, #522, #543,
#686,
PR#146, PR#147

Nachzug 27.08.: #198, #204, #205, #206, #209, #210, #211, #212, #213, #214, #215, #240, #243,
#458, #520, #682, #782, #819, #820, #821, #822, #823, #888 — u. a. Ordner in Bibliotheken
(Epic #520 komplett), Space-Kennzahlen, zentrale AccessPolicy; dazu die Backlog-Bereinigung des
alten Asset-Modell-Epics #198 (15 Kind-Issues „not planned").

## D · Agenten, Prompts & Werkzeuge

**Keine Bausteine.** Zu diesem Bereich existiert weder Code noch ein geschlossenes Issue —
deckt sich mit dem Befund in STATUS.md. Zweite Säule der Vision, Phase 2.

## E · Modelle & zentrale Steuerung

Modellanbindung und -konfiguration: lokal-first als Voreinstellung, OpenAI-kompatible
Server (vLLM, Ollama).

#47, #100, #353

Nachzug 27.08.: #720, #755, #756, #757, #758, #759, #762, #768, #771, PR#761 — die komplette
Modellverwaltung Stufe 1 (Datenmodell mit verschlüsselten Zugangsdaten, Admin-API, Laufzeitauflösung,
Administrationsseite) plus Ollama als optionales Compose-Profil.

## F · Identität, Rechte & Mandanten

OIDC/Keycloak, Nutzerprovisionierung, Systemrollen, Auth-Modi-Konsolidierung, Gruppen als
Rechtesubjekt, Verzeichnisabgleich, Grants und rechtebewusste Suche, Rechte-Historisierung,
Organisationsgrenze.

#63, #73, #108, #109, #110, #120, #137, #138, #139, #144, #153, #164, #200, #202, #208,
#237, #238, #255, #258, #260, #271, #289, #293, #294, #300, #307, #323, #330, #332, #390,
#400, #401, #423, #436, #677, #737,
PR#287, PR#413

Nachzug 27.08.: #241, #358, #429, #430, #445, #777, #833, #884 — u. a. berechtigungsunabhängige
Nutzersuche für die Rechtevergabe, zentraler CurrentUser (mit behobenem fail-open-Befund),
lastLoginAt-Drosselung; Rechtehistorien-Nacharbeiten zurückgestellt.

## G · Sicherheit, Nachweis & Prüfbarkeit

Security-Review-Funde der Frühphase, Ratenbegrenzung, CORS, Sicherheits-Header,
Härtungsdokumentation, Audit-Trail (Schnitt #355, Umsetzung #391–#395).

#60, #61, #62, #64, #71, #76, #250, #355, #391, #392, #393, #394, #395, #409, #545

Nachzug 27.08.: #143, #216, #239, #426, #447, #451, #452, #455, #457, #798, #834, #892 — Audit-Indizes
und Domain-Events geliefert; Audit-Betriebshärtung (Epic #457 samt Sub-Issues) und DSGVO-Vollständigkeit
(#143) ausdrücklich zurückgestellt, Auswertungs-Governance #239 entschieden.

## H · Monitoring, Kosten & Governance

Betriebsmetriken, Kontingente.

#65, #119

## I · Kanäle & Oberflächen

Chat und Gesprächsführung (Gedächtnis, persistente Chats im Space, Titel, Race-Härtung) sowie
die Weboberfläche einschließlich Redesign (Designsystem, Branding, App-Shell, Assistenten,
Belegfenster, Dunkelmodus) und deutscher Oberflächentexte.

Chat & Gespräche: #43, #49, #54, #69, #123, #523, #524, #525, #527, #528, #556, #557, #559,
#565, #573, #619

Weboberfläche & Design: #14, #40, #70, #75, #148, #193, #221, #272, #440, #572, #575, #580,
#581, #582, #583, #587, #588, #590, #591, #592, #593, #594, #595, #596, #597, #654, #658, #731

Nachzug 27.08.: #145, #634, #718, #725, #749, #780, #784, #786, #787, #788, #789, #792, #800,
#809, #814, #840, #853, #874, #889, PR#790 — u. a. globale Leiste (Rail) und Verwaltungsrahmen,
Browservorschau für Originale, deutsche MUI-Lokalisierung, Kontrast-Nachbesserungen; Entscheidung
deutsch-only (#145 „not planned").

## J · Betrieb & Deployment

Docker Compose, Konfiguration, öffentliche Testinstanz, Demo-Instanz „Stadt Rheinfurt"
(Korpus, Seed, Rollout).

#16, #50, #98, #157, #196, #229, #230, #235, #244, #252, #519, #553, #708, #712, #716,
PR#269, PR#728, PR#732

Nachzug 27.08.: #707, #775, #812, #929, PR#942 — u. a. Betriebshandbuch (deployment.md),
Cache-Control, CSP-Font-Fix, Demo-Seed mit Space↔Bibliothek-Zuordnungen.

## K · Verwaltungs-Spezifika

Barrierefreiheit (BITV 2.0 / WCAG 2.1 AA): Richtlinie, A11y-Basisausstattung, automatisierte
Prüfungen. Leichte Sprache und Amtssprache: keine Bausteine.

#584, #585, #586

---

## T1 · Projektsetup, Build & Werkzeugkette

Scaffolding von Backend/Frontend, CI-Pipeline, Branch-Schutz, CLA & Lizenz (AGPL-3.0),
Versionskatalog, OpenAPI-DTO-Generierung, Dependency-Upgrades, Build-Performance.

#4, #6, #7, #8, #9, #17, #18, #19, #23, #67, #72, #74, #86, #102, #133, #152, #162,
#188, #189, #310, #324, #625, #644,
PR#403

Nachzug 27.08.: #68, #192, #456, #653, #751, #817, #826, #832, #835, #843, #844, #860, #862,
#875, #896, #903, #904, #924, PR#818, PR#847, PR#864, PR#867, PR#869, PR#870, PR#871, PR#872,
PR#873, PR#879, PR#893, PR#897, PR#899, PR#902, PR#905, PR#908 — u. a. pnpm-Migration, Renovate,
Liquibase-Baseline, Gradle-Modul opaa-api, Backend-Architekturreview (DTO-Leak-Serie,
Domain-Exceptions), Testkontext-Konsolidierung (Suite 9m53s → 3m13s).

## T2 · Agenten-Organisation & Projektsteuerung

Rollenmodell der KI-Agenten, Arbeitsregeln (AGENTS.md), Projektsprache Deutsch,
Stakeholder-Agenten, Tagesreport, Epic-/Sub-Issue-Prozess, Review-Prozess.

#172, #174, #176, #178, #180, #182, #184, #186, #194, #218, #219, #263, #268, #276,
#279, #295, #302, #319, #335, #346, #459, #495, #566, #661,
PR#1, PR#92

Nachzug 27.08.: #842, #848 — Kommentar-Konvention und Koordinations-Betriebsregeln in AGENTS.md.

## T3 · Testinfrastruktur & E2E

E2E-Suite (Playwright), Testcontainer-Infrastruktur (Template-DB, Kontext-Konsolidierung),
Flaky-Test-Härtung, Testkonten-Konventionen.

#231, #232, #233, #256, #257, #288, #308, #424, #471, #497, #508, #529, #547, #606, #609,
#616, #623,
PR#499, PR#648, PR#695, PR#698

Nachzug 27.08.: #760, #805 — E2E-Abdeckung Modellverwaltung, Nachweis-Lücken im Frontend.

## V · Produktvision, Strategie & Konzeption

Vision, Neuausrichtung auf die öffentliche Verwaltung, Feature-Spezifikationen, Roadmap,
Marketing.

#2, #29, #317, #326, #338, #339, #340, #341, #343, #344, #348, #350, #351, #352, #354,
#356, #357, #360, #361, #362, #363, #410, #461, #470, #533, #569,
PR#91, PR#97, PR#217

Nachzug 27.08.: #349, #845, #927, PR#861 — MCP-Verhältnis entschieden, ADR Single-Instance,
Doku-Struktur nach Achsen.

## P · Projekt als Produkt: Öffentlichkeit, Demo & Governance

Gebaute Artefakte, die weder Produktfeld noch reine Technik sind: der Tagesreport als
generierte GitHub-Pages-Seite mit Management-Summary und Atom-Feed, Projektwebsite und
Marketing-Assets, der CLA-Prozess mit Lizenzrahmen (AGPL-3.0) sowie Demonstrations-Assets
der Demo-Instanz (Verwaltungskorpus-Generator, Vorführ-Drehbuch).

Tagesreport: #248, #261, #285, #290, #312, #321, #373, #383, PR#385
Website & Marketing: #58, #342, #367, PR#32, PR#399
Lizenz & CLA: #245, PR#104, PR#105
Demo-Assets: #709, #711, #713

Nachzug 27.08.: #370, #744, #807, PR#810, PR#930 — Leistungsinventur selbst, Demo-Video und
Landing-Page-Screenshots aus dem Rheinfurt-Korpus.

