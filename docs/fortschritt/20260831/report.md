# Meilenstein 1 — Zeitraumsbericht (ENTWURF)

**Zeitraum: Projektstart (Februar 2026) bis 31.08.2026.** Dieser Bericht belegt, was in diesem
Zeitraum getan wurde; künftige Berichte enthalten nur noch das Delta zum vorigen Stichtag
(siehe [../README.md](../README.md)).

> **Entwurfs-Baseline, bis zur Abnahme nicht veröffentlicht.** Stichtag des Meilensteins ist
> der 31.08.2026; dieser Bericht beruht auf der Leistungsinventur mit Datenstand 30.08.2026
> (`main@a51e6b8c`, 504 geschlossene Issues, 496 gemergte PRs — siehe [anker.md](./anker.md)).
> Der Commit, der diesen Berichtsstand einführt, trägt das Git-Tag
> `fortschritt-20260831-entwurf`; der getaggte Stand umfasst damit Datenstand und Bericht
> gemeinsam. Jede Aussage ist über die [Bausteine](./bausteine/) auf Issue, PR und Code
> rückführbar.

## Management Summary

OPAA ist in sechs Monaten von einer leeren Projektidee zu einem lauffähigen, öffentlich
demonstrierbaren Wissensassistenten für die öffentliche Verwaltung gewachsen — mit belegten
Antworten, echtem Rechtemodell, eigener Demo-Instanz und einer Arbeitsweise, in der Menschen
und KI-Agenten gemeinsam knapp 500 Pull Requests geliefert haben.

**Phase 1 der Produktvision („Souveräner Wissensassistent") ist zu geschätzt rund 80 %
umgesetzt.** Das Fundament — Wissensquellen, Bibliotheken, Rechtemodell, belegte Antworten mit
mehrstufig angehobener Retrieval-Qualität, Modellverwaltung, Audit-Grundstufe, Oberfläche
samt Ordnerstrukturen, Deployment — steht vollständig und trägt das Hauptgewicht der
Phase. Offen sind vor allem hybride Suche mit Reranking und
Konfidenz, Streaming-Antworten, der echte Verzeichnisanschluss, die bewusst zurückgestellte
DSGVO-Vollständigkeit und die Betriebsreife jenseits von Docker Compose (Details in Abschnitt 6).

**Was heute konkret möglich ist:**

- **Installieren und betreiben:** kompletter Stack per Docker Compose mit Schnellstart-Konfiguration,
  Betriebsmetriken und Gesundheitsendpunkt; eine öffentliche Testinstanz läuft.
- **Anmelden:** über den Verzeichnisdienst des Hauses (OIDC/Keycloak), Konten entstehen
  automatisch bei der Erstanmeldung.
- **Wissen anbinden:** Wissensbibliotheken per Vorlage anlegen — aus Dateiverzeichnissen,
  Webverzeichnissen und RSS-Feeds (inkl. Behörden-Websites auf GSB-Basis) oder per Upload
  mit Drag-and-drop, auch ganzer Ordnerstrukturen; mit Verbindungstest, Zeitplan,
  Ordner-Navigation und Dokumentenverwaltung in der Oberfläche.
- **Modelle verwalten:** Chat-Modelle über die Administrationsoberfläche anlegen, aktivieren
  und per Verbindungstest prüfen — Zugangsdaten verschlüsselt abgelegt, lokal betriebene
  Modelle als Voreinstellung.
- **Rechtekonform suchen und fragen:** Chat mit Gesprächsverlauf in Spaces; jede Antwort mit
  geprüften Belegen, Sprung ins Originaldokument und einstellbarem Suchbereich — wer etwas
  nicht lesen darf, bekommt es auch über die Suche nicht zu sehen.
- **Wissen teilen und steuern:** Spaces, Bibliotheks-Freigaben an Personen und Gruppen,
  vollständig über die Oberfläche verwaltbar; jede Rechteänderung historisiert und im
  Audit-Protokoll nachweisbar.
- **Moderne, barrierefreie Oberfläche:** eigenes Designsystem mit konfigurierbarem Branding,
  Dunkelmodus und Barrierefreiheit (BITV/WCAG), automatisiert geprüft und per manuellem
  Abschluss-Audit abgenommen — durchgängig deutsch.
- **Vorführen:** Demo-Installation „Stadt Rheinfurt" mit umfangreichem fiktivem
  Verwaltungskorpus, mehreren Nutzerkonten und Drehbuch für verschiedene Szenarien.

Dieser Bericht stellt die erbrachten Leistungen im Einzelnen zusammen und benennt die Lücken
ebenso deutlich wie das Erreichte.

---

## 1 · Projektsetup und Arbeitsweise

Das Projekt hat nicht nur Software geliefert, sondern eine funktionierende Organisationsform:

- **Mensch-KI-Kollaborationsmodell** von Anfang an (PR#1): dokumentierte Agenten-Rollen —
  Product Manager, Developer, Code Reviewer, QA-Engineer, Marketing, UX sowie sechs
  Stakeholder-Perspektiven der Verwaltung für Konzept-Reviews (#172–#184, #218, #459).
- **Verbindliche Arbeitsregeln** (AGENTS.md): Branch-/Issue-Disziplin, Conventional Commits,
  Reproduktionsnachweis bei Bugfixes, Epic-Führung über native Sub-Issues (#295, #335, #346, #566).
- **Projektsprache Deutsch** für Issues, PRs, Doku und alle nutzerseitigen Texte (#186, #219, #221).
- **Rechtlicher Rahmen:** AGPL-3.0 mit CLA-Prozess samt automatisierter Signaturprüfung (PR#104, PR#105).
- **Täglicher Projektreport** als generierte GitHub-Pages-Seite mit Management Summary und
  Epic-Zuordnung (#248, #285, #321, #373) — Projektsteuerung als Nebenprodukt der CI.
- **CI/CD:** GitHub-Actions-Pipeline mit Backend-/Frontend-Builds, Lint, Tests, E2E, Docker-Images
  nach GHCR, Branch-Schutz, Auto-Merge und Build-Cache-Optimierung (#23, #102, #196, #625, #644,
  #832); selbst betriebene Abhängigkeits-Updates über Renovate mit Auto-Merge und im Betrieb
  gehärteten Schutzregeln — gruppierte Lockfile-Updates, Majors nur mit Freigabe,
  Mindestalter für Releases (#751, #951, #1000, #1002).

## 2 · Technische Grundlagen

- **Stack steht und ist aktuell:** Java 21 / Spring Boot 4.1 / Spring AI 2.0, React 19 /
  TypeScript 6 / MUI 9 / Vite 8, PostgreSQL 18 mit pgvector, Liquibase (#6, #7, #9, #188, #189).
- **API-First:** Alle DTOs werden aus der OpenAPI-Spezifikation generiert — Backend und Frontend
  aus derselben Quelle, seit #896 aus dem eigenen Gradle-Modul `opaa-api` (#8, #133, #152, ADR-0006).
- **Entscheidungskultur:** 19 gepflegte ADRs; überholte werden entfernt oder aktualisiert statt
  stehen gelassen (#326, #324, #845).
- **Architektur aktiv gepflegt:** ein systematisches Backend-Architekturreview (#826) hat u. a.
  die DTO-Grenze zwischen Domain-Services und API-Schicht durchgesetzt (#860), Domain-Exceptions
  eingeführt (#875) und die Aufrufer-Identität zentralisiert — inklusive eines dabei gefundenen
  und behobenen Sicherheitsbefunds (#884).
- **Testfundament:** Unit-, Integrations- und Migrationstests, E2E-Suite mit Playwright gegen den
  echten Compose-Stack (#231–#233), Testcontainer-Infrastruktur mit Template-DB und
  Kontext-Konsolidierung — die Backend-Suite läuft in 3:13 statt 9:53 Minuten
  (#497, #903, PR#499, PR#648, PR#698) und ist plattformübergreifend lauffähig,
  einschließlich macOS-Arbeitsplätzen (#611, #966).
- **Werkzeugkette modernisiert:** Frontend auf pnpm umgestellt (#653), Liquibase-Historie vor
  Produktionsbetrieb zu einer Baseline konsolidiert (#904).
- **Suchqualität ist messbar:** eigene Eval-Korpora (Comichelden, 200 europäische Großstädte),
  Golden Datasets, Metrik-Harness (Hit Rate, MRR, nDCG, Recall) und ein CI-Regressionsjob mit
  Baseline, der Verschlechterungen automatisch als Issue meldet (#224–#228, #234, #306, #721).

## 3 · Produktvision und Konzeption

- **Klare Positionierung:** souveräne, quelloffene KI-Plattform für die öffentliche Verwaltung,
  mit den Leitprinzipien Belegbarkeit und Verteilbarkeit (ADR-0014, Epic #338 mit #339–#343).
- **Elf Themenbereiche als Spezifikationsgerüst** (A–K), je mit eigener Feature-Spezifikation
  (#340, #360–#363); Use Cases, Konzeptglossar und bewusste Abgrenzungen („Bewusst nicht").
- **Konzeptionelle Grundsatzentscheidungen** wurden aktiv getroffen statt vertagt: Zielbild der
  Chat-Kanäle (#352), lokal-first als Modell-Voreinstellung (#353), Zuschnitt des Zitierzwangs
  (#354/#386), Umfang des Audit-Protokolls (#355), Storage-Zielbild (#351), Mandantengrenze (#356);
  dazu eine dokumentierte GraphRAG-Recherche als Entscheidungsgrundlage für den Wissensgraphen (#317).
- **Roadmap mit datiertem Meilenstein** und Arbeitsteilung (#461).
- **Entscheidungsgrundlagen für die nächsten Ausbaustufen** liegen als dokumentierte
  Recherchen vor: Retrieval-Strategien mit Roadmap und Dateityp-/Metadaten-Konzept (#1023)
  sowie Agent-Loop, Frameworks und Laufzeitumgebung für die Phase-2-Agenten (#1022).

## 4 · Implementierte Features (gegen Phase 1 der Vision)

### Wissensschicht & Retrieval (A)
Vektorsuche mit pgvector, Antwortgenerierung mit Quellenangaben, überlappendes Chunking (#374),
deterministische **Belegvalidierung**: jeder Beleg wird gegen die tatsächlich abgerufenen
Fundstellen geprüft und ungültige Belege sichtbar gekennzeichnet (#386). Belege führen bis zum
Original: Download-Endpunkt und Deeplink aus dem Beleg in die Wissensbibliothek (#736, #738),
bei Feed-Anlagen samt Herkunfts-Link auf den Feed-Eintrag (#493); jede Zitatstelle nennt ihren
Fundort und die durchsuchten Bestände (#667). Suchbereichssteuerung per @-Bibliotheksreferenzen
im Chat (#526, #560).

**Mehrstufig angehobene Retrieval-Qualität** (Epic #912): Teilfragen-Zerlegung und
kontextbewusste Reformulierung vor dem Retrieval (Multi-Query-RAG, #923), MMR-Diversität mit
angehobenem topK (#914), Dokument-Vervollständigung nach der Fusion (#932), Contextual Chunking —
Dokumentkontext im Chunk-Embedding (#933) — und eine deterministische **Faktenprüfung der
Zitate** über die Belegvalidierung hinaus (#937). Der Ist-Stand des Retrieval-Algorithmus ist
als Spezifikation dokumentiert (PR#936), ebenso die akzeptierte Grenze der reinen Vektorsuche
(#938, PR#943). Offen für Phase 1: hybride Suche und Reranking.

### Wissensquellen & Indizierung (B)
Drei Aufnahmewege — Verzeichnis, Webverzeichnis/URL-Crawling, Upload — plus **RSS-Feeds als
erster Konnektor-Quellentyp** samt Anlagenübernahme und Government-Site-Builder-Profil
(#463–#468). **Bibliothekstypen mit gespeicherter Quellkonfiguration** (ADR-0018, Epic #486):
Anlage per Template, Verbindungstest, Zeitplan je Bibliothek, sichere Zugangsdatenverwahrung,
Pfad-Allowlist. Formaterkennung anhand des Inhalts statt der Endung (#404), Prüfsummen-Skip,
asynchrone Verarbeitung, **Speicherkontingent je Bibliothek** (#119). **Dokumentenverwaltung in
der Oberfläche**: Dokumentliste mit Paging und Stichwortsuche, Upload per Drag-and-drop, Löschen,
Statusanzeige, übersprungene Dokumente mit Grund, letzter Indexstand je Bibliothek in der
Übersicht (#422, #513, #517, #684). **Ordner in Bibliotheken**
(Epic #520): Ordnerstruktur mit Navigation, ordner-bewusster Upload samt Drag-and-drop ganzer
Verzeichnisse, FILESYSTEM-Quellen als read-only-Ordnerbaum (#819–#824). Dokumente verschwundener
Quellen werden aufgeräumt (#886), die Dokumentidentität ist je (Bibliothek, Quelle) gescoped
(#877), der Crawler gegen Endlosrekursion begrenzt (#836). Die Quellenzugriffe sind
gegen SSRF, Redirect-Tricks und Zugangsdaten-Exfiltration gehärtet (#267, #538, #617, #651, #693).

### Spaces, Bibliotheken & Rechte (C/F)
Spaces mit Mitgliedschaften und Rollen, persönlicher Space je Nutzer, und **Wissensbibliotheken
als eigenständige Rechteobjekte** mit Grants an Personen und Gruppen (#199–#202). Die Rechteprüfung
sitzt in der Vektorsuche selbst. **Space↔Wissensbibliothek-Zuordnung als Kuratierung**: Nutzer
assoziieren die Bibliotheken, auf die sie berechtigt sind, mit ihren Spaces — wirksam in API und
Retrieval (#203, #686). Gruppen aus dem Verzeichnisabgleich als Rechtesubjekt (#200, #237),
**lückenlose Historisierung von Rechten und Mitgliedschaften** mit Stichtags-Rekonstruktion
(#238), Organisationsgrenze auf Datenbankebene mit strukturellem Prüflauf (#289, #390, #400, #401).
**Rechteverwaltung vollständig über die Oberfläche**: Freigaben an Personen und Gruppen erteilen,
Rollen ändern und entziehen, dazu Space- und Gruppenverwaltung (#421, #423) — mit
berechtigungsunabhängiger Nutzersuche für die Rechtevergabe (#445). Die Spaces-Übersicht
zeigt Quellen- und Chatzahl je Space (#682).

### Identität & Anmeldung (F)
OIDC/Keycloak-Anbindung mit automatischer Nutzerprovisionierung (#108–#110), daneben ein
abgetrennter dev-Modus für die Entwicklung, per Startguard erzwungen. Robustheit gegen
parallele Erstanmeldungen (#293, #307), Silent-Token-Renew statt Sofort-Logout (#737).

### Chat & Oberfläche (I)
**Persistente Chats in Spaces** mit serverseitigem Verlauf, LLM-generierten Titeln und
Chatliste (Epic #523, #557); umfangreiche Race-Condition-Härtung der Frontend-Stores.
**Komplettes UI-Redesign** auf eigenem Designsystem: Design-Tokens, Theme, konfigurierbares
Branding, App-Shell, Assistenten für Space- und Bibliotheksanlage, Fußnoten-Fundstellen mit
Belegfenster, Dunkelmodus (#580–#597, #654, #658). **Globale Navigationsebene**: immer sichtbare
Leiste (Rail) mit abgesetztem Verwaltungsrahmen und globalen Einstellungen (#786–#789).
**Browservorschau für Originaldokumente** statt stillem Download (#780), durchgängig deutsche
Oberfläche inklusive MUI-Standardtexten (#784). Barrierefreiheit als Richtlinie (BITV/WCAG)
mit automatisierten Prüfungen in Lint und E2E (#584–#586), Kontrast-Korrekturen
(#634, #725, #853) und einem **manuellen Abschluss-Audit** — Tastatur-Durchgänge,
Screenreader-Stichproben, Kontrastprüfung in beiden Farbschemata — mit abgelegtem
Prüfprotokoll und sämtlich behobenen Befunden (#598, #956–#959, #1016). Das Redesign-Epic
ist damit vollständig abgeschlossen (#600).

### Modelle & zentrale Steuerung (E)
Austauschbare, **OpenAI-kompatible Modellanbieter**, für Chat und Einbettung getrennt
konfigurierbar — lokal betriebene Modelle (vLLM, Ollama) sind die Voreinstellung, eine
unkonfigurierte Installation redet nicht nach außen (#47, #353). **Modellverwaltung Stufe 1
ist geliefert** (Epic #755): Chat-Modelle als verwaltbare Objekte mit verschlüsselten
Zugangsdaten (#756), Admin-API mit CRUD, Aktivierung und Verbindungstest (#757),
Laufzeitauflösung des aktiven Modells (#758) und Administrationsseite samt
Einbettungsübersicht (#759) — E2E-abgedeckt (#760). Ollama steht als optionales
Compose-Profil bereit (#720). Zentrale Vorgaben als Obergrenze je Space/Bibliothek
bleiben Phase-1-Arbeit.

### Sicherheit & Nachweis (G)
Ratenbegrenzung, CORS-Härtung, Sicherheits-Header, Härtungsdokumentation (#61, #62, #409, #250).
Die **erste Stufe des revisionssicheren Audit-Trails ist geliefert** (#355, #391–#395):
nur-anfügende Ablage mit entzogenen Änderungsrechten, Erfassung aller Rechte- und
Verwaltungsereignisse, Revisionszugriff ohne personenbezogene Auswertung, Selbstprotokollierung
jedes Protokollzugriffs, Aufbewahrung mit automatischer Löschung, ergänzt um Abfrage-Indizes
und strukturell abgesicherte Doppelbuchführung über Domain-Events (#834, #892). Benannte
Grenzen: keine Prüfsummenverkettung — Manipulation mit direktem Datenbankzugang liegt beim
Betreiber — und die Betriebshärtung (u. a. Nicht-Superuser-Datenbankkonto) ist bewusst
zurückgestellt (Epic #457).

### Betrieb & Demo (J/H)
**Betriebsmetriken und Gesundheitsendpunkt** für die Überwachung (#65).
Docker Compose für den Gesamtstack inkl. Keycloak, .env-Schnellstart (#16, #157, #716),
öffentliche Testinstanz, `deployment.md` als allgemeines Betriebshandbuch (#929), und die
**Demo-Instanz „Stadt Rheinfurt“**: generierter Verwaltungskorpus einer fiktiven Stadt,
Seed-Profile für Demo und E2E — inklusive Space↔Bibliothek-Zuordnungen (#775) —,
Installationsanleitung, Vorführ-Drehbuch und Demo-Video auf der Projektseite
(Epic #708, #709–#713, #807).

## 5 · Ehrlicher Befund: Lücken und bewusste Schnitte

Nichts davon ist verschwiegen — alles ist als Entscheidung oder offenes Issue dokumentiert:

- **Hybride Suche und Reranking fehlen** — reine Vektorsuche ist für attributreiche Fachdaten
  eine bekannte Schwäche (Phase-1-Ziel, offen).
- **Zitierzwang wurde bewusst verkleinert:** statt Verweigerungsmodus und Space-Schalter gibt es
  die deterministische Belegvalidierung — eine dokumentierte Maintainer-Entscheidung, kein
  stilles Scheitern (#354, #387–#389 „not planned").
- **Agenten, Prompts & Werkzeuge (D)** und **Verwaltungs-Spezifika (K, z. B. Leichte Sprache)**
  sind noch nicht begonnen — Phase-2- bzw. spätere Phase-1-Arbeit. Das alte Asset-Verteilungs-Epic
  (#198) wurde mit 15 Kind-Issues als „not planned" bereinigt: Das Verteilungs-Kernversprechen
  ist unerledigt, aber sauber ausgewiesen statt formal abgehakt.
- **DSGVO-Vollständigkeit** (Löschrecht, Selbstauskunft, Export) ist per Maintainer-Entscheidung
  bis vor den Produktivbetrieb zurückgestellt (#143, #798) — ebenso die Audit-Betriebshärtung
  (Epic #457). Schadsoftwareprüfung des Uploads und Streaming-Antworten stehen aus.
- **Mehrsprachigkeit ist entschieden, nicht offen:** Die Anwendung bleibt auf absehbare Zeit
  bewusst deutschsprachig (#145 „not planned").

Der Report nennt durchgängig nur den Endzustand. Zwischenstände, die gebaut und wieder
zurückgebaut wurden, sind keine Leistungsposten; sie bleiben ausschließlich in den
[Bausteinen](./bausteine/) dokumentiert.

## 6 · Offen für Phase 1 — priorisierte Restliste

Was zur Phase-1-Definition der Vision („Souveräner Wissensassistent") noch fehlt, nach Gewicht:

**Kern des Wissensassistenten**
1. **Hybride Suche mit Reranking** — wichtigste Retrieval-Lücke für Fachdaten
2. **Ausgabe im Fluss (Streaming)** — größter Einzelfaktor der gefühlten Antwortzeit
3. **Konfidenz als erklärte Größe** und **erklärbares Chunking**
4. **Textwerkzeuge einschließlich Leichter Sprache** — Bereich K, bisher ohne einen einzigen Vorgang

**Identität & Modelle**
5. **Echter Verzeichnisanschluss** — hinter dem Abgleich steht bislang nur ein No-Op-Client;
   LDAP-/SCIM-Client und Kontenlebenszyklus fehlen
6. **Zentrale Modellvorgaben als Obergrenze** je Space/Bibliothek — die Modellverwaltung
   selbst (Stufe 1) ist geliefert
7. Sitzungsverwaltung mit erzwungener Neuanmeldung, Einschränkung auf Netzbereiche

**Nachweis & Compliance**
8. **DSGVO-Vollständigkeit** — Löschrecht, Selbstauskunft, Datenexport; bewusst zurückgestellt
   bis vor den Produktivbetrieb (#143, #798)
9. **Schadsoftwareprüfung des Uploads** — Voraussetzung für den Produktivbetrieb
10. Software-Stückliste, signierte Builds, Sicherheits-Scans in der CI, unabhängige Prüfung
11. **Audit-Betriebshärtung** (Epic #457: Nicht-Superuser-Datenbankkonto, Flutschutz,
    Partitionshorizont) sowie Fristwarnung (abhängig von #216) und Rechtehistorien-Nacharbeiten
    (#429, #430) — sämtlich bewusst zurückgestellt

**Betrieb & Oberfläche**
12. **Kubernetes mit Hochverfügbarkeit** und die **air-gapped-Lieferung** (Abbilder,
    Modellgewichte, Stückliste als übertragbares Paket)
13. Antwort-Bewertung mit Speicherung, API-Token-Verwaltung

## 7 · Zahlen zum Inventurstand

| | |
|---|---|
| Gemergte Pull Requests | 496 |
| Geschlossene Issues | 504 (davon ~15 % bewusst „not planned") |
| Architecture Decision Records | 19 gepflegt |
| Datenbank-Migrationen | Liquibase mit konsolidierter Baseline (#904) und strukturellem Schema-Prüflauf |
| Backend-Testsuite | 3:13 Minuten (vorher 9:53) nach Testkontext-Konsolidierung (#903) |
| Themenbereiche mit Lieferung | alle außer D (Agenten); K bisher nur Barrierefreiheit |

---

*Erstellt aus der Leistungsinventur (Issues #744/#945, Vorgehen siehe [../README.md](../README.md)).
Datenstand: `main@a51e6b8c`, GitHub-Abfrage vom 30.08.2026; dieser Berichtsstand ist im
Repository als Tag `fortschritt-20260831-entwurf` verankert. Künftige Fortschreibungen erheben
das Delta ab den Marken in [anker.md](./anker.md).*
