# Konsolidierter Gesamtstand

> Beschreibt, **was heute gebaut ist**. Wird bei jedem Stichtag aus dem Delta des jeweiligen
> [Zeitraumsberichts](./README.md) fortgeschrieben; die Zeitraumsberichte bleiben unverändert
> stehen (historischer Nachweis), dieses Dokument zeigt den aktuellen Zustand. Jede Aussage ist
> über die Bausteine des genannten Stichtags auf Issue, PR und Code rückführbar. Dieses
> Dokument ist die **einzige Quelle für den Umsetzungsstand** (Maintainer-Entscheidung,
> 26.08.2026, #927); das frühere `docs/STATUS.md` ist entfernt.

**Stand: Stichtag 31.10.2026** — Datenstand `main@39a00d1da` (31.10.2026, Stand von
`v0.1.0-rc.5`), im Repository als Tag `inventur-20261031` verankert. Belege:
[Zeitraumsbericht 20261031](./20261031/report.md) mit den
[Bausteinen mit Befund](./20261031/bausteine.md) für das Delta, die Zeitraumsberichte
[20260930](./20260930/report.md) und [20260831](./20260831/report.md) für alles davor. Jeder
Abschnitt nennt den Stichtag, mit dem er zuletzt bestätigt wurde.

**Umsetzungsgrad Phase 1:** geschätzt rund 90 % (20260930: rund 85 %); Phase 2 ist mit
Fremdzugängen, Prompt-Bibliothek und MCP-Server als Zugangsprofil begonnen.

## Was mit dem Produkt heute konkret möglich ist

*Bestätigt: 20261031*

- **Installieren und betreiben:** versionierte Releases mit signierten Images (amd64 und arm64)
  und attestiertem Helm-Chart; Betrieb per Docker Compose oder unter Kubernetes, mit einer oder
  mehreren gleichrangigen Backend-Instanzen; Erstinstallation per Browser-Assistent oder
  Antwortdatei; Betrieb ohne Internetzugang mit Offline-Bündel; eine öffentliche Testinstanz läuft.
- **Nachweisbar aktualisieren:** Jedes Release bringt ein attestiertes Update-Paket mit
  Manifest-Delta, Faktenblättern, Behördenvorlagen und Handbuch; Supportlinien mit LTS.
- **Anmelden:** über einen oder mehrere Identitätsanbieter (OIDC) oder eine lokale
  Benutzerverwaltung mit Passwort und Selbstbedienung; erneute Anmeldung vor sensiblen Vorgängen.
- **Wissen anbinden:** Wissensbibliotheken aus Dateiverzeichnissen, Webverzeichnissen, RSS-Feeds,
  Confluence, S3, Google Drive, Nextcloud, SMB-Freigaben, SharePoint, Dropbox und OneDrive for
  Business oder per Upload — mit geführter Quelleinrichtung, Zugängen mit Freigabe, verbundenen
  Konten für **private Bibliotheken**, installationsweitem Ausgangsproxy, strukturbewusster
  Aufbereitung aller gängigen Büroformate und optionaler Virenprüfung.
- **Metadaten pflegen:** Kernfelder und bibliothekseigene Felder mit kontrolliertem Vokabular,
  automatisch ermittelt, von Hand korrigierbar und als Suchfilter nutzbar.
- **Modelle verwalten:** Chat-, Embedding-, Rerank- und Transkriptionsmodell in der
  Administrationsoberfläche; geführter Wechsel des Embedding-Modells; lokal betriebene Modelle als
  Voreinstellung.
- **Rechtekonform suchen und fragen:** hybride Suche mit Filtern; Chat mit Gesprächsverlauf,
  Spracheingabe und Export; jede Antwort als KI-erzeugt gekennzeichnet, mit geprüften Belegen und
  Sprung an die Fundstelle im Original — wer etwas nicht lesen darf, bekommt es auch über die Suche
  nicht zu sehen.
- **Wissen teilen und steuern:** ein Katalog für alle Assets mit Favoriten; Spaces mit Anweisung
  und harter Suchgrenze; Freigaben an Personen, Gruppen und „Alle Konten“; Gruppen und Kontostatus
  aus dem Verzeichnis; Herleitung, Stichtagsauskunft und Rechteübertragung — jede Änderung
  historisiert und im Revisionsprotokoll nachweisbar.
- **Gegenüber Behörden belegen:** Revisionsprotokoll mit Prüfsummenkette und optionaler
  SIEM-Ausleitung, Selbstauskunft für jede Person, Faktenblätter und redaktionelle Vorlagen für
  Datenschutz, Mitbestimmung, Sicherheit und KI-Verordnung.
- **Vorlagen teilen:** Prompt-Bibliotheken mit Variablen, im Chat per Slash-Befehl einsetzbar.
- **Aus anderen KI-Werkzeugen nutzen:** persönliche Zugangstokens, Such-Endpunkt und MCP-Server
  für Claude Code, Cursor, VS Code und andere — hinter einem Feature-Flag mit Notaus.
- **Moderne, barrierefreie Oberfläche:** eigenes Designsystem mit konfigurierbarem Branding,
  Dunkelmodus und Barrierefreiheit (BITV/WCAG, Kontrast als Regel), automatisiert geprüft —
  durchgängig deutsch.
- **Vorführen:** Demo-Installation „Stadt Rheinfurt" mit fiktivem Verwaltungskorpus, Gruppen aus dem
  Verzeichnis, Prompt-Bibliotheken, Nutzerkonten und Drehbuch.

## Stand je Themenbereich

### A · Wissensschicht & Retrieval

*Bestätigt: 20261031*

**Hybride Suche:** Die Abfragestrecke besteht aus benannten Stufen mit Pflicht-Erklärprotokoll —
Teilfragen-Zerlegung mit Kenntnis des Gesprächsverlaufs und Quote je Teilfrage, Vektorpfad auf
pgvector und lexikalischer Volltextpfad mit Kennungsschutz (Paragraphen, Aktenzeichen,
geschützte Bezeichner), beide mit Rechtefilter in der Abfrage, zusammengeführt per RRF-Fusion mit
Spitzentreffern je Liste; danach MMR-Diversität, ein Chunk je Dokument, Nachbar-Chunks im
Antwortkontext, optionales Reranking und ein optionaler Relevanzschnitt (beide ab Werk aus).
Harte Filter nach Dokumentart und Datum wirken in beiden Pfaden. Gliederungsbewusster Zuschnitt
je Format (Chunkgröße 4.000 Zeichen), Contextual Chunking mit Metadaten im Kontextpräfix,
Fundstellen-Anker je Chunk. Suchbereich über Space-Zuordnung und @-Bibliotheksreferenzen.

**Belege:** Jeder Beleg durchläuft eine deterministische Belegvalidierung und Faktenprüfung
gegen die tatsächlich abgerufenen Fundstellen; ungültige Belege sind sichtbar gekennzeichnet.
Belege führen bis zum Original: PDF-Viewer mit markierter Fundseite, Sprung an den Abschnitt bei
Markdown, Text und Confluence, Textfassung mit Original-Download für die übrigen Formate. Fundstellen
gehen als gekennzeichnete Daten an das Modell (Schutz gegen Prompt-Injection).

**Gesprächsgedächtnis:** kurzes, wörtliches Suchfenster und eine sichtbare, punktweise
löschbare Gesprächsnotiz (ADR-0031).

**Metadaten:** Kernfelder mit Herkunftsangabe, deterministischer Bestandslauf, manuelle
Korrektur mit Audit, Bibliotheksfelder mit kontrolliertem Vokabular, zweiphasige
Umschlüsselung; modellgestützte Extraktion vorhanden, aber ausgeschaltet und nicht abgenommen.

**Diagnose und Messbarkeit:** Administrationsseite „Suche & Indexierung“ mit Diagnose je Stufe,
Chunk-Ansicht und „Sicht als (Person)“ unter Befugnis und Protokoll. Suchqualitäts-Benchmark mit
zwei Messpfaden, Mehrrunden-Messpfad, Variantenvergleichen, drei Eval-Domänen, reproduzierbaren
Messfestpunkten und nächtlichem Regressionsjob mit automatischem Alarm.

**Nicht gebaut:** Konfidenz als erklärte Größe, Streaming der Antworten; Reranking ohne
Hardwareprofil und daher nicht voreingestellt; Qualitätsgewinn des Gesprächsgedächtnisses mit
dem Eval-Modell nicht belegt.

### B · Wissensquellen & Indizierung

*Bestätigt: 20261031*

Elf Konnektoren — Dateiverzeichnis, Webverzeichnis/URL-Crawling, RSS-Feed (Inhaltsselektor und
Anlagenprofil je Bibliothek), Confluence (Cloud und Data Center), S3-kompatible Objektspeicher und,
auf einem gemeinsamen Datei-Abgleich (ADR-0045), **Google Drive, Nextcloud, SMB, SharePoint,
Dropbox und OneDrive for Business** — plus Upload. Konnektoren sind steckbar (ADR-0038) und laufen
nur in einem vom Kern gestarteten Lauf mit Anfragebudget, Wiederaufnahme und Kennzahlen; Umfang
„Alle“ bei Confluence, S3, Google Drive und SharePoint.

**Zugänge und Verbindungen** (ADR-0041, ADR-0048, ADR-0049): Die Systemverwaltung richtet
Zugänge mit Freigabe und Sperre ein; Personen verbinden eigene Konten per OAuth (PKCE) und betreiben
damit **private Bibliotheken** mit Kontingent und nachweisbarer Löschung. Ein installationsweiter
Ausgangsproxy mit nicht abschaltbarer Zertifikatsprüfung gilt für alle Quellen (ADR-0050).
Verbindungstests unterscheiden Netz-, TLS- und Anmeldefehler.

**Formate:** eigene, strukturbewusste Aufbereitung für PDF (inkl. Tabellen, seitenweiser
Scan-Erkennung), DOCX, PPTX, ODT, ODP, ODS, XLSX/CSV, HTML, EML/MSG und Markdown, Tika als Rückfall;
gegen Ressourcen-Erschöpfung gehärtet und per Fuzzing geprüft. Anhänge sind eigene Dokumente
(ADR-0022). Optionale Schadprogrammprüfung über clamd vor der Aufnahme (ab Werk aus).

Bibliotheken mit geführter Quelleinrichtung, Detailansicht mit Statusfilter, Verbindungstest,
Zeitplan, sicherer Zugangsdatenverwahrung, Speicherkontingent und Dokumentenverwaltung. Die
Quellenzugriffe sind gegen SSRF, DNS-Rebinding, Redirect-Tricks und Zugangsdaten-Abfluss gehärtet.

**Nicht gebaut:** OCR und Bilderkennung (Docling-PoC #1062 geparkt), Exchange/Outlook (Epic #2172
geparkt), OneDrive für private Microsoft-Konten.

### C · Spaces, Assets & Verteilung

*Bestätigt: 20261031*

Spaces mit Mitgliedschaften und Rollen — auch für Gruppen —, persönlicher Space „Privater Bereich“
je Person, immer privat. Jeder Space kann eine eigene Anweisung tragen (ADR-0043) und inaktive
Chats automatisch aufräumen. Wissensbibliotheken und Prompt-Bibliotheken sind Assets auf einer
gemeinsamen Asset-Schale mit typunabhängigen Freigaben, Herleitung und Nachfolge. **Ein Katalog**
ist der einzige Einstieg zu allen Assets, mit Favoriten; die Space-Zuordnung ist eine harte Grenze
der Suche (ADR-0039). Reichweite ist eine Freigabe an „Alle Konten“ (ADR-0037).

**Nicht gebaut:** Freigabe- und Prüfworkflow, Versionierung, Export und Import von Assets.

### D · Agenten, Prompts & Werkzeuge

*Bestätigt: 20261031*

**Prompt-Bibliotheken** als Asset-Typ: Prompts mit Variablen anlegen, freigeben und im Chat per
Slash-Befehl einsetzen. Der MCP-Server ist als Profilart von Zugängen angelegt. Eine
Werkzeugschleife existiert als Spike hinter Schalter.

**Nicht gebaut:** Werkzeugaufrufe im Chat (Epic #1747 geparkt), Skills als Objektart (Epic #1727
geparkt), Agenten.

### E · Modelle & zentrale Steuerung

*Bestätigt: 20261031*

Austauschbare, OpenAI-kompatible Modellanbieter; lokal betriebene Modelle sind die Voreinstellung,
eine unkonfigurierte Installation redet nicht nach außen. Die Modellverwaltung führt Chat-,
Embedding-, Rerank- und Transkriptionsmodell mit verschlüsselten Zugangsdaten, Verbindungstest und
Ermittlung der Modelle am Endpunkt; Modell-Endpunkte haben eine eigene Freigabeliste. Das
Embedding-Modell wird bei der Installation gewählt und lässt sich geführt wechseln, mit
Neueinbettung im Hintergrund (ADR-0061). Der Denktext von Reasoning-Modellen wird entfernt.
Installationsweite Feature-Flags schalten Funktionen zentral (ADR-0044).

**Nicht gebaut:** Modell-Obergrenze je Space/Bibliothek — erst relevant, wenn mehr als ein
Chat-Modell zur Wahl steht.

### F · Identität, Rechte & Mandanten

*Bestätigt: 20261031*

**Anmeldung:** mehrere OIDC-Anbieter aus der Datenbank (ADR-0025), Konten je (Issuer, Subject),
Rollen und Gruppen je Anbieter aus dem Token; alternativ oder ergänzend **lokale
Benutzerverwaltung** (ADR-0033) mit Passwort, Fehlversuchssperre als Einstellung,
Notanker-Systemverwalter mit Schutz, Einladung, Sperre, Ablauf, Selbstbedienung, Mail-Anbindung über
eine Warteschlange und Übergabe an einen Identitätsanbieter. Erneute Anmeldung vor sensiblen
Vorgängen. Konten ruhen nach Inaktivität. Die Rolle Revision wird beantragt und bestätigt.

**Rechte** (ADR-0036): typunabhängige Grants an Personen, Gruppen und „Alle Konten“; Gruppen
mit Herkunft; Verzeichnisabgleich je Anbieter mit Keycloak als erstem Konnektor, samt Kontostatus
und Status gescheiterter Läufe; „Nachfolge offen“ und Übertragung von Rechten und Eigentum mit
Zeilensperre; Anlegerechte als Fähigkeiten. Die Rechteprüfung sitzt in der Suche selbst; die
Rechtekanten sind per Rechte-Matrix je Operation abgesichert. Lückenlose Historisierung mit
Stichtagsauskunft auch des Kontozustands und Herleitung „warum sehe ich das“. Organisationsgrenze
auf Datenbankebene.

**Nicht gebaut:** Verzeichnis-Konnektoren jenseits von Keycloak (LDAP, SCIM), mehrere
Organisationen in einer Installation (Epic #1442 geparkt), Sitzungsverwaltung mit erzwungener
Neuanmeldung.

### G · Sicherheit, Nachweis & Prüfbarkeit

*Bestätigt: 20261031*

**Härtung:** Ratenbegrenzung, CORS, Sicherheits-Header, Schutz gegen DNS-Rebinding (ADR-0063),
Schutz gegen Prompt-Injection, Parser gegen Ressourcen-Erschöpfung gehärtet. Ein Security-Audit
2026 mit acht Prüfbereichen ist abgeschlossen; dauerhaft prüfen Rechte-Matrix, Architekturtests,
nächtliches Parser-Fuzzing und ein ZAP-Scan. Verschlüsselte Geheimnisse mit Schlüsselwechsel
(ADR-0064). **Lieferkette:** SBOM als Attestierung, CVE-Erkennung über Dependabot und Trivy,
CodeQL, signierte Images, Gradle Dependency Verification, Actions per Commit-SHA; Images ohne root
mit nur lesbarem Dateisystem, wöchentlich neu gebaut.

**Revisionsprotokoll** (ADR-0059): nur-anfügende Ablage mit Ereigniskatalog und Schemaversion,
monatlicher Prüfsummenkette mit Siegel, pseudonymisierten Kontokennungen, Revisionszugriff ohne
Personenkennungen, Aufbewahrung mit wirksamen Löschläufen und Metriken; optionale Ausleitung an ein
Sicherheitsmonitoring per Syslog (ADR-0055, ab Werk aus).

**Fakten für Behörden** (ADR-0052): Fakten-Manifest mit Wächter, per `@DataFlow` erzwungene
Datenflüsse, erzeugte Faktenblätter, Lizenzprüfung der Abhängigkeiten und ein attestiertes
Update-Paket je Release. KI-erzeugte Antworten sind nach Art. 50 KI-VO gekennzeichnet.

**Benannte Grenzen:** Die Diagnosesperre einer Bibliothek schützt über Nachvollziehbarkeit, nicht
gegen die Systemverwaltung; das Anhalten der Chat-Löschung gesperrter Konten liegt ohne Begründung
und Höchstdauer bei der Systemverwaltung.

### H · Monitoring & Governance

*Bestätigt: 20261031*

Betriebsmetriken und Gesundheitsendpunkt, im Helm-Chart mit ServiceMonitor und Grundalarmen;
Metriken zu Revisionsprotokoll und SIEM-Ausleitung; Speicherkontingente je Bibliothek und
Organisation sowie für private Bibliotheken.

**Nicht gebaut:** Auswertung von Nutzung und Kosten.

### I · Kanäle & Oberfläche

*Bestätigt: 20261031*

**Web-Oberfläche:** persistente Chats in Spaces mit serverseitigem Verlauf, Titeln, Anheften,
Archiv, Volltext-Chatsuche, Kopierfunktion, **Chatexport** und **Spracheingabe** über das
Transkriptionsmodell. Seitenleiste mit Spaces als Liste, Katalog mit Kacheln, geführte
Quelleinrichtung, Detailansicht der Bibliothek, Belegfenster mit Sprung an die Fundstelle,
überarbeitete Administrationsseiten mit einheitlichem Zeilenmenü. Eigenes Designsystem mit
Design-Tokens, konfigurierbarem Branding, Dunkelmodus und Gestaltungsleitlinien;
Kontrastregeln (WCAG AA, Fokusring 3:1) per Test durchgesetzt; durchgängig deutsch.

**Fremdzugänge und API:** „Externe KI-Werkzeuge“ mit persönlichen Zugangstokens, Such-Endpunkt
und MCP-Server hinter einem Feature-Flag mit Notaus, Netzbereichen, Kontingent und befristeter
Bibliotheksfreigabe (ADR-0035); Tokens ruhen, wenn die letzte Anmeldung beim Identitätsanbieter zu
lange zurückliegt. Die REST-API antwortet mit korrekten Statuscodes nach einer per Test
durchgesetzten Regel.

**Nicht gebaut:** Antwort-Bewertung, Streaming-Darstellung, Live-Transkript (#2615), Anbindung an
Team-Chats.

### J · Betrieb & Deployment

*Bestätigt: 20261031*

**Lieferung:** versionierte Releases `vX.Y.Z` mit unveränderlichen, signierten Images für amd64
und arm64, Release-Zyklus mit Supportlinien, LTS und Wartungsbranches (ADR-0054), Update-Paket
mit Offline-Bündel und Offline-Handbuch. **Installation** im Installationsmodus per
Browser-Assistent oder Antwortdatei; fachliche Werte (Fristen, Rhythmen, Modelle, Mail, Branding)
liegen in der Datenbank und lassen sich exportieren (ADR-0060). Schema-Migration mit Flyway, eine
Baseline je Modul (ADR-0056).

**Betrieb:** Docker Compose für den Gesamtstack, Helm-Chart für Kubernetes (ADR-0042) mit einer
oder mehreren gleichrangigen Backend-Instanzen gegen eine Datenbank (ADR-0053): Instanzregister,
Invalidierungsbus, Laufbesitz über Heartbeats, installationsweite Budgets, Aktualisierung ohne
Ausfall, Zwei-Instanzen-Suite unter Compose und auf kind. Originalablage im Dateisystem oder
S3-kompatibel. Automatisch belegter Betrieb ohne Internetzugang und automatische
Wiederherstellungsprobe. Demo-Instanz „Stadt Rheinfurt" mit Seed, Drehbuch und Video.

**Nicht gebaut:** Erprobung auf einem produktiven Cluster (bisher k3d und kind), getrennte Dienste
für große Installationen (Epic #1439).

### K · Verwaltungs-Spezifika

*Bestätigt: 20261031*

Barrierefreiheit als Richtlinie (BITV 2.0 / WCAG 2.1 AA) mit automatisierten Prüfungen und
abgenommenem Abschluss-Audit. **Behördenunterlagen:** redaktionelle Vorlagen für VVT, DSFA, TOM,
Löschkonzept, Informationsmappe Personalrat, Dienstvereinbarung, Sicherheitsnachweise und
Einstufungshilfe KI-Verordnung, mit Platzhaltern aus den Faktenblättern und im Update-Paket.
**Selbstauskunft** Stufe 1 als ZIP-Export eigener Daten, Auskunft über die Datenerhebung für die
Personalvertretung, Löschung der Chats nach Verwaltungssperre lokaler Konten.

**Nicht gebaut:** Textwerkzeuge einschließlich Leichter Sprache; Barrierefreiheits-Audit einer
veröffentlichten Version (#3006).

## Technisches Fundament & Arbeitsweise

*Bestätigt: 20261031*

Java 21 / Spring Boot 4.1 / Spring AI 2.0, React 19 / TypeScript 6 / MUI 9 / Vite 8,
PostgreSQL 18 mit pgvector. **Modularer Monolith ohne Paketzyklen:** logische Module und ihre
Schichtung per ArchUnit erzwungen, Architekturvertrag mit Ratschen (ADR-0051). Frontend nach
Themen gegliedert, Grenzen per ESLint geprüft. API-First: alle DTOs aus der OpenAPI-Spezifikation
im Gradle-Modul `opaa-api`, aufgeteilt in Dateien je Thema. Flyway mit einer Baseline je Modul und
Expand/Contract-Regel. 62 gepflegte ADRs. Testfundament: Unit-, Integrations- und Migrationstests,
Spring-Testkontexte mit Isolationswächtern, Teststufen für Konnektoren (ADR-0057),
Container-Suiten, Playwright-E2E gegen den echten Stack in Chromium und Firefox mit axe-Prüfung,
Offline-, Helm- und Mehrinstanz-Suiten, Struktur-Wächter. CI/CD über GitHub Actions mit gepinnten
Actions und gehärteten Workflows, Images und Chart nach GHCR, Auto-Merge; Abhängigkeits-Updates
über Renovate. Produkthandbuch unter `docs/handbuch/` als Kapitelbaum. Mensch-KI-Kollaborationsmodell
mit dokumentierten Agenten-Rollen einschließlich Behördenkontakt und Stakeholder-Review,
verbindlichen Arbeitsregeln, Projektsprache Deutsch, AGPL-3.0 mit CLA-Prozess, Ablage auf openCode
vorbereitet und täglichem Projektreport.

## Offene Phase-1-Arbeit

Die priorisierte Restliste gegen die Phase-1-Definition der Vision steht im
[Zeitraumsbericht 20261031, Abschnitt 12](./20261031/report.md#12--offen-für-phase-1--priorisierte-restliste);
die bewussten Schnitte und Zurückstellungen in
[Abschnitt 11](./20261031/report.md#11--lücken-und-bewusste-schnitte).
