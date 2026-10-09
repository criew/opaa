# Konsolidierter Gesamtstand

> Beschreibt, **was heute gebaut ist**. Wird bei jedem Stichtag aus dem Delta des jeweiligen
> [Zeitraumsberichts](./README.md) fortgeschrieben; die Zeitraumsberichte bleiben unverändert
> stehen (historischer Nachweis), dieses Dokument zeigt den aktuellen Zustand. Jede Aussage ist
> über die Bausteine des genannten Stichtags auf Issue, PR und Code rückführbar. Dieses
> Dokument ist die **einzige Quelle für den Umsetzungsstand** (Maintainer-Entscheidung,
> 26.08.2026, #927); das frühere `docs/STATUS.md` ist entfernt.

**Stand: Stichtag 30.09.2026** — Datenstand `main@17ec7fca8` (30.09.2026), im Repository als
Tag `inventur-20260930` verankert. Belege:
[Zeitraumsbericht 20260930](./20260930/report.md) mit den
[Bausteinen mit Befund](./20260930/bausteine.md) für das Delta, der
[Zeitraumsbericht 20260831](./20260831/report.md) für alles davor. Jeder Abschnitt nennt den
Stichtag, mit dem er zuletzt bestätigt wurde.

**Umsetzungsgrad Phase 1:** geschätzt rund 85 % (20260831: rund 80 %); Phase 2 ist mit
Fremdzugängen und Prompt-Bibliothek begonnen.

## Was mit dem Produkt heute konkret möglich ist

*Bestätigt: 20260930*

- **Installieren und betreiben:** kompletter Stack per Docker Compose mit
  Schnellstart-Konfiguration, schlankem Backend-Image ohne root-Rechte, Betriebsmetriken und
  Gesundheitsendpunkt; Upload-Originale wahlweise im Dateisystem oder im Objektspeicher; eine
  öffentliche Testinstanz läuft.
- **Anmelden:** über einen oder mehrere Identitätsanbieter (OIDC) oder eine lokale
  Benutzerverwaltung mit Passwort und Selbstbedienung; Konten entstehen bei der Erstanmeldung
  oder per Einladung.
- **Wissen anbinden:** Wissensbibliotheken per Assistent aus Dateiverzeichnissen,
  Webverzeichnissen, RSS-Feeds, Confluence (Cloud und Data Center) und S3-kompatiblen
  Objektspeichern oder per Upload ganzer Ordnerstrukturen — mit Verbindungstest, Zeitplan,
  Ordner-Navigation, strukturbewusster Aufbereitung aller gängigen Büroformate und Anhängen als
  eigenen Dokumenten.
- **Metadaten pflegen:** Kernfelder und bibliothekseigene Felder mit kontrolliertem Vokabular,
  automatisch ermittelt, von Hand korrigierbar und als Suchfilter nutzbar.
- **Modelle verwalten:** Chat-Modelle über die Administrationsoberfläche anlegen, aktivieren
  und prüfen — Zugangsdaten verschlüsselt, lokal betriebene Modelle als Voreinstellung;
  Reranking als zuschaltbare Modellrolle.
- **Rechtekonform suchen und fragen:** hybride Suche aus Vektor- und Volltext mit Filtern nach
  Dokumentart und Datum; Chat mit Gesprächsverlauf und sichtbarer Gesprächsnotiz; jede Antwort
  mit geprüften Belegen und Sprung ins Original — wer etwas nicht lesen darf, bekommt es auch über
  die Suche nicht zu sehen. Die Suche ist je Stufe diagnostizierbar.
- **Wissen teilen und steuern:** Spaces, Freigaben an Personen, Gruppen und „Alle Konten“;
  Gruppen und Kontostatus aus dem Verzeichnis; Anlegerechte als Fähigkeiten; Herleitung „warum
  sehe ich das“, Stichtagsauskunft und Rechteübertragung bei ausgeschiedenen Konten — jede
  Änderung historisiert und im Audit-Protokoll nachweisbar.
- **Vorlagen teilen:** Prompt-Bibliotheken mit Variablen, im Chat per Slash-Befehl einsetzbar;
  organisationsweiter Katalog aller geteilten Assets.
- **Aus anderen KI-Werkzeugen nutzen:** persönliche Zugangstokens, Such-Endpunkt und MCP-Server
  für Claude Code, Cursor, VS Code und andere — mit installationsweitem Notaus.
- **Moderne, barrierefreie Oberfläche:** eigenes Designsystem mit konfigurierbarem Branding,
  Dunkelmodus und Barrierefreiheit (BITV/WCAG), automatisiert geprüft — durchgängig deutsch.
- **Vorführen:** Demo-Installation „Stadt Rheinfurt" mit fiktivem Verwaltungskorpus in sieben
  Bibliotheken, Gruppen aus dem Verzeichnis, Prompt-Bibliotheken, Nutzerkonten und Drehbuch.

## Stand je Themenbereich

### A · Wissensschicht & Retrieval

*Bestätigt: 20260930*

**Hybride Suche:** Die Abfragestrecke besteht aus benannten Stufen mit Pflicht-Erklärprotokoll —
Teilfragen-Zerlegung mit Kenntnis des Gesprächsverlaufs, Vektorpfad auf pgvector und
lexikalischer Volltextpfad mit Kennungsschutz (Paragraphen, Aktenzeichen), beide mit
Rechtefilter in der Abfrage, zusammengeführt per RRF-Fusion; danach MMR-Diversität,
Dokument-Vervollständigung und optionales Reranking als eigene Modellrolle (Voreinstellung aus).
Harte Filter nach Dokumentart und Datum wirken in beiden Pfaden. Überlappendes Chunking,
gliederungsbewusster Zuschnitt je Format, Contextual Chunking mit Metadaten im Kontextpräfix.
Suchbereichssteuerung per @-Bibliotheksreferenzen im Chat.

**Belege:** Jeder Beleg durchläuft eine deterministische Belegvalidierung und Faktenprüfung
gegen die tatsächlich abgerufenen Fundstellen; ungültige Belege sind sichtbar gekennzeichnet.
Belege führen bis zum Original (Download, Deeplink, Content-Proxy, Objektspeicher,
Nachextraktion von Mail-Anhängen, Herkunfts-Link bei Feed-Anlagen); jede Zitatstelle nennt
Fundort und durchsuchte Bestände; die Belegansicht öffnet über „Belege anzeigen“ und klickbare
Fußnoten.

**Gesprächsgedächtnis:** kurzes, wörtliches Suchfenster und eine sichtbare, punktweise
löschbare Gesprächsnotiz (ADR-0031).

**Metadaten:** Kernfelder mit Herkunftsangabe, deterministischer Bestandslauf, manuelle
Korrektur mit Audit, Bibliotheksfelder mit kontrolliertem Vokabular, zweiphasige
Umschlüsselung; modellgestützte Extraktion vorhanden, aber ausgeschaltet und nicht abgenommen.

**Diagnose und Messbarkeit:** Administrationsseite „Suche & Indexierung“ mit Diagnose je Stufe,
Chunk-Ansicht und „Sicht als (Person)“ unter Befugnis und Protokoll. Suchqualitäts-Benchmark mit
zwei Messpfaden (Rohvektor und produktive Pipeline), Mehrrunden-Messpfad, Variantenvergleichen,
drei Eval-Domänen einschließlich der deutschsprachigen Verwaltungsdomäne, reproduzierbaren
Messfestpunkten und nächtlichem Regressionsjob mit automatischem Alarm.

**Nicht gebaut:** Konfidenz als erklärte Größe, Streaming der Antworten; Reranking ohne
Hardwareprofil und daher nicht voreingestellt; Qualitätsgewinn des Gesprächsgedächtnisses mit
dem Eval-Modell nicht belegt.

### B · Wissensquellen & Indizierung

*Bestätigt: 20260930*

Fünf Konnektoren — Dateiverzeichnis, Webverzeichnis/URL-Crawling, RSS-Feed (mit
GSB-Profil), **Confluence** (Cloud und Data Center, Voll- und inkrementeller Abgleich, Webhooks,
Makro-Regelwerk) und **S3-kompatible Objektspeicher** (Geltungsbereiche, ETag-Erkennung,
Ereignisweg) — plus Upload. Konnektoren sind **steckbar** (ADR-0038): eine Schnittstelle je
Quellart, Einstellungen als JSON, ein gemeinsamer Laufrahmen mit Anfragebudget, Wiederaufnahme,
Unterbrechung und Kennzahlen, ein gemeinsamer Ereignis-Intake. Quellstrukturen werden als
schreibgeschützte Ordner gespiegelt.

**Formate:** eigene, strukturbewusste Aufbereitung für PDF (inkl. Tabellen, Scan-Erkennung),
DOCX, PPTX, ODT, ODP, ODS, XLSX/CSV, HTML, EML/MSG und Markdown, Tika als Rückfall;
Pipeline-Version am Chunk mit selektivem Reindex. Ein neues Format braucht nur Klasse und Bean.
Formaterkennung anhand des Inhalts statt der Endung, Prüfsummen-Skip, asynchrone Verarbeitung.
**Anhänge** sind eigene Dokumente mit Elternbezug (ADR-0022).

Bibliothekstypen mit gespeicherter Quellkonfiguration, Anlage-Assistent und Detailansicht mit
Reitern Dokumente/Quelle/Metadaten/Freigaben; Verbindungstest, Zeitplan, sichere
Zugangsdatenverwahrung, Pfad-Allowlist, Speicherkontingent. Dokumentenverwaltung mit Paging,
Stichwortsuche, Drag-and-drop-Upload, Sammellöschen, Statusanzeige, übersprungenen Dokumenten
mit Grund und letztem Indexstand je Bibliothek. Dokumente verschwundener Quellen werden
aufgeräumt; die Dokumentidentität ist je (Bibliothek, Quelle) gescoped. Die Quellenzugriffe sind
gegen SSRF, Redirect-Tricks, kodierten Pfadaufstieg, Endlosrekursion und Zugangsdaten-Abfluss
gehärtet; Downloads sind gedeckelt, alte Chunks bleiben bis zum erfolgreichen Parsen der neuen
Fassung stehen. Produkthandbuch mit Kapiteln zu Indexierung, allen Konnektoren und Formaten.

**Nicht gebaut:** OCR und Bilderkennung (Docling-PoC #1062 offen), Schadsoftwareprüfung.

### C · Spaces, Assets & Verteilung

*Bestätigt: 20260930*

Spaces mit Mitgliedschaften und Rollen — auch für Gruppen —, persönlicher Space je Nutzer.
Wissensbibliotheken und Prompt-Bibliotheken sind Assets auf einer **gemeinsamen Asset-Schale**
mit typunabhängigen Freigaben, Herleitung und Nachfolge; die Space-Zuordnung wirkt als
Kuratierung in API und Retrieval. **Reichweite ist eine Freigabe** an „Alle Konten“
(ADR-0037); ein organisationsweiter Katalog zeigt alle geteilten Assets. Für
Konnektorbibliotheken gibt es eine Freigabe-Obergrenze.

**Nicht gebaut:** Freigabe- und Prüfworkflow, Versionierung, Export und Import von Assets.

### D · Agenten, Prompts & Werkzeuge

*Bestätigt: 20260930*

**Prompt-Bibliotheken** als erster Asset-Typ neben dem Wissen: Prompts mit Variablen anlegen,
freigeben und im Chat per Slash-Befehl mit Variablenformular einsetzen. Eine Werkzeugschleife
existiert als Spike hinter Schalter. Konzeptionell ausgearbeitet: Agenten und Werkzeuge
(agents-and-tools.md) samt Ausführungsumgebung.

**Nicht gebaut:** Werkzeugaufrufe im Chat (Epic #1747 zurückgestellt), Skills als Objektart
(Epic #1727), Agenten.

### E · Modelle & zentrale Steuerung

*Bestätigt: 20260831; Reranking-Rolle 20260930*

Austauschbare, OpenAI-kompatible Modellanbieter, für Chat und Einbettung getrennt
konfigurierbar — lokal betriebene Modelle (vLLM, Ollama) sind die Voreinstellung, eine
unkonfigurierte Installation redet nicht nach außen. Modellverwaltung Stufe 1: Chat-Modelle
als verwaltbare Objekte mit verschlüsselten Zugangsdaten, Admin-API mit CRUD, Aktivierung und
Verbindungstest, Laufzeitauflösung des aktiven Modells, Administrationsseite mit Reitern für
Chat-Modelle und Einbettung — E2E-abgedeckt. Ollama steht als optionales Compose-Profil bereit.
Reranking als zusätzliche, abschaltbare Modellrolle.

Die Modellkonfiguration ist zentral: Die Systemverwaltung legt fest, welches Modell genutzt wird.
**Nicht gebaut:** Einschränkung darunter je Space/Bibliothek (Modell-Obergrenze) — erst relevant,
wenn mehr als ein Chat-Modell zur Wahl steht.

### F · Identität, Rechte & Mandanten

*Bestätigt: 20260930*

**Anmeldung:** mehrere OIDC-Anbieter aus der Datenbank (ADR-0025), Konten je (Issuer, Subject),
Rollen und Gruppen je Anbieter aus dem Token, Anbieterwahl und automatische Anmeldung bei
laufender Sitzung; alternativ oder ergänzend **lokale Benutzerverwaltung** (ADR-0033) mit
Passwort, rotierenden Refresh-Tokens, Notanker-Systemverwalter, Einladung, Sperre, Ablauf,
Selbstbedienung, Mail-Anbindung und Übergabe an einen Identitätsanbieter. Robust gegen
parallele Erstanmeldungen, Silent-Token-Renew. Dev-Modus per Startguard abgetrennt.

**Rechte** (ADR-0036): typunabhängige Grants an Personen, Gruppen und „Alle Konten“; Gruppen
mit Herkunft (Anbieter oder intern mit Verantwortlichen); **Verzeichnisabgleich je Anbieter
mit Keycloak als erstem Konnektor**, samt Kontostatus — ausgeschiedene Konten werden gesperrt;
„Nachfolge offen“ und Übertragung von Rechten und Eigentum; Anlegerechte als Fähigkeiten. Die
Rechteprüfung sitzt in der Suche selbst. Lückenlose Historisierung mit streng monotonen
Intervallgrenzen, Stichtagsauskunft und Herleitung „warum sehe ich das“, Aufbewahrungshöchstdauer
mit Löschlauf. Organisationsgrenze auf Datenbankebene mit strukturellem Prüflauf. Rechte-,
Space- und Gruppenverwaltung vollständig über die Oberfläche, inklusive
berechtigungsunabhängiger Nutzersuche für die Rechtevergabe.

**Nicht gebaut:** Verzeichnis-Konnektoren jenseits von Keycloak (LDAP, SCIM), mehrere
Organisationen in einer Installation (Epic #1442), Sitzungsverwaltung mit erzwungener
Neuanmeldung.

### G · Sicherheit, Nachweis & Prüfbarkeit

*Bestätigt: 20260930*

Ratenbegrenzung mit Proxy-Auflösung und Kontosperre, CORS-Härtung, Sicherheits-Header,
Härtungsdokumentation. **Lieferkette:** SBOM als Image-Attestierung und CycloneDX-Artefakt,
CVE-Erkennung über Dependabot und Trivy mit Triage-Verfahren, versionierter Unterdrückungsliste
und automatischem Alarm; Backend-Image auf Distroless mit jlink-JRE als
Nicht-root-Nutzer, wöchentlich neu gebaut. **Audit-Trail Stufe 1:** nur-anfügende Ablage mit entzogenen
Änderungsrechten, Erfassung aller Rechte- und Verwaltungsereignisse, Revisionszugriff ohne
personenbezogene Auswertung, Selbstprotokollierung, Aufbewahrung mit wirksamen Löschläufen,
Abfrage-Indizes und strukturell abgesicherte Doppelbuchführung über Domain-Events. Die Diagnose „Sicht
als“ ist an eine befristete Befugnis mit Pflichtbegründung und Protokoll gebunden.
**Benannte Grenzen:** keine Prüfsummenverkettung; die Diagnosesperre einer Bibliothek schützt
über Nachvollziehbarkeit, nicht gegen die Systemverwaltung; DSGVO-Vollständigkeit (Löschrecht,
Selbstauskunft, Export) und Schadsoftwareprüfung des Uploads sind bis vor den Produktivbetrieb
zurückgestellt; signierte Builds fehlen.

### H · Monitoring & Governance

*Bestätigt: 20260831*

Betriebsmetriken und Gesundheitsendpunkt; Speicherkontingente je Bibliothek und Organisation.
(Kennzahlen je Konnektorlauf stehen unter B.)

**Nicht gebaut:** Auswertung von Nutzung und Kosten.

### I · Kanäle & Oberfläche

*Bestätigt: 20260930*

**Web-Oberfläche:** persistente Chats in Spaces mit serverseitigem Verlauf, LLM-generierten
und umbenennbaren Titeln, Titelfilter, Zeitgruppen, Anheften, Archiv, Volltext-Chatsuche und
Kopierfunktion; race-gehärtete Frontend-Stores; Einstieg im zuletzt genutzten Space. Eigenes
Designsystem mit Design-Tokens, konfigurierbarem Branding (kontrastsicher), Dunkelmodus,
App-Shell mit globaler Navigation, einheitlichen Bereichsseiten, Tabellen mit Zeilenmenü,
eigenem Bestätigungs-Overlay, Assistenten für Space- und Bibliotheksanlage und
Fußnoten-Fundstellen mit Belegfenster; Anmeldeseite mit eigenem Logo und Anbieterwahl.
Browservorschau für Originaldokumente; durchgängig deutsch inklusive MUI-Standardtexten
(Entscheidung: deutsch-only).

**Fremdzugänge und API:** persönliche Zugangstokens, Such-Endpunkt ohne Antwortgenerierung und
MCP-Server (`search`, `fetch`, `list_libraries`) hinter einem installationsweiten Schalter mit
Notaus, Netzbereichen, Kontingent und befristeter Bibliotheksfreigabe (ADR-0035). Die REST-API
antwortet mit korrekten Statuscodes (405/406/415 statt 500), deklariert sie nach einer
einheitlichen, per Test durchgesetzten Regel und legt den Fehlerrumpf nur bei passender
Inhaltsaushandlung bei.

**Nicht gebaut:** Antwort-Bewertung (die konzeptlosen Daumen-Schaltflächen wurden entfernt),
Streaming-Darstellung, Anbindung an Team-Chats.

### J · Betrieb & Deployment

*Bestätigt: 20260930*

Docker Compose für den Gesamtstack inklusive Keycloak, .env-Schnellstart, konfigurierbares
Datenbankschema, sanftes Herunterfahren mit Readiness ohne Abhängigkeit von KI-Diensten,
Originalablage wahlweise im Dateisystem oder S3-kompatibel (ADR-0030) mit Aufräumlauf,
`deployment.md` als eigenständiges Betriebshandbuch, Flyer mit Installationsvoraussetzungen,
öffentliche Testinstanz. Demo-Instanz „Stadt Rheinfurt": generierter Verwaltungskorpus in
sieben Bibliotheken über Webverzeichnis, RSS, Upload und S3 (inklusive Formattest-Bibliothek), Gruppen per
Verzeichnisabgleich, Prompt-Bibliotheken, Seed-Profile für Demo und E2E, Vorführ-Drehbuch und
Demo-Video. Das Backend ist auf Single-Instance-Betrieb ausgelegt (ADR-0021). Helm-Chart für den
Betrieb unter Kubernetes mit einer Backend-Instanz (ADR-0042), beschrieben im Handbuchkapitel
`kubernetes.md`.

**Nicht gebaut:** Hochverfügbarkeit unter Kubernetes (Epic #1439), Multiinstanzbetrieb (Epic #1292),
air-gapped-Lieferung.

### K · Verwaltungs-Spezifika

*Bestätigt: 20260831*

Barrierefreiheit als Richtlinie (BITV 2.0 / WCAG 2.1 AA) mit automatisierten Prüfungen in
Lint und E2E sowie manuell abgenommenem Abschluss-Audit mit Prüfprotokoll — alle Befunde
behoben.
**Nicht gebaut:** Textwerkzeuge einschließlich Leichter Sprache.

## Technisches Fundament & Arbeitsweise

*Bestätigt: 20260930*

Java 21 / Spring Boot 4.1 / Spring AI 2.0, React 19 / TypeScript 6 / MUI 9 / Vite 8,
PostgreSQL 18 mit pgvector. **Modularer Monolith ohne Paketzyklen:** logische Module
(u. a. Retrieval, Formate, Metadaten, Konnektoren) und ihre Schichtung per ArchUnit im Build
erzwungen, Controller in `web`-Paketen der Module. API-First: alle DTOs aus der
OpenAPI-Spezifikation im Gradle-Modul `opaa-api`, aufgeteilt in Dateien je Thema;
Domain-Services ohne DTO-Kenntnis, Domain-Exceptions, zentralisierte Aufrufer-Identität. Liquibase mit
einer Baseline je Modul und Changelogs mit Datums-Dateinamen. 36 gepflegte ADRs. Testfundament:
Unit-, Integrations- und Migrationstests, vier Spring-Testkontexte mit Isolationswächtern,
Container-Suiten gegen Confluence, S3-Objektspeicher und Keycloak, Playwright-E2E gegen den
echten Compose-Stack (nächtlich und auf `main`), Struktur-Wächter für API-Regeln;
plattformübergreifend lauffähig. CI/CD über GitHub Actions mit drei parallelen
Backend-Test-Shards, Docker-Images nach GHCR, Branch-Schutz und Auto-Merge; selbst betriebene
Abhängigkeits-Updates über Renovate mit gehärtetem Auto-Merge-Betrieb. Produkthandbuch unter
`docs/handbuch/`. Mensch-KI-Kollaborationsmodell mit dokumentierten Agenten-Rollen,
verbindlichen Arbeitsregeln (AGENTS.md, je Modul ergänzt), Projektsprache Deutsch, AGPL-3.0
mit CLA-Prozess und täglichem Projektreport.

## Offene Phase-1-Arbeit

Die priorisierte Restliste gegen die Phase-1-Definition der Vision steht im
[Zeitraumsbericht 20260930, Abschnitt 9](./20260930/report.md#9--offen-für-phase-1--priorisierte-restliste);
die bewussten Schnitte und Zurückstellungen in
[Abschnitt 8](./20260930/report.md#8--lücken-und-bewusste-schnitte).
