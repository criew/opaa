# Sichtung des Backlogs gegen die neue Produktausrichtung

## Wozu diese Sichtung

Die Produktausrichtung von OPAA ist auf die öffentliche Verwaltung geschärft worden: drei Säulen
(Wissen · Agenten · KI für Teams und Organisation), zwei Leitprinzipien (Belegbarkeit und
Verteilbarkeit), elf Themenbereiche A bis K und vier Produktphasen. Der offene Backlog ist über einen
längeren Zeitraum entstanden und trägt an mehreren Stellen noch die Sprache des abgelösten
Workspace-Modells oder eines allgemeinen Unternehmenskontexts.

Dieses Dokument ordnet **jedes offene Issue** genau einer von vier Kategorien zu, benennt die Lücken
der neuen Ausrichtung und schlägt eine Reihenfolge für das weitere Vorgehen vor.

**Es ist eine Empfehlungsliste, keine Aktion.** Im Zuge dieser Sichtung wurde kein Issue geändert,
umbenannt, geschlossen oder neu beschriftet und kein neues Issue angelegt.

**Stand:** 14. August 2026 · **79 offene Issues** · Grundlage sind `docs/VISION.md` in der Fassung der
Neuausrichtung, Epic #338 (verbindliche Grenzen), Epic #344 (Prüf-Epic mit den Sub-Issues #348 bis
#357) und `docs/features/spaces-and-assets.md`.

### Die vier Kategorien

| Kategorie | Bedeutung | Anzahl |
|---|---|---|
| 1 — Trägt die neue Ausrichtung | Passt unverändert; Themenbereich und Phase sind zugeordnet | 61 |
| 2 — Passt, braucht neue Formulierung | Inhalt bleibt richtig, Titel oder Text stammen aus dem abgelösten Modell | 7 |
| 3 — Ist zu prüfen | Widerspricht der neuen Ausrichtung oder ist dadurch fraglich geworden | 10 |
| 4 — Unklar | Reicht in der vorliegenden Form nicht zur Einordnung | 1 |

Die Themenbereiche werden mit ihren Buchstaben A bis K aus `docs/VISION.md` benannt. „Meta" steht für
Issues, die nicht am Produkt, sondern am Repository arbeiten; sie tragen keine Produktphase.

---

## 1 — Trägt die neue Ausrichtung

### 1.1 Der Umbau selbst (Meta)

| Nr. | Titel | Bereich | Phase | Begründung | Empfehlung |
|---|---|---|---|---|---|
| #338 | Epic: Produktvision auf die öffentliche Verwaltung ausrichten | Meta | — | Das Epic ist der Anker, gegen den diese Sichtung überhaupt erst möglich ist. | Unverändert weiterführen. |
| #339 | docs: Produktvision, ADR und Use-Cases auf die neue Ausrichtung umstellen | Meta | — | Legt Nordstern, Themenbereiche und Phasen fest, auf die sich alles Weitere beruft. | Zuerst abschließen; alle übrigen Meta-Issues hängen daran. |
| #340 | docs: Feature-Spezifikationen entlang der elf Themenbereiche neu schneiden | Meta | — | Ordnet jedem Themenbereich genau eine zuständige Spezifikation zu. | Nach #339 umsetzen. |
| #341 | docs: Einstiegsdokumente und Umsetzungsstand an die neue Ausrichtung angleichen | Meta | — | Bringt Einstiegsdokumente und Statusaussage auf den belegbaren Stand. | Nach #340 umsetzen. |
| #342 | docs(marketing): Landing-Page, Pitch und One-Pager auf den Verwaltungston umstellen | Meta | — | Überträgt die Ausrichtung nach außen, parallel zu den Einstiegsdokumenten. | Parallel zu #341 umsetzen. |
| #343 | docs: Backlog gegen die neue Produktausrichtung sichten | Meta | — | Das vorliegende Dokument ist sein Ergebnis. | Nach Annahme dieses Dokuments abschließen. |
| #344 | Epic: Konzepte und Abstraktionen gegen die neue Produktausrichtung prüfen | Meta | — | Bündelt die Entscheidungsvorlagen zu allem, was durch die Ausrichtung fraglich wird. | Weiterführen; die Empfehlungen unten weisen zusätzliche Issues hierhin zu. |
| #348 | Vektorspeicher-Austauschbarkeit: brauchen wir sie noch? | Meta | — | Prüft ein Versprechen, das dem festgelegten Stack und dem Betrieb im Behördenrechenzentrum widerspricht. | Weiterführen; #77 hängt an der Entscheidung. |
| #349 | Verhältnis von Plugin-Architektur und MCP klären | Meta | — | Klärt zwei überlappende Wege zur Anbindung von Fremdsystemen. | Weiterführen; sechs offene Issues hängen an der Entscheidung. |
| #350 | Cloud-Deployment und Managed Service gegen das Souveränitätsversprechen prüfen | Meta | — | Prüft Betriebsmodelle, die das Kernversprechen der Datenhoheit berühren. | Weiterführen. |
| #351 | Umfang der Storage-Backend-Abstraktion festlegen | Meta | — | Grenzt eine Abstraktion ein, die den Betrieb ohne Netz stützt, aber Pflege kostet. | Weiterführen. |
| #352 | Zielbild der Chat-Kanäle festlegen | Meta | — | Entscheidet, welche Kanäle im Verwaltungskontext tragen und welche nur zugesagt sind. | Weiterführen; Voraussetzung für die Lücke „Anbindung an einen Team-Chat". |
| #353 | Standardposition der Modellanbieter auf lokal-first umstellen | Meta | — | Bringt die Voreinstellung in Einklang mit dem Vorrang eigener Modelle. | Weiterführen; #252 und #256 arbeiten in dieselbe Richtung. |
| #354 | Zitierzwang in der bestehenden Query-Pipeline bewerten | Meta | — | Bewertet die schärfste Ausprägung der Belegbarkeit gegen den heutigen Code. | Vorziehen — die Umsetzung ist Phase-1-pflichtig und hat heute kein Issue. |
| #355 | Umfang des revisionssicheren Audit-Loggings schneiden | Meta | — | Schneidet die größte Substanzlücke gegenüber Phase 1. | Vorziehen; hängt mit #239 und #143 zusammen. |
| #356 | Organisationsgrenze über die Anwendungsschicht hinaus absichern | Meta | — | Fasst die Mandantengrenze als durchgängige Eigenschaft statt als Einzelbefund. | Weiterführen; #271 und #289 sind die konkreten Befunde. |
| #357 | Bürgerassistent und öffentliches Widget als Ausblick festhalten | Meta | — | Hält fest, was Phase 1 nicht verbauen darf, ohne es einzuplanen. | Weiterführen. |

### 1.2 Spaces, Assets und Verteilung

| Nr. | Titel | Bereich | Phase | Begründung | Empfehlung |
|---|---|---|---|---|---|
| #198 | Epic: Space and asset model — replace the workspace model | C | 1–3 | Das Epic baut genau die Objekte, auf denen das Leitprinzip Verteilbarkeit ruht. | Unverändert weiterführen; Titel und Kindtexte sind englisch, siehe nächste Schritte. |
| #203 | Space-asset association as pure curation | C | 1 | Trennt Kuratierung von Rechtevergabe und ist damit Grundlage jeder Verteilung. | Unverändert. |
| #204 | Strict mode for spaces | C, G | 3 | Die einzige technische Zusicherung des Modells, gebraucht für Prüf-, Revisions- und Personalräume. | Unverändert. |
| #205 | Persistent chats inside spaces | C | 3 | Führt Chats als dauerhafte, zurechenbare Objekte mit bewusster Sichtbarkeit ein. | Unverändert; klärt zugleich das Verhältnis zu #54. |
| #206 | Artifacts in spaces with lifecycle and provenance-based release | C | 3 | Ergebnisse aus der Arbeit werden nachvollziehbare Objekte statt flüchtiger Ausgaben. | Unverändert. |
| #207 | Connector sources target exactly one knowledge library | B | 1 | Bindet jede Quelle an genau einen Rechteanker und begrenzt die Reichweite eingespeister Fachdaten. | Unverändert; die dort geforderte Definition der Freigabeobergrenze ist Phase-1-kritisch. |
| #209 | Agent and prompt library assets with the knowledge share chain | D | 2 | Agenten und Prompt-Bibliotheken sind die tragenden verteilbaren Assets. | Unverändert. |
| #210 | Asset parameters: adapt without forking | D | 2 | Verhindert, dass Verteilung in unverbundene Kopien zerfällt. | Unverändert; gemeinsam mit #209 ausliefern. |
| #211 | Asset versioning with immediate propagation and rollback | C | 3 | Versionierbarkeit ist Bestandteil des Versprechens, KI-Können teilbar zu machen. | Unverändert. |
| #212 | Recall by deactivation, with warnings in existing transcripts | C | 3 | Deaktivieren statt Löschen erhält die Nachvollziehbarkeit über Jahre. | Unverändert. |
| #213 | Derivatives with permanent provenance and drift protection | C | 3 | Dauerhafte Herkunft ist die Belegbarkeit auf Ebene der Assets. | Unverändert. |
| #214 | Built-in assets as a distinct origin type | C, D | 2 | Mitgelieferte Verwaltungs-Assets sind der schnellste Weg zum ersten sichtbaren Nutzen. | Unverändert; der dort geforderte Aktualisierungsweg ohne Netz gehört zu Bereich J. |
| #215 | Asset catalog: visibility, listed flag and space directory | C | 3 | Auffindbarkeit ohne Rechtepreisgabe ist die Voraussetzung der organisationsweiten Verteilung. | Unverändert. |
| #216 | Governance controls for co-determination | H, G | 3 | Aufbewahrung, Kontingente und aggregierte Auswertung sind Bedingungen der Mitbestimmungsfähigkeit. | Unverändert; gegen #119 abgrenzen, dort steht ein zweites Kontingent. |
| #238 | Historisierung von Rechten und Gruppenmitgliedschaften | F, G | 1 | Nur historisierte Rechte beantworten die Negativfrage eines Prüfers. | Unverändert; gemeinsam mit den Grants bauen. |
| #239 | Audit-Governance: kein personenbezogener Auswertungspfad | G | 1 | Ohne diese Zusage beginnt in aller Regel kein Rollout mit Personalvertretung. | Unverändert; eng mit #355 abstimmen. |
| #240 | Nachfolge statt Sperre: Assets ausgeschiedener Eigentümer | F, C | 2 | Der Kontenlebenszyklus darf nicht an Eigentümerschaft scheitern. | Unverändert. |
| #241 | Befristung und Rezertifizierung von Einzelgrants | F | 3 | Ohne Verfall verwässert das Rechtemodell innerhalb weniger Jahre. | Unverändert. |
| #242 | Konsistenzprüflauf zwischen Vektorspeicher und Datenbank | A, J | 1 | Ein Prüflauf gegen verwaiste Chunks bleibt sinnvoll, sein Umfang hängt aber an der Entscheidung in #348. | Nach #348 im Umfang bestätigen. |
| #243 | Driftschutz für Abkömmlinge: Fristen und automatische Deaktivierung | C | 3 | Bewusst zurückgestellt, bis belegt ist, dass Abkömmlinge in relevanter Zahl entstehen. | Unverändert zurückgestellt lassen. |

### 1.3 Wissensschicht, Suchqualität und Demo

| Nr. | Titel | Bereich | Phase | Begründung | Empfehlung |
|---|---|---|---|---|---|
| #229 | feat(demo): Korpus als statisches HTTP-Verzeichnis im Compose-Stack | A | 1 | Ein vorführbarer Bestand ohne neuen Ingestion-Code stützt Messbarkeit und Erstkontakt gleichermaßen. | Unverändert. |
| #230 | feat(demo): Demo-Korpus auf die bestehende Instanz ausrollen | A | 1 | Macht die belegte Antwort ohne eigene Installation erlebbar. | Unverändert; die Modellangaben im Text nennen einen Anbieter und sollten beim nächsten Anfassen sachlich formuliert werden. |
| #232 | test(e2e): Indizierung des Demo-Korpus über die Admin-Oberfläche | A, B | 1 | Sichert die einzige Kette, deren Ausfall OPAA unbrauchbar macht. | Unverändert. |
| #233 | test(e2e): Suche im Demo-Korpus mit Quellenangaben | A | 1 | Prüft genau das, was Belegbarkeit im Alltag bedeutet — Antwort mit auffindbarer Quelle. | Unverändert; um eine Zusicherung zum Zitierzwang erweitern, sobald #354 entschieden ist. |
| #235 | feat(demo): Demo-Domänen in getrennte Wissensbibliotheken legen (blockiert) | C | 1 | Führt die rechtebewusste Trennung an einem Bestand vor, den jeder nachvollziehen kann. | Unverändert; der Text ist bereits auf Wissensbibliotheken umgestellt. |
| #304 | eval(golden): category:crosslingual und language:de sind identische Fallmengen | A | 1 | Eine vorgetäuschte Abdeckung untergräbt die Aussagekraft der Regressionsprüfung. | Unverändert. |
| #306 | eval(baseline): Fallzahlbasierte Regressionsprüfung für Paare mit Toleranz < 1/n | A | 1 | Ohne diese Verschärfung meldet die Regressionsprüfung Fehlalarme statt echter Verschlechterungen. | Unverändert. |

### 1.4 Identität, Sicherheit und Betrieb

| Nr. | Titel | Bereich | Phase | Begründung | Empfehlung |
|---|---|---|---|---|---|
| #271 | security(auth): AdminController setzt die Organisationsgrenze nicht durch | F | 1 | Die Mandantengrenze ist in der Rolle mit den weitesten Rechten offen. | Vorziehen; muss vor der zweiten Organisation geschlossen sein. |
| #289 | feat(backend): Organisationsgrenze auf Datenbankebene symmetrisch absichern | F, G | 1 | Macht eine ganze Fehlerklasse strukturell unmöglich statt sie je Service neu abzufangen. | Unverändert; gehört zur Entscheidungsvorlage in #356. |
| #294 | fix(auth): Fehler bei der Anlage des persönlichen Space darf den Login-Request nicht scheitern lassen | F | 1 | Der erste Login ist die empfindlichste Stelle jeder Einführung. | Unverändert. |
| #307 | fix(auth): Gleichzeitige Erstanmeldungen verschiedener Nutzer erschöpfen den Connection-Pool | F, J | 1 | Der Fall ist der Regelfall am ersten Tag eines Rollouts. | Vorziehen; blockiert faktisch jede Einführung mit mehr als einer Handvoll Personen. |
| #308 | test(backend): GroupServiceIntegrationTest auf echtes Liquibase-Schema umstellen | F | 1 | Ein Test gegen ein anderes Schema als die Produktion belegt nichts. | Unverändert. |
| #137 | perf(auth): avoid DB round-trip on every request in UserProvisioningFilter | F | 1 | Eine Datenbankrunde je Anfrage skaliert nicht auf eine ganze Behörde. | Unverändert; gemeinsam mit #307 betrachten. |
| #267 | feat(security): Zielprüfung für URL-Indizierung ergänzen | B, G | 1 | Ein Abruf beliebiger interner Adressen aus dem Serverkontext ist in einem Behördennetz nicht tragbar. | Unverändert. |
| #250 | docs(security): Härtungsanforderungen für erreichbare Compose-Deployments dokumentieren | J, G | 1 | Sichere Voreinstellungen sind ein Produktziel und keine Betriebsempfehlung. | Unverändert; siehe Lücke „geprüfter Auslieferungszustand". |
| #252 | docs: Standardwerte in docs/deployment.md gegen application.yml abgleichen | J, E | 1 | Genau diese Verwechslung hat bereits zu falschen Aussagen in Spezifikation und Epic geführt. | Unverändert; Ergebnis von #353 abwarten und einarbeiten. |
| #256 | test(e2e): Lokale Modellbereitstellung für den E2E-Stack | E, J | 1 | Ein Test, der einen externen Dienst braucht, widerspricht dem Vorrang eigener Modelle. | Unverändert; passt zur Richtung von #353. |
| #257 | docs: Einheitliche Testkonto-Konvention dokumentieren | J | 1 | Uneinheitliche Testkonten sind eine Fehlerquelle bei Einrichtung und Härtung. | Unverändert. |
| #272 | feat(frontend): Space-Sichtbarkeit in der Oberfläche nutzbar machen | C, I | 1 | Solange die Oberfläche fehlt, ist die Sichtbarkeitsachse produktseitig tot. | Unverändert. |
| #193 | fix(frontend): hamburger menu icon invisible in mobile header | K, I | 1 | Ein unsichtbares Bedienelement ist ein Barrierefreiheitsdefekt, kein Schönheitsfehler. | Unverändert; als erster Baustein der BITV-Lücke einordnen. |
| #192 | chore(frontend): drop openapi-typescript peer override once upstream supports TypeScript 6 | J | 1 | Eine stille Ausnahme in der Abhängigkeitsauflösung ist ein Wartungsrisiko. | Unverändert. |
| #68 | Docker Build Skips Tests | J | 1 | Ein Abbild ohne Qualitätsschranke darf in keiner Behörde landen. | Unverändert; Titel und Text auf Deutsch umstellen, wenn es angefasst wird. |
| #78 | Silent Error Fallback for Invalid Document IDs | A | 1 | Ein stiller Fehlschlag verdeckt Datenfehler in der Wissensschicht. | Unverändert. |
| #35 | feat: Erweiterte Job-Status API (Status pro Job, Liste laufender Jobs) | B | 1 | Der Lebenszyklus einer Wissensquelle braucht eine bedienbare Statusansicht. | Unverändert; in die Spezifikation zu Bereich B einbetten. |

---

## 2 — Passt, braucht neue Formulierung

Der Inhalt dieser Issues bleibt richtig. Titel oder Text stammen aber aus dem abgelösten
Workspace-Modell oder aus dem allgemeinen Unternehmenskontext und würden einen Entwickler heute in
die Irre führen.

| Nr. | Titel | Bereich | Phase | Begründung | Was umformuliert werden müsste |
|---|---|---|---|---|---|
| #224 | Epic: Suchqualität messbar machen — Demo-Korpus und Retrieval-Regression | A | 1 | Inhaltlich tragend, aber Phase 3 des Epics spricht von „Trennung in eigene Workspaces" und der Abhängigkeitsgraph nennt die geschlossenen Issues #115 und #117 als Blocker. | „Workspace" durch „Wissensbibliothek" ersetzen, den Blockerpfad auf #207 umstellen, den Korrekturhinweis zur Modellkonfiguration ohne Anbieternamen fassen und den Bezug zum Zitierzwang ergänzen. |
| #234 | feat(eval): Ausweitung des Korpus auf Filme, Reiseziele und Tiere | A | 1 | Die Domänenausweitung bleibt richtig, verweist im Abschnitt „Außerhalb des Umfangs" aber auf „Workspace-Trennung der Domänen". | Verweis auf die Bibliothekstrennung in #235 umformulieren. |
| #119 | feat(upload): upload quotas and duplicate detection | H, B | 2 | Kontingente und Dublettenerkennung bleiben nötig, das Issue verortet beides jedoch im Workspace und in einem geschlossenen Epic. | Auf Wissensbibliothek und Space umstellen, Bezug auf Epic #107 entfernen, gegen das Kontingent in #216 abgrenzen und die Aussage der Dublettenmeldung datensparsam fassen. |
| #123 | feat(query): isolate chat memory per user | C, G | 1 | Die Trennung des Chat-Gedächtnisses je Person ist weiter richtig, das Issue beschreibt sie aber am flüchtigen Zwischenspeicher und im Rahmen eines abgelösten Epics. | Auf das Chat-Objekt aus #205 beziehen oder ausdrücklich als Zwischenlösung bis dahin kennzeichnen; Bezug auf Epic #107 entfernen. |
| #143 | GDPR Compliance: Privacy requirements for production use | G | 1 | Auskunft, Löschung und Portabilität sind Pflichtbestandteil von Bereich G, die Bestandsaufnahme des Issues ist jedoch überholt und der Lösungsweg setzt einen externen Modellanbieter voraus. | Auf Deutsch und auf DSGVO-Vollständigkeit umstellen, Auftragsverarbeitung durch den Vorrang eigener Modelle ersetzen, gegen #239 und #355 abgrenzen und die Tabelle „bereits erfüllt" gegen den heutigen Code neu erheben. |
| #144 | security: restrict workspace member list to ADMIN/OWNER roles | F, C | 1 | Die Preisgabe der vollständigen Mitgliederliste bleibt ein Befund, benennt aber Objekt und Rollen des abgelösten Modells. | Auf Space und die drei Space-Rollen umstellen und gegen die Rechteprüfung aus #202 und #203 abgleichen. |
| #145 | feat: full application internationalization (i18n) support | K, I | 3 | Eine Sprachinfrastruktur wird gebraucht, das Issue macht aber Englisch zum Standard und benennt Workspace-Vorgaben. | Deutsch als Standard- und Ausgangssprache festlegen, Englisch zur Option herabstufen, „My Documents" durch die Benennung des persönlichen Space ersetzen und den Bezug zu Amtssprache und Leichter Sprache herstellen. |

---

## 3 — Ist zu prüfen

Diese Issues widersprechen der neuen Ausrichtung, oder ihre Grundlage ist durch sie fraglich
geworden. Sie gehören in das Prüf-Epic #344, nicht in die Umsetzung.

| Nr. | Titel | Bereich | Phase | Begründung | Empfehlung |
|---|---|---|---|---|---|
| #77 | Vector Store Index Type Hardcoded | A | — | Das Issue begründet sich ausdrücklich mit dem Versprechen austauschbarer Komponenten, das in #348 gerade geprüft wird. | An #348 hängen; bei einer klaren Festlegung auf einen Vektorspeicher bleibt allenfalls die Konfigurierbarkeit des Indextyps als eigener, kleiner Vorgang. |
| #106 | feat: Proof-of-Concept for Plugin Architecture (Connectors) | B, D | — | Das Epic setzt einen Marktplatz für Fremd-Plugins voraus, dessen Verhältnis zu MCP in #349 offen ist. | An #349 hängen und erst nach der Entscheidung neu schneiden. |
| #126 | feat: Define ConnectorPlugin interface contract | B | — | Der Schnittstellenvertrag ist nur sinnvoll, wenn der Plugin-Weg bestätigt wird. | An #349 hängen. |
| #127 | feat: Implement WebAssembly plugin runtime (Variant C PoC) | B | — | Eine isolierte Laufzeitumgebung könnte auch der Agenten-Sandbox dienen — welcher Zweck trägt, entscheidet #349. | An #349 hängen; die mögliche Doppelnutzung für Bereich D dort ausdrücklich mitbewerten. |
| #128 | feat: Implement demo connector plugin (Filesystem source) | B | — | Ein Demo-Plugin belegt nur den Weg, über den noch nicht entschieden ist. | An #349 hängen. |
| #129 | feat: Spring Boot dynamic plugin loading infrastructure | B, G | — | Das Nachladen von Code zur Laufzeit ist in einer Behördenumgebung eigenständig begründungsbedürftig. | An #349 hängen; die Frage der Signatur und Herkunftsprüfung dort ergänzen. |
| #130 | docs: Plugin architecture evaluation and production decision proposal | B | — | Die Bewertungskriterien stammen aus einem allgemeinen Unternehmenskontext und kennen MCP nicht. | An #349 hängen und dort aufgehen lassen. |
| #54 | feat: Erweitertes Chat-Memory mit Persistenz und Session-Verwaltung | C | — | Persistente Chats werden in #205 als Objekt im Space mit Entwurfs- und Ablagezustand neu gebaut; dieses Issue beschreibt dieselbe Fähigkeit ohne Rechte- und Nachweisbezug. | Als Duplikat zu #205 zur Schließung vorschlagen; Entscheidung des Maintainers. |
| #60 | Security & Code Review Findings (20 Issues) | G | — | Die Sammelliste stammt aus Februar 2026, verweist auf Dateien und Zustände vor der Authentifizierung und ist in weiten Teilen erledigt, ohne dass es dem Issue anzusehen wäre. | Neues Sub-Issue in #344 vorschlagen: „Sicherheitsbefunde aus 02/2026 gegen den heutigen Stand abgleichen" — je Punkt belegen, was gilt, den Rest zur Schließung vorlegen. |
| #63 | No Authentication/Authorization Implementation | F, G | — | Die Anmeldung existiert inzwischen, und die geforderte „Workspace isolation at data layer" ist durch das Bibliotheks- und Rechtemodell abgelöst. | Demselben neuen Sub-Issue zuordnen; die verbliebene offene Zusage — Protokollierung mit Nutzerbezug — gehört zu #355. |

---

## 4 — Unklar

| Nr. | Titel | Bereich | Phase | Offene Frage |
|---|---|---|---|---|
| #76 | SQL Injection Risk in Future Migrations | G | — | Das Issue benennt keinen Defekt, sondern ein künftiges Risikomuster, und seine Ergebnisse reichen von einer Richtlinie über eine Prüfliste bis zu Schulungsmaterial — unklar bleibt, welches prüfbare Ergebnis entstehen soll und wer es abnimmt. Vorschlag zur Klärung: entweder auf einen Satz in der Beitragsrichtlinie zu Migrationen eindampfen oder mit Begründung schließen. |

---

## Lücken

Systematisch gegen alle elf Themenbereiche geprüft. Aufgeführt ist, wofür es heute **kein einziges**
offenes Issue gibt.

### A — Wissensschicht & Retrieval

- **Zitierzwang als Umsetzung.** #354 bewertet ihn nur; die schärfste Ausprägung der Belegbarkeit hat
  keinen Umsetzungsvorgang, obwohl sie zu Phase 1 gehört.
- **Hybride Suche mit Reranking.** Reine Vektorsuche versagt bei attributreichen, prosaarmen
  Beständen — genau dem Normalfall in Fachdaten.
- **Konfidenz an der Antwort.** Ohne eine ausgewiesene Sicherheit kann niemand entscheiden, wann er
  eine Auskunft weiterverwendet.
- **Erklärbares Chunking und Sprung zur Textstelle.** Eine Fundstelle, die nicht bis in den
  Ausgangstext führt, trägt vor einem Prüfer nicht.
- **Deep Research über mehrere Quellen.** Mehrstufige Recherchefragen sind der Alltag in Referaten
  und heute nicht abgebildet.
- **Wissensgraph als Ergänzung des Vektor-Retrievals.** In `docs/GraphRAG.md` vorgedacht, in Phase 3
  benannt, ohne Vorgang.
- **Bewertung der Antwortqualität, nicht nur des Rankings.** Die Regressionsprüfung misst Retrieval;
  ob die Antwort durch ihre Quellen gedeckt ist, misst nichts.

### B — Wissensquellen & Konnektoren

- **Konkrete lesende Konnektoren.** Netzlaufwerk, Wiki und Postfach sind der eigentliche Wissensort
  einer Behörde; offen ist nur der Weg dorthin (#349), nicht die Quellen selbst.
- **Selbst aktualisierende Bestände.** Ohne inkrementellen Abgleich einschließlich Löschungen
  antwortet OPAA aus einem Bestand, den es im Quellsystem nicht mehr gibt.
- **Spiegelung der Rechte aus dem Quellsystem.** Wird eine Ablage mit eigenen Rechten eingelesen,
  entsteht ohne Übernahme dieser Rechte eine Umgehung.
- **Texterkennung für Scans und Handschrift.** Ein erheblicher Teil des Aktenbestands liegt als Bild
  vor und ist ohne sie unauffindbar.
- **Lebenszyklus einer Quelle.** Fehlerbehandlung, Wiederaufnahme und gezielte Neuindizierung
  bestimmen, ob der Betrieb beherrschbar bleibt.

### C — Spaces, Assets & Verteilung

- **Freigabe- und Prüfworkflow bis zum organisationsweiten Katalog.** In #198 ausdrücklich außerhalb
  des Umfangs, in der Ausrichtung aber der Kern der Verteilbarkeit.
- **Export und Import von Assets.** Ohne Portabilität gibt es weder eine Sicherung eines Assets noch
  den behördenübergreifenden Austausch der Phase 4.
- **Vorlagenkatalog nach Fachbereich.** Der schnellste Weg von der Installation zum ersten Nutzen ist
  ein fertiger Satz erprobter Vorlagen.

### D — Agenten, Prompts & Werkzeuge

Der am schwächsten belegte Bereich: Von den sechs tragenden Fähigkeiten hat keine einen Vorgang.

- **Geführtes Agenten-Onboarding.** Eine Aufgabenbeschreibung statt eines Freitext-Prompts ist der
  Unterschied zwischen einer Fachkraft und einem Prompt-Bastler.
- **Agenten-Prüfstand vor der Freigabe.** Ohne ihn wird ein Agent an die halbe Organisation verteilt,
  ohne dass jemand belegen kann, dass er tut, was er soll.
- **Prüfagenten als maschinelles Vier-Augen-Prinzip.** Für kritische Vorgänge ist eine zweite,
  unabhängige Prüfung die Bedingung, unter der eine Referatsleitung ein Ergebnis abzeichnet.
- **Isolierte Ausführungsumgebung.** Dateiverarbeitung, Auswertungen und Transkription brauchen eine
  Sandbox, sonst führt jede Erweiterung Code im Anwendungskontext aus.
- **Werkzeugaufrufe und MCP-Anbindung.** #349 klärt nur das Verhältnis zur Plugin-Architektur, nicht
  die Umsetzung.
- **Schreibende Aktionen mit menschlicher Freigabe.** Der Übergang von „fragen" zu „erledigen" steht
  und fällt mit einem belastbaren Freigabeschritt.

### E — Modelle & zentrale Steuerung

- **Modellverwaltung.** Mehrere Modelle je Aufgabe, gepflegt an einer Stelle statt über
  Umgebungsvariablen.
- **Zentrale Modellvorgaben als Obergrenze.** In #198 ausdrücklich außerhalb des Umfangs, in der
  Ausrichtung Teil des Leitprinzips Verteilbarkeit.
- **Beschränkung, die an den Daten hängt.** Eine Wissensbibliothek muss ihre Vorgabe „nur lokale
  Modelle" selbst mitführen, unabhängig davon, wer wo fragt.
- **Schutz vor Weitergabe personenbezogener Daten** an ein freigegebenes externes Modell.

### F — Identität, Rechte & Mandanten

- **Kontenlebenszyklus über SCIM.** Anlage, Änderung und Deaktivierung von Konten aus dem
  Verzeichnisdienst; in #198 ausdrücklich außerhalb des Umfangs.
- **Zugriffsbedingungen wie IP-Beschränkungen.** In vielen Häusern eine Voraussetzung der Freigabe.
- **Geschärftes Rollenmodell.** Die Trennung von Systemverwaltung, Fachadministration und
  Kuratorenrollen ist bisher nur in Teilen beschrieben.

### G — Sicherheit, Nachweis & Prüfbarkeit

- **Revisionssicheres Protokoll als Umsetzung.** #355 schneidet nur den Umfang; die Fähigkeit selbst
  gehört zu Phase 1 und hat keinen Vorgang.
- **Export in ein Sicherheitsmonitoring.** Behörden werten zentral aus; ohne Ausleitung bleibt OPAA
  ein blinder Fleck.
- **Stückliste der Bestandteile und Lieferkettennachweis.** Ohne sie kann ein Betreiber bei einer
  Schwachstellenmeldung nicht antworten.
- **Nachweispaket für die C5-Fähigkeit.** Das Produktziel ist, dass ein Betreiber die Prüfung mit
  OPAA im Prüfumfang besteht — dafür braucht er Unterlagen, nicht nur Eigenschaften.
- **Geprüfter sicherer Auslieferungszustand.** #250 dokumentiert die Härtung; ein abgesichertes
  Betriebsprofil und dessen automatische Prüfung fehlen.

### H — Monitoring, Kosten & Governance

- **Kontingente je Nutzer.** Heute gibt es nur eine Ratenbegrenzung, keine Obergrenze über den Monat.
- **Kostentransparenz.** Ohne Aufschlüsselung nach Modell und Organisationseinheit ist der Betrieb
  nicht planbar; die Aufschlüsselung muss aggregiert und ohne Personenbezug erfolgen.
- **Auswertung des KI-Rollouts.** Wie weit sich geprüfte Assets verbreiten, ist die einzige Größe, an
  der sich Verteilbarkeit überhaupt messen lässt.
- **Betriebsbeobachtbarkeit.** Metriken, Ablaufverfolgung und aussagekräftige Zustandsprüfungen
  fehlen; der einzige Hinweis darauf steckt in der überholten Sammelliste #60.

### I — Kanäle & Oberflächen

- **REST-API als zugesagte Außenschnittstelle.** Sie ist in Phase 1 versprochen, hat aber weder eine
  Zugangsart für Fremdsysteme noch eine Aussage zur Stabilität.
- **Anbindung an einen self-hosted Team-Chat.** Erst nach der Entscheidung in #352 zu schneiden, aber
  danach unmittelbar nötig.
- **Oberfläche für Wissensbibliotheken und Assets.** Zum Space- und Asset-Modell existiert
  frontendseitig nur #272; ohne Bedienoberfläche bleibt das Modell für Nutzende unsichtbar.

### J — Betrieb & Deployment

- **Betrieb auf Kubernetes mit Hochverfügbarkeit.** In Phase 1 zugesagt, ohne Vorgang.
- **Installation ohne Netzanbindung.** Bereitstellung von Abbildern, Modellen und Aktualisierungen im
  air-gapped Betrieb; #214 stößt an dieselbe offene Frage.
- **Sicherung und Wiederherstellung einschließlich Vektorindex.** Für einen Betriebsverantwortlichen
  die erste Frage, und heute nirgends beantwortet.
- **Aktualisierungs- und Migrationspfad zwischen Versionen.** Bisher gilt „harter Schnitt vor 1.0";
  danach braucht es einen belegten Weg.
- **Mandantenfähiger Betrieb durch ein Rechenzentrum.** Die Organisationsgrenze wird abgesichert,
  aber das Anlegen und Verwalten mehrerer Organisationen hat keinen Vorgang.

### K — Verwaltungs-Spezifika

Nach D der schwächste Bereich, obwohl er den Unterschied der Ausrichtung ausmacht.

- **Leichte Sprache und Amtssprache als Textwerkzeug.** Ausdrücklich Phase 1, ohne jeden Vorgang.
- **Barrierefreiheit nach BITV.** Für eine Behörde eine rechtliche Anforderung, nicht ein Merkmal;
  #193 ist ein Einzelbefund, keine Prüfung.
- **Deutsch als Standardsprache der Oberfläche.** Heute sind Texte gemischt hart verdrahtet; #145
  würde in seiner jetzigen Fassung Englisch zum Standard machen.
- **Revisionssichere Aufbewahrung von Vorgängen.** Über das Protokoll hinaus geht es um die Inhalte
  selbst und ihre Fristen.
- **Anbindung an elektronische Akte und Dokumentenmanagement.** Als Option der Phase 4 benannt, ohne
  Vorgang — hier genügt vorerst eine Notiz zu dem, was Phase 1 nicht verbauen darf.

---

## Empfohlene nächste Schritte

Priorisiert. **[Maintainer]** kennzeichnet einen Punkt, der eine Entscheidung braucht, bevor
Folgearbeit sinnvoll ist.

1. **Dieses Dokument abnehmen oder korrigieren.** **[Maintainer]** Alle folgenden Punkte setzen die
   Einordnung voraus.
2. **Die drei Phase-1-Lücken mit dem größten Gewicht als Issues schneiden lassen: Zitierzwang,
   hybride Suche mit Reranking, revisionssicheres Protokoll.** **[Maintainer]** Für Zitierzwang und
   Protokoll sind #354 und #355 die Vorstufe — sie sollten vorgezogen werden, damit die Umsetzung
   einen entschiedenen Schnitt vorfindet.
3. **Zwei Sicherheitsbefunde vorziehen: #271 und #307.** Der eine muss vor der zweiten Organisation
   geschlossen sein, der andere trifft jeden Rollout am ersten Tag.
4. **Über die zehn Issues der Kategorie 3 entscheiden.** **[Maintainer]** Konkret: #77 an #348
   hängen, #106 mit #126 bis #130 an #349 hängen, #54 als Duplikat zu #205 schließen und ein neues
   Sub-Issue in #344 für den Abgleich der Sicherheitsbefunde #60 und #63 anlegen.
5. **Die sieben Issues der Kategorie 2 umformulieren lassen.** Das ist reine Textarbeit ohne
   Codewirkung und beseitigt die letzten Vorkommen des Workspace-Modells im Backlog.
6. **Bereich K mit Issues unterlegen.** Leichte Sprache und Amtssprache gehören in Phase 1; ohne sie
   fehlt der Ausrichtung ihr sichtbarster Verwaltungsbezug.
7. **Bereich D nach der Entscheidung in #349 schneiden.** Agenten-Onboarding, Prüfstand und
   Prüfagenten sind die Substanz der Phase 2 und haben heute keinen einzigen Vorgang.
8. **#76 klären oder schließen.** **[Maintainer]** Der einzige Vorgang, dessen Ziel sich nicht aus
   dem Text erschließt.
9. **Über die Sprache der Issues aus #198 entscheiden.** **[Maintainer]** #198 und seine Kindvorgänge
   #203 bis #216 sind auf Englisch verfasst und widersprechen damit der Projektsprache; die
   Übersetzung ist Aufwand ohne fachlichen Gewinn, das Belassen ein dauerhafter Bruch der Konvention.
   Empfehlung: beim nächsten inhaltlichen Anfassen jeweils mit übersetzen, keine gesonderte Aktion.

### Was diese Sichtung ausdrücklich nicht enthält

Keine Aufwands- oder Kostenaussagen, keine Anbieternamen und keine Aussage darüber, welche Lücke
zuerst gebaut wird — die Reihenfolge oben ist ein Vorschlag, keine Planung.
