# Konsolidierter Gesamtstand

> Beschreibt, **was heute gebaut ist**. Wird bei jedem Stichtag aus dem Delta des jeweiligen
> [Zeitraumsberichts](./README.md) fortgeschrieben; die Zeitraumsberichte bleiben unverändert
> stehen (historischer Nachweis), dieses Dokument zeigt den aktuellen Zustand. Jede Aussage ist
> über die Bausteine des genannten Stichtags auf Issue, PR und Code rückführbar. Dieses
> Dokument ist die **einzige Quelle für den Umsetzungsstand** (Maintainer-Entscheidung,
> 26.08.2026, #927); das frühere `docs/STATUS.md` ist entfernt.

**Stand: Stichtag 31.08.2026** — Datenstand `main@a51e6b8c` (30.08.2026), im Repository als
Tag `inventur-20260830` verankert. Belege: [Zeitraumsbericht 20260831](./20260831/report.md)
mit den [Bausteinen mit Befund](./20260831/bausteine.md). Alle Aussagen dieses Dokuments tragen diesen Stand.

## Was mit dem Produkt heute konkret möglich ist

- **Installieren und betreiben:** kompletter Stack per Docker Compose mit
  Schnellstart-Konfiguration, Betriebsmetriken und Gesundheitsendpunkt; eine öffentliche
  Testinstanz läuft.
- **Anmelden:** über den Verzeichnisdienst des Hauses (OIDC/Keycloak), Konten entstehen
  automatisch bei der Erstanmeldung.
- **Wissen anbinden:** Wissensbibliotheken per Vorlage — aus Dateiverzeichnissen,
  Webverzeichnissen und RSS-Feeds (inkl. Behörden-Websites auf GSB-Basis) oder per Upload mit
  Drag-and-drop, auch ganzer Ordnerstrukturen; mit Verbindungstest, Zeitplan,
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
  Verwaltungskorpus, mehreren Nutzerkonten und Drehbuch.

## Stand je Themenbereich

### A · Wissensschicht & Retrieval

Vektorsuche auf pgvector mit rechtebewusster Filterung; Antwortgenerierung mit
Quellenangaben. Jeder Beleg durchläuft eine **deterministische Belegvalidierung** gegen die
tatsächlich abgerufenen Fundstellen, ergänzt um eine deterministische **Faktenprüfung der
Zitate**; ungültige Belege sind sichtbar gekennzeichnet. Das Retrieval arbeitet mit
Teilfragen-Zerlegung und kontextbewusster Reformulierung (Multi-Query-RAG), MMR-Diversität,
Dokument-Vervollständigung nach der Fusion, überlappendem Chunking und Contextual Chunking
(Dokumentkontext im Chunk-Embedding). Belege führen bis zum Original: Download-Endpunkt,
Deeplink in die Wissensbibliothek, serverseitiger Content-Proxy, Herkunfts-Link bei
Feed-Anlagen; jede Zitatstelle nennt Fundort und durchsuchte Bestände. Suchbereichssteuerung
per @-Bibliotheksreferenzen im Chat. Die **Suchqualität ist messbar**: zwei Eval-Korpora,
Golden Datasets, Metrik-Harness (Hit Rate, MRR, nDCG, Recall) und ein CI-Regressionsjob mit
Baseline, der Verschlechterungen als Issue meldet. Der Ist-Stand des Retrieval-Algorithmus und
die akzeptierte Grenze der reinen Vektorsuche sind als Spezifikation dokumentiert.
**Nicht gebaut:** hybride Suche, Reranking, Konfidenz als erklärte Größe, Streaming-Antworten.

### B · Wissensquellen & Indizierung

Drei Aufnahmewege — Dateiverzeichnis, Webverzeichnis/URL-Crawling, Upload — plus RSS-Feeds als
Konnektor-Quellentyp samt Anlagenübernahme und Government-Site-Builder-Profil.
Bibliothekstypen mit gespeicherter Quellkonfiguration (ADR-0018): Anlage per Template,
Verbindungstest, Zeitplan je Bibliothek, sichere Zugangsdatenverwahrung, Pfad-Allowlist.
Formaterkennung anhand des Inhalts statt der Endung, Prüfsummen-Skip, asynchrone Verarbeitung,
Speicherkontingent je Bibliothek. Dokumentenverwaltung in der Oberfläche: Dokumentliste mit
Paging und Stichwortsuche, Upload per Drag-and-drop, Löschen, Statusanzeige, übersprungene
Dokumente mit Grund, letzter Indexstand je Bibliothek. Ordner in Bibliotheken mit Navigation,
ordner-bewusstem Upload ganzer Verzeichnisse und FILESYSTEM-Quellen als read-only-Ordnerbaum.
Dokumente verschwundener Quellen werden aufgeräumt; die Dokumentidentität ist je (Bibliothek,
Quelle) gescoped; der Crawler ist gegen Endlosrekursion begrenzt. Die Quellenzugriffe sind
gegen SSRF, Redirect-Tricks und Zugangsdaten-Exfiltration gehärtet.

### C/F · Spaces, Bibliotheken, Identität & Rechte

Spaces mit Mitgliedschaften und Rollen, persönlicher Space je Nutzer; Wissensbibliotheken als
eigenständige Rechteobjekte mit Grants an Personen und Gruppen. Die Rechteprüfung sitzt in der
Vektorsuche selbst. Space↔Bibliothek-Zuordnung als Kuratierung, wirksam in API und Retrieval.
Gruppen aus dem Verzeichnisabgleich als Rechtesubjekt; lückenlose Historisierung von Rechten
und Mitgliedschaften mit Stichtags-Rekonstruktion; Organisationsgrenze auf Datenbankebene mit
strukturellem Prüflauf. Rechteverwaltung vollständig über die Oberfläche, inklusive
berechtigungsunabhängiger Nutzersuche für die Rechtevergabe. Anmeldung über OIDC/Keycloak mit
automatischer Nutzerprovisionierung, robust gegen parallele Erstanmeldungen,
Silent-Token-Renew; daneben ein abgetrennter dev-Modus, per Startguard erzwungen.
**Nicht gebaut:** echter Verzeichnisanschluss (hinter dem Abgleich steht ein No-Op-Client),
Sitzungsverwaltung mit erzwungener Neuanmeldung.

### D · Agenten, Prompts & Werkzeuge

**Nicht begonnen** — zweite Säule der Vision, Phase 2. Als Vorarbeit existiert eine
dokumentierte Grundlagenrecherche zu Agent-Loop, Frameworks und Laufzeitumgebung.

### E · Modelle & zentrale Steuerung

Austauschbare, OpenAI-kompatible Modellanbieter, für Chat und Einbettung getrennt
konfigurierbar — lokal betriebene Modelle (vLLM, Ollama) sind die Voreinstellung, eine
unkonfigurierte Installation redet nicht nach außen. Modellverwaltung Stufe 1: Chat-Modelle
als verwaltbare Objekte mit verschlüsselten Zugangsdaten, Admin-API mit CRUD, Aktivierung und
Verbindungstest, Laufzeitauflösung des aktiven Modells, Administrationsseite mit
Einbettungsübersicht — E2E-abgedeckt. Ollama steht als optionales Compose-Profil bereit.
**Nicht gebaut:** zentrale Modellvorgaben als Obergrenze je Space/Bibliothek.

### G · Sicherheit, Nachweis & Prüfbarkeit

Ratenbegrenzung, CORS-Härtung, Sicherheits-Header, Härtungsdokumentation. Audit-Trail
Stufe 1: nur-anfügende Ablage mit entzogenen Änderungsrechten, Erfassung aller Rechte- und
Verwaltungsereignisse, Revisionszugriff ohne personenbezogene Auswertung,
Selbstprotokollierung jedes Protokollzugriffs, Aufbewahrung mit automatischer Löschung,
Abfrage-Indizes und strukturell abgesicherte Doppelbuchführung über Domain-Events. **Benannte
Grenzen:** keine Prüfsummenverkettung (Manipulation mit direktem Datenbankzugang liegt beim
Betreiber); Audit-Betriebshärtung, DSGVO-Vollständigkeit (Löschrecht, Selbstauskunft, Export)
und Schadsoftwareprüfung des Uploads sind bewusst bis vor den Produktivbetrieb zurückgestellt.

### H · Monitoring & Governance

Betriebsmetriken und Gesundheitsendpunkt; Speicherkontingente je Bibliothek und Organisation.

### I · Kanäle & Oberfläche

Persistente Chats in Spaces mit serverseitigem Verlauf, LLM-generierten Titeln und Chatliste;
race-gehärtete Frontend-Stores. Eigenes Designsystem: Design-Tokens, Theme, konfigurierbares
Branding, App-Shell, Assistenten für Space- und Bibliotheksanlage, Fußnoten-Fundstellen mit
Belegfenster, Dunkelmodus; globale Navigationsleiste (Rail) mit abgesetztem Verwaltungsrahmen.
Browservorschau für Originaldokumente; durchgängig deutsche Oberfläche inklusive
MUI-Standardtexten (Entscheidung: deutsch-only). **Nicht gebaut:** Antwort-Bewertung mit
Speicherung, Streaming-Darstellung.

### J · Betrieb & Deployment

Docker Compose für den Gesamtstack inklusive Keycloak, .env-Schnellstart, öffentliche
Testinstanz, `deployment.md` als allgemeines Betriebshandbuch. Demo-Instanz „Stadt Rheinfurt":
generierter Verwaltungskorpus, Seed-Profile für Demo und E2E mit
Space↔Bibliothek-Zuordnungen, Installationsanleitung, Vorführ-Drehbuch, Demo-Video auf der
Projektseite. **Nicht gebaut:** Kubernetes/Hochverfügbarkeit, air-gapped-Lieferung,
Software-Stückliste, signierte Builds.

### K · Verwaltungs-Spezifika

Barrierefreiheit als Richtlinie (BITV 2.0 / WCAG 2.1 AA) mit automatisierten Prüfungen in
Lint und E2E sowie manuell abgenommenem Abschluss-Audit mit Prüfprotokoll — alle Befunde
behoben. **Nicht gebaut:** Textwerkzeuge einschließlich Leichter Sprache.

## Technisches Fundament & Arbeitsweise

Java 21 / Spring Boot 4.1 / Spring AI 2.0, React 19 / TypeScript 6 / MUI 9 / Vite 8,
PostgreSQL 18 mit pgvector, Liquibase mit konsolidierter Baseline. API-First: alle DTOs aus
der OpenAPI-Spezifikation im Gradle-Modul `opaa-api`; Domain-Services ohne DTO-Kenntnis,
Domain-Exceptions, zentralisierte Aufrufer-Identität. 19 gepflegte ADRs. Testfundament:
Unit-, Integrations- und Migrationstests, Playwright-E2E gegen den echten Compose-Stack,
konsolidierte Testkontexte (Backend-Suite 3:13 Minuten), plattformübergreifend lauffähig.
CI/CD über GitHub Actions mit Docker-Images nach GHCR, Branch-Schutz und Auto-Merge; selbst
betriebene Abhängigkeits-Updates über Renovate mit gehärtetem Auto-Merge-Betrieb.
Mensch-KI-Kollaborationsmodell mit dokumentierten Agenten-Rollen, verbindlichen Arbeitsregeln
(AGENTS.md), Projektsprache Deutsch, AGPL-3.0 mit CLA-Prozess und täglichem Projektreport.

## Offene Phase-1-Arbeit

Die priorisierte Restliste gegen die Phase-1-Definition der Vision steht im
[Zeitraumsbericht 20260831, Abschnitt 6](./20260831/report.md#6--offen-für-phase-1--priorisierte-restliste);
die bewussten Schnitte und Zurückstellungen in
[Abschnitt 5](./20260831/report.md#5--ehrlicher-befund-lücken-und-bewusste-schnitte).
