# Umsetzungsstand

Was von der [Produktvision](./VISION.md) heute tatsächlich gebaut ist — belegt am Code, nicht am Vorhaben.
Dieses Dokument löst die frühere MVP-Statusübersicht ab, die einen vollständigen MVP meldete und dabei auf
Dateien verwies, die es nicht mehr gibt.

**Stand: August 2026.** Grundlage sind `backend/src/main/java/io/opaa/`, `frontend/src/`,
`backend/src/main/resources/db/changelog/changes/` und die offenen Vorgänge auf GitHub.

Drei Zustände werden unterschieden:

| | Bedeutung |
|---|---|
| **Gebaut** | Läuft, ist getestet und über die Oberfläche oder die API erreichbar |
| **Im Bau** | Spezifiziert und in Arbeit, mit offenen Vorgängen |
| **Geplant** | Spezifiziert, aber noch keine Zeile Code — mit der Phase, in die es gehört |

---

## Kurzübersicht

| Themenbereich | Stand | Nächster Schritt |
|---|---|---|
| **A** Wissensschicht & Retrieval | Grundlage gebaut, Kern der Vision fehlt | Zitierzwang, hybride Suche, Reranking |
| **B** Wissensquellen & Konnektoren | Uploads und URL-Indizierung gebaut, Konnektoren fehlen | erster lesender Konnektor |
| **C** Spaces, Assets & Verteilung | **im Bau** | Epic #198 |
| **D** Agenten, Prompts & Werkzeuge | nichts gebaut, keine Vorgänge | Phase 2 schneiden |
| **E** Modelle & zentrale Steuerung | lokal-first gebaut, zentrale Vorgaben fehlen | Modellverwaltung |
| **F** Identität, Rechte & Mandanten | Anmeldung und Verzeichnisabgleich gebaut | rechtebewusste Suche vollenden |
| **G** Sicherheit, Nachweis & Prüfbarkeit | **größte Lücke** — kein Protokoll | Umfang klären (#355) |
| **H** Monitoring, Kosten & Governance | Betriebsmetriken gebaut, fachliche Auswertung fehlt | Phase 1 abgrenzen |
| **I** Kanäle & Oberflächen | Web-Oberfläche und REST-API gebaut | weitere Kanäle offen (#352) |
| **J** Betrieb & Deployment | Docker Compose gebaut | Kubernetes, Betrieb ohne Netz |
| **K** Verwaltungs-Spezifika | nichts gebaut | Leichte Sprache (Phase 1) |

---

## A · Wissensschicht & Retrieval

**Gebaut**
- Aufnahme, Zerlegung und Einbettung von Dokumenten (`io.opaa.indexing` — `ChunkingService`,
  `DocumentIndexingService`, `FileProcessingService`)
- Abruf über Vektorähnlichkeit und Antwortgenerierung (`io.opaa.query` — `QueryService`,
  `AnswerGenerationService`)
- **Quellenangaben in der Antwort** (`CitationParser`) mit Relevanzwert und Textauszug
- Gesprächsgedächtnis je Sitzung (`CaffeineChatMemoryRepository`)
- Vektorspeicherung in PostgreSQL mit pgvector — der einzige unterstützte Vektorspeicher; ein Wechsel
  ist über die Schnittstelle von Spring AI technisch möglich, wird aber nicht unterstützt, nicht geprüft
  und nicht dokumentiert (#348)

**Im Bau**
- Messbarkeit der Suchqualität — Korpus, Golden Dataset und Regressionsprüfung (Epic #224, Verzeichnis
  `eval/`, ADR-0010 bis ADR-0013). Der Messvertrag steht, die Fallmengen wachsen noch.

**Geplant (Phase 1)**
- **Zitierzwang** — heute gibt es Quellenangaben, aber keine Verweigerung ohne Beleg. Das schärfste
  Leitprinzip der Vision hat noch keine Umsetzung; der Schnitt wird in #354 geklärt.
- **Hybride Suche und Reranking** — es gibt weder Volltextsuche noch einen Reranker im Code. Reine
  Vektorsuche versagt genau bei attributreichen Fachdaten.
- **Erklärbares Chunking** — die Zerlegung ist heute nicht nachvollziehbar dargestellt.
- **Überlappung beim Zerlegen.** `ChunkingService` verwendet den Token-Splitter ohne Überlappung. Ohne
  sie wird eine Definition regelmäßig von ihrer Überschrift getrennt und der Beleg dadurch unbrauchbar —
  das ist keine Feinjustierung, sondern eine Lücke in der Belegbarkeit.
- **Nur fünf Dateiendungen werden verarbeitet** (`.md`, `.txt`, `.pdf`, `.docx`, `.pptx`; im Netzweg
  zusätzlich `.doc`), ausgewählt über eine Endungsliste statt über den erkannten Inhalt. Der eingesetzte
  Extraktor kann mehr. Die beiden Indizierungswege führen dabei **unterschiedliche** Listen.
- Konfidenz als eigenständige, erklärte Größe (heute nur Ähnlichkeitswert)

**Geplant (Phase 2 und später)**
- Deep Research · Bild- und Handschrifterkennung · Wissensgraph

---

## B · Wissensquellen & Konnektoren

**Gebaut**
- Upload über die Weboberfläche und die REST-API; Aufnahme aus einem konfigurierten Verzeichnis
- Indizierung aus dem Netz über URL (`UrlIndexingExecutor`, `UrlFileDownloader`, `AutoindexCrawlerService`)
- Formate: Markdown, Text, PDF, DOCX, XLSX, PPTX
- Wiedererkennung unveränderter Dateien über Prüfsummen (`ChecksumService`)
- Auftragsverwaltung für Indizierungsläufe mit Status (`IndexingJobService`, `/api/v1/indexing`)

**Geplant (Phase 1)**
- **Der erste Konnektor.** Bisher gibt es keinen — weder zu einer Dateiablage noch zu einem Wiki, einem
  Postfach oder einem Vorgangssystem. Was existiert, ist die Aufnahme über Verzeichnis und URL.
- Selbst aktualisierende Wissensblöcke; Zeitpläne und Prioritäten
- Zuordnung einer Konnektorquelle zu genau einer Wissensbibliothek (#207)

**Geplant (Phase 2)**
- Schreibender Zugriff je Integration · Spiegelung der Rechte aus dem Quellsystem

> Die Zielprüfung für URL-Indizierung (private Adressbereiche, Schema) ist offen — #267.

---

## C · Spaces, Assets & Verteilung

**Gebaut**
- Spaces mit Mitgliedschaften und Rollen (`io.opaa.space`, `/api/v1/spaces`)
- Persönlicher Space je Nutzer, automatisch angelegt
- Gruppen mit Mitgliedschaften (`io.opaa.group`, `/api/v1/admin/groups`)
- **Wissensbibliotheken als eigenständige Objekte** mit Rechtevergabe an Nutzer und Gruppen
  (`io.opaa.library` — `KnowledgeLibrary`, `AssetGrant`, `AssetRole`, `LibraryAccessService`)
- Organisation als Mandantenklammer (`io.opaa.organization`)
- Oberfläche für Space- und Gruppenverwaltung (`SpaceManagementPage`, `GroupManagementPage`)
- Migrationen bis `015-replace-space-kind-with-is-default.yaml` — die Space-Arten sind durch Attribute
  ersetzt (#333)

**Im Bau** — Epic #198 mit #203 bis #216
- Assoziation von Assets an Spaces als reine Kuratierung (#203) · strenger Modus (#204) · dauerhafte
  Chats im Space (#205) · Artefakte mit Lebenszyklus (#206) · Agenten- und Prompt-Assets mit
  Freigabekette (#209) · Parameter statt Abspaltung (#210) · Versionierung mit Sofortwirkung (#211) ·
  Rückruf durch Deaktivierung (#212) · Abkömmlinge mit Herkunft (#213) · mitgelieferte Assets (#214) ·
  Katalog mit Auffindbarkeit (#215) · Mitbestimmungs-Steuerung (#216)

**Geplant (Phase 3)**
- Freigabe- und Prüfworkflow über Stufen · organisationsweiter Katalog · Vorlagenkatalog nach
  Fachbereich · gemeinsame Räume für Menschen und KI

> Die Organisationsgrenze ist auf Datenbankebene noch nicht symmetrisch abgesichert (#289), und der
> Verwaltungspfad setzt sie nicht durch (#271). Beides muss zu sein, bevor eine zweite Organisation
> auf derselben Installation läuft.
>
> Beide Lücken sind heute **nicht ausnutzbar**, weil genau eine Organisation existiert — und beide
> werden **gleichzeitig** scharf, sobald eine zweite dazukommt. #289 und #271 sind deshalb als
> Voraussetzung für den mandantenfähigen Betrieb markiert und vorgezogen; hinzu kommt ein
> struktureller Prüflauf gegen das Schema, der verhindert, dass eine künftige Migration die
> Datenbankebene erneut löchrig macht (siehe
> [features/spaces-and-assets.md](./features/spaces-and-assets.md#wie-die-grenze-gehalten-wird)).

---

## D · Agenten, Prompts & Werkzeuge

**Nichts gebaut.** Für keinen der Bausteine dieses Bereichs gibt es heute Code — und, was schwerer wiegt,
auch keinen einzigen offenen Vorgang. Der Bereich trägt die zweite Säule der Vision und ist im Backlog
nicht vertreten.

**Geplant (Phase 2)**
- Agenten als teilbare Pakete · geführtes Agenten-Onboarding · Prüfstand vor der Freigabe · Prüfagenten
  für kritische Vorgänge · isolierte Ausführungsumgebung · schreibende Aktionen mit menschlicher Freigabe ·
  MCP · mitgelieferter Startkatalog

**Geplant (Phase 1)**
- Textwerkzeuge: Zusammenfassung, Übersetzung, Leichte Sprache, Export

---

## E · Modelle & zentrale Steuerung

**Gebaut**
- Austauschbare Anbieter für Chat und Einbettung, getrennt konfigurierbar
- **Lokal betriebene Modelle sind die Voreinstellung**, für Chat und Einbettung. Ein Cloud-Anbieter ist
  konfigurierbar, aber nicht voreingestellt; ohne gesetzten Schlüssel greift ein Platzhalter. Eine
  unkonfigurierte Installation redet nicht nach außen.

**Geplant (Phase 1)**
- **Ausgabe im Fluss.** Die Antwort erscheint heute am Stück. `AnswerGenerationService` ruft das Modell
  blockierend auf, die Schnittstelle kennt keinen Ereignisstrom und die Oberfläche empfängt keinen. Für
  die wahrgenommene Antwortzeit ist das der wichtigste Einzelfaktor.
- **Modellverwaltung** — Modelle sind heute Konfiguration, keine verwaltbaren Objekte
- **Zentrale Vorgaben als Obergrenze** — es gibt keine technische Sperre, die Cloud-Nutzung ausschließt
  oder je Wissensbibliothek beschränkt. Wer das heute will, verlässt sich auf die Konfiguration.
- Beschränkungen, die an den Daten hängen statt am Arbeitsraum
- Voreinstellungen und Parameter je Aufgabe

**Geplant (Phase 2)**
- Schutz vor Weitergabe personenbezogener Daten an Modelle

---

## F · Identität, Rechte & Mandanten

**Gebaut**
- Anmeldung über OIDC; Entwicklungsmodus als getrenntes Profil, durch `AuthProfileGuard` erzwungen
  (ADR-0005)
- Anlage von Nutzern bei der Erstanmeldung (`UserProvisioningFilter`)
- Systemrollen (`SystemRole`), Space-Rollen, Asset-Rollen
- **Abgleich mit dem Verzeichnisdienst** einschließlich Gruppen und Status (`io.opaa.group.sync`,
  `/api/v1/admin/directory-sync`, Migration `011-directory-sync.yaml`) — mit Probelauf vor dem Vollzug
- Rechtefilter über lesbare Wissensbibliotheken (`LibraryAccessService`)

**Geplant (Phase 1)**
- Kontenlebenszyklus über SCIM statt eigenem Abgleich · Einschränkung auf Netzbereiche ·
  Sitzungsverwaltung mit erzwungener Neuanmeldung · Historisierung von Rechten und Mitgliedschaften (#238)

> Offen: gleichzeitige Erstanmeldungen erschöpfen den Verbindungspool (#307) — das ist der Regelfall am
> ersten Rollout-Tag. Ein Fehler bei der Anlage des persönlichen Space lässt die Anmeldung scheitern
> (#294).

---

## G · Sicherheit, Nachweis & Prüfbarkeit

**Die größte Lücke gegenüber Phase 1.**

**Gebaut**
- Sichere Voreinstellungen bei der Anmeldung; Trennung der Auth-Profile (ADR-0005)
- Durchsetzung der Berechtigungen zur Abfragezeit über die Bibliotheksrechte

**Nicht gebaut**
- **Revisionssicheres Protokoll.** Es gibt kein Audit-Paket im Code. Weder Verwaltungsaktionen noch
  Zugriffe auf Protokolldaten werden festgehalten. Ohne das besteht kein Betreiber eine Prüfung mit
  OPAA im Prüfumfang. Umfang und Schnitt werden in #355 geklärt.
- **Vollständigkeit nach DSGVO** — Löschrecht und Datenexport fehlen (#143)
- Software-Stückliste, signierte Builds, automatisierte Sicherheitsprüfung im CI
- Governance der Auswertung: kein personenbezogener Auswertungspfad (#239)

**Geplant (Phase 1)**
- Alle vier Nachweisblöcke · unabhängige Sicherheitsprüfung

---

## H · Monitoring, Kosten & Governance

**Gebaut**
- Betriebsmetriken und Gesundheitsendpunkt (`io.opaa.observability`, `/api/health`)

**Geplant (Phase 1)**
- Grenzen je Nutzer · Transparenz über Token- und Sitzungskosten

**Geplant (Phase 2)**
- Auswertungscockpit · Transparenz über den Fortschritt der KI-Einführung je Organisationseinheit ·
  Export für Berichte — durchgehend aggregiert

---

## I · Kanäle & Oberflächen

**Gebaut**
- Weboberfläche: Chat mit Quellenangaben und Eingrenzung auf Spaces, Space- und Gruppenverwaltung,
  Einstellungen (`frontend/src/pages/`)
- REST-API unter `/api/v1` — Abfrage, Indizierung, Spaces, Bibliotheken samt Rechtevergabe, Gruppen,
  Verzeichnisabgleich, Systemverwaltung
- Ratenbegrenzung je Netzadresse und global je Endpunkt (`io.opaa.api.RateLimitFilter`)
- E2E-Prüfung als Rauchtest (`e2e/`, ADR-0009)

**Nicht gebaut — sieht aber gebaut aus**
- **Die Dokumentenseite ist ein Platzhalter.** `DocumentsPage.tsx` zeigt „Demnächst verfügbar"; der
  Endpunkt `/api/v1/libraries/{id}/documents` existiert, die Oberfläche dazu nicht.
- **Die Bewertung von Antworten ist nur Oberfläche.** `FeedbackButtons.tsx` hält den Zustand lokal, zeigt
  „Feedback folgt in Kürze" und trägt einen entsprechenden Vermerk im Code. Es gibt weder Endpunkt noch
  Speicherung — die Rückkopplungsschleife aus Themenbereich A hat damit keine Datenquelle.
- **Gespräche überleben kein Neuladen.** Der Verlauf liegt nur im Speicher des Browsers; das
  serverseitige Gesprächsgedächtnis aus Themenbereich A ist davon unberührt, aber nicht dasselbe.
- **Es gibt keine Verwaltung von API-Tokens.** Die Einstellungsseite kennt nur das Farbschema.

**Nicht gebaut**
- **Kein einziger Chat-Kanal.** Weder ein self-hosted Team-Chat noch ein Verbraucher-Messenger. Die
  Dokumentation nannte bisher mehrere davon; welche im Zielbild bleiben, wird in #352 geklärt.

**Geplant**
- Dokumentenseite, Bewertung von Antworten mit Speicherung, dauerhafte Gespräche (#205),
  Token-Verwaltung (Phase 1) · Anbindung an self-hosted Team-Chats (Phase 3) · Erweiterungen für Office
  und Browser (Phase 4)

> Offen: unsichtbares Menüsymbol im mobilen Kopfbereich (#193); vollständige Mehrsprachigkeit der
> Anwendung (#145).

---

## J · Betrieb & Deployment

**Gebaut**
- Docker Compose für den gesamten Stapel (`docker-compose.yml`, `keycloak/`)
- PostgreSQL mit pgvector; Schemaverwaltung über Liquibase
- Öffentliche Testinstanz (siehe [deployment.md](./deployment.md))

**Geplant (Phase 1)**
- Kubernetes mit Hochverfügbarkeit · **Betrieb ohne Netzanbindung** — die übertragbare Lieferung aus
  Abbildern, Modellgewichten und Stückliste ist heute nirgends geschnitten
- Umfang der Speicher-Abstraktion (#351)

> Der Docker-Build überspringt die Tests (#68). Härtungsanforderungen für erreichbare
> Compose-Installationen sind nicht dokumentiert (#250).

---

## K · Verwaltungs-Spezifika

**Nichts gebaut.** Der Bereich, der die Ausrichtung von generischem Wissensmanagement unterscheidet, ist
im Code und im Backlog leer.

**Geplant (Phase 1)**
- Leichte Sprache und Amtssprache — in der Vision Phase 1, ohne Vorgang

**Geplant (Phase 3)**
- Barrierefreiheit nach BITV · Feinschliff der Amtssprache

**Geplant (Phase 4)**
- Anbindung an elektronische Akte und Dokumentenmanagement · Assistent für Bürgerinnen und Bürger (#357)

---

## Was dieses Dokument nicht sagt

Es nennt **keine Termine** und keine Aufwände. Die Reihenfolge ergibt sich aus den Phasen in
[VISION.md](./VISION.md) und aus der Priorisierung im Backlog, nicht aus einem Datum.

Es ist außerdem eine Momentaufnahme. Maßgeblich sind die offenen Vorgänge auf GitHub und der
[Tagesreport](./tagesreport.md); dieses Dokument fasst zusammen, was dort verstreut steht.
