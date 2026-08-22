# Meilenstein 1 — Leistungsbericht (ENTWURF)

> **Arbeitsstand, nicht veröffentlicht.** Stichtag des Meilensteins ist der 31.08.2026; dieser
> Entwurf basiert auf dem Inventurstand vom 22.08.2026 (`main@99f61ee1`, 351 geschlossene Issues,
> 324 gemergte PRs — siehe [anker.md](./anker.md)). Vor der Abnahme wird das Delta bis zum
> Stichtag nachgezogen. Jede Aussage ist über die [Bausteine](./bausteine/) auf Issue, PR und
> Code rückführbar.

OPAA ist in sechs Monaten von einer leeren Projektidee zu einem lauffähigen, öffentlich
demonstrierbaren Wissensassistenten für die öffentliche Verwaltung gewachsen — mit belegten
Antworten, echtem Rechtemodell, eigener Demo-Instanz und einer Arbeitsweise, in der Menschen
und KI-Agenten gemeinsam über 320 Pull Requests geliefert haben. Dieser Bericht stellt die
erbrachten Leistungen zusammen und benennt die Lücken ebenso deutlich wie das Erreichte.

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
  nach GHCR, Branch-Schutz, Auto-Merge und Build-Cache-Optimierung (#23, #102, #196, #625, #644).

## 2 · Technische Grundlagen

- **Stack steht und ist aktuell:** Java 21 / Spring Boot 4.1 / Spring AI 2.0, React 19 /
  TypeScript 6 / MUI 9 / Vite 8, PostgreSQL 18 mit pgvector, Liquibase (#6, #7, #9, #188, #189).
- **API-First:** Alle DTOs werden aus der OpenAPI-Spezifikation generiert — Backend und Frontend
  aus derselben Quelle (#8, #133, #152, ADR-0006).
- **Entscheidungskultur:** 18 gepflegte ADRs; überholte werden entfernt oder aktualisiert statt
  stehen gelassen (#326, #324).
- **Testfundament:** Unit-, Integrations- und Migrationstests, E2E-Suite mit Playwright gegen den
  echten Compose-Stack (#231–#233), Testcontainer-Infrastruktur mit Template-DB und
  Kontext-Konsolidierung, die die Suite massiv beschleunigt hat (#497, PR#499, PR#648, PR#698).
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
  (#354/#386), Umfang des Audit-Protokolls (#355), Storage-Zielbild (#351), Mandantengrenze (#356).
- **Roadmap mit datiertem Meilenstein** und Arbeitsteilung (#461).

## 4 · Implementierte Features (gegen Phase 1 der Vision)

### Wissensschicht & Retrieval (A)
Vektorsuche mit pgvector, Antwortgenerierung mit Quellenangaben, überlappendes Chunking (#374),
deterministische **Belegvalidierung**: jeder Beleg wird gegen die tatsächlich abgerufenen
Fundstellen geprüft und ungültige Belege sichtbar gekennzeichnet (#386). Suchbereichssteuerung
per @-Bibliotheksreferenzen im Chat (#526, #560). Offen für Phase 1: hybride Suche und Reranking.

### Wissensquellen & Indizierung (B)
Drei Aufnahmewege — Verzeichnis, Webverzeichnis/URL-Crawling, Upload — plus **RSS-Feeds als
erster Konnektor-Quellentyp** samt Anlagenübernahme und Government-Site-Builder-Profil
(#463–#468). **Bibliothekstypen mit gespeicherter Quellkonfiguration** (ADR-0018, Epic #486):
Anlage per Template, Verbindungstest, Zeitplan je Bibliothek, sichere Zugangsdatenverwahrung,
Pfad-Allowlist. Formaterkennung anhand des Inhalts statt der Endung (#404), Prüfsummen-Skip,
asynchrone Verarbeitung. Die Quellenzugriffe sind gegen SSRF, Redirect-Tricks und
Zugangsdaten-Exfiltration gehärtet (#267, #538, #617, #651, #693).

### Spaces, Bibliotheken & Rechte (C/F)
Spaces mit Mitgliedschaften und Rollen, persönlicher Space je Nutzer, und **Wissensbibliotheken
als eigenständige Rechteobjekte** mit Grants an Personen und Gruppen (#199–#202). Die Rechteprüfung
sitzt in der Vektorsuche selbst. **Space↔Wissensbibliothek-Zuordnung als Kuratierung**: Nutzer
assoziieren die Bibliotheken, auf die sie berechtigt sind, mit ihren Spaces — wirksam in API und
Retrieval (#203, #686). Gruppen aus dem Verzeichnisabgleich als Rechtesubjekt (#200, #237),
**lückenlose Historisierung von Rechten und Mitgliedschaften** mit Stichtags-Rekonstruktion
(#238), Organisationsgrenze auf Datenbankebene mit strukturellem Prüflauf (#289, #390, #400, #401).

### Identität & Anmeldung (F)
OIDC/Keycloak-Anbindung mit automatischer Nutzerprovisionierung (#108–#110), daneben ein
abgetrennter dev-Modus für die Entwicklung, per Startguard erzwungen. Robustheit gegen
parallele Erstanmeldungen (#293, #307), Silent-Token-Renew statt Sofort-Logout (#737).

### Chat & Oberfläche (I)
**Persistente Chats in Spaces** mit serverseitigem Verlauf, LLM-generierten Titeln und
Chatliste (Epic #523, #557); umfangreiche Race-Condition-Härtung der Frontend-Stores.
**Komplettes UI-Redesign** auf eigenem Designsystem: Design-Tokens, Theme, konfigurierbares
Branding, App-Shell, Assistenten für Space- und Bibliotheksanlage, Fußnoten-Fundstellen mit
Belegfenster, Dunkelmodus (#580–#597, #654, #658). Barrierefreiheit als Richtlinie (BITV/WCAG)
mit automatisierten Prüfungen in Lint und E2E (#584–#586).

### Modelle & zentrale Steuerung (E)
Austauschbare, **OpenAI-kompatible Modellanbieter**, für Chat und Einbettung getrennt
konfigurierbar — lokal betriebene Modelle (vLLM, Ollama) sind die Voreinstellung, eine
unkonfigurierte Installation redet nicht nach außen (#47, #353). Modellverwaltung als
verwaltbare Objekte und zentrale Vorgaben je Space/Bibliothek bleiben Phase-1-Arbeit.

### Sicherheit & Nachweis (G)
Ratenbegrenzung, CORS-Härtung, Sicherheits-Header, Härtungsdokumentation (#61, #62, #409, #250).
Der **Audit-Trail ist geschnitten und in Umsetzung**: Ablage nur-anfügend, Erfassung der
Verwaltungs- und Rechteereignisse, Revisionszugriff ohne Personenbezug, Selbstprotokollierung,
Aufbewahrung mit automatischer Löschung (#355, #391–#395 — Stand zum Stichtag nachtragen).

### Betrieb & Demo (J)
Docker Compose für den Gesamtstack inkl. Keycloak, .env-Schnellstart (#16, #157, #716),
öffentliche Testinstanz, und die **Demo-Instanz „Stadt Rheinfurt“**: generierter
Verwaltungskorpus einer fiktiven Stadt, Seed-Profile für Demo und E2E, Installationsanleitung
und Vorführ-Drehbuch (Epic #708, #709–#713).

## 5 · Ehrlicher Befund: Lücken und bewusste Schnitte

Nichts davon ist verschwiegen — alles ist als Entscheidung oder offenes Issue dokumentiert:

- **Hybride Suche und Reranking fehlen** — reine Vektorsuche ist für attributreiche Fachdaten
  eine bekannte Schwäche (Phase-1-Ziel, offen).
- **Audit-Trail** war zum Inventurstand in Umsetzung, nicht fertig; ohne ihn besteht kein
  Betreiber eine Prüfung mit OPAA im Prüfumfang (#391–#395).
- **Zitierzwang wurde bewusst verkleinert:** statt Verweigerungsmodus und Space-Schalter gibt es
  die deterministische Belegvalidierung — eine dokumentierte Maintainer-Entscheidung, kein
  stilles Scheitern (#354, #387–#389 „not planned").
- **Agenten, Prompts & Werkzeuge (D)** und **Verwaltungs-Spezifika (K, z. B. Leichte Sprache)**
  sind noch nicht begonnen — Phase-2- bzw. spätere Phase-1-Arbeit.
- **DSGVO-Vollständigkeit** (Löschrecht, Export), Schadsoftwareprüfung des Uploads und
  Streaming-Antworten stehen aus.

Der Report nennt durchgängig nur den Endzustand. Zwischenstände, die gebaut und wieder
zurückgebaut wurden, sind keine Leistungsposten; sie bleiben ausschließlich in den
[Bausteinen](./bausteine/) dokumentiert.

## 6 · Zahlen zum Inventurstand

| | |
|---|---|
| Gemergte Pull Requests | 324 |
| Geschlossene Issues | 351 (davon ~10 % bewusst „not planned") |
| Architecture Decision Records | 18 gepflegt |
| Datenbank-Migrationen | fortlaufend über Liquibase, mit strukturellem Schema-Prüflauf |
| Themenbereiche mit Lieferung | 10 von 12 Inventur-Bereichen (offen: D, K) |

---

*Erstellt aus der [Leistungsinventur](./README.md) (Issue #744). Fortschreibung zum Stichtag:
Delta ab `99f61ee1` bzw. Issues geschlossen nach 2026-08-22, siehe [anker.md](./anker.md).*
