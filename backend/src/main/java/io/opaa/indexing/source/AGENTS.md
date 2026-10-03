# Modul connectors

Pakete (`io.opaa.indexing.source.*`): confluence, filesystem, rss, s3, upload, web — jedes direkte
Unterpaket ist ein Konnektor. Der Vertrag liegt in `indexing.source` (knowledge). Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Ein Konnektor holt die Elemente einer Quellart und übergibt sie dem Kern. Lauf, Protokoll und
Aufnahme eines Dokuments gehören dem Kern (`indexing.job`, `indexing.document`). connectors hängt nur
von foundation, format und knowledge ab.

## Invarianten und Stolpersteine

- **Selbstregistrierung:** Jede Quellart ist eine `SourceConnector`-Bean im eigenen Unterpaket,
  registriert durch dessen `@Configuration`, aufgelöst über `SourceConnectorRegistry`. Die
  `SourceConnectorDescriptor` beantwortet, worauf die Verwaltung sonst verzweigen würde. Optionale
  Fähigkeiten sind weitere Schnittstellen derselben Bean (`SourceBrowser`, `OriginalAccess`,
  `PushIntakeHandler`); eine laufbasierte Art registriert zusätzlich einen `SourceIndexingExecutor`.
- **Kein Konnektor kennt einen anderen, und nichts außerhalb kennt einen Konnektor.** Kern,
  Verwaltung und API erreichen ihn nur über die Registry.
- **Der Typ ist ein offener Schlüssel** (`A-Z0-9_`, höchstens 20 Zeichen); nur die Registry kennt alle.
- **Einstellungen:** Alles Konnektoreigene ist ein Record im Konnektorpaket und steht als ein
  JSON-Objekt in `source_settings`; der Kern reicht es als `ConnectorData` durch.
- **Ziel, Geheimnis und Einstellungen kommen nur vom Kern** (ADR-0041, 3a): im Lauf über
  `IndexingRun#settings()` (ohne Geheimnis) und `#currentCredentials()`, das bei jedem Aufruf das
  jetzt gültige Geheimnis vom Port holt; sonst im Aufruf. Kein Konnektor hält oder erzeugt einen
  `SourceConnectionResolver` oder liest aus `KnowledgeLibrary` mehr als `getSourcePath`/`getWebhookSecret`.
- **Dienstkonto-Schlüssel signiert der Kern** (ADR-0040): Der Konnektor bekommt nur das Token aus
  `ServiceAccountTokens` und meldet imitiertes Konto und feste Adresse (`assertionSubject`).
- **Geheimnisse stehen nie in `source_settings`** — die Spalte ist unverschlüsselt und erscheint in
  Antworten. Plätze für Geheimnisse sind nur `source_credentials` und `source_webhook_secret`,
  beide verschlüsselt. Antworten tragen nur Ja/Nein, das Audit nur Feldnamen.
- **Jedes Ziel, an das Zugangsdaten gehen, leitet sich aus `sourceUrl` ab.** Die Ursprungsbindung
  (`SourceOriginMatcher`) verwirft Zugangsdaten, sobald sich der Ursprung ändert. Steht ein Ziel nur
  in `source_settings`, verlangt der Konnektor bei dessen Änderung selbst neue Zugangsdaten.
- **Der Laufrahmen ist `IndexingRunTemplate`:** Der Körper zählt nur die Quelle auf und meldet
  einen `ListingOutcome`; Fortschritt, Protokoll und Abgleich durch Abwesenheit besitzt der Rahmen.
- **Dateiablagen** implementieren `FileStore` (mit Änderungsprotokoll auch `ChangeFeed`) aus
  `indexing.filesync`; Abgleich und Änderungslauf besitzt `FileSync`. Tests: `FileStoreContract`.
- **Push-Adapter** (`confluence.webhook`, `s3.events`) liegen über ihrem Konnektor, erreicht über
  einen eigenen Port (`ConfluencePushReceiver`, `S3PushReceiver`).
- **Netzzugriff:** HTTP über `io.opaa.sourceaccess`, S3 über `io.opaa.s3`. Die S3-Tests nutzen
  die geteilte `S3TestFixture` (foundation); ohne Docker übersprungen.

## Verweise

- ADRs (`docs/decisions/`): 0017, 0018, 0023, 0027, 0038, 0040, 0041 (Entscheidung 3a: Lauf-SPI)
- Handbuch: `docs/handbuch/indexierung.md`, Abschnitt 4; `docs/handbuch/konnektor-*.md`
- Strukturtests: `ModularArchitectureTest` (`connectorsDoNotKnowEachOther`,
  `noOneOutsideAConnectorKnowsIt`, `connectorsTakeTheirSourceConfigurationFromTheCore`)

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.indexing.source.*' --tests 'io.opaa.architecture.*'
OPAA_CONFLUENCE_IT=true ./gradlew confluenceIntegrationTest   # nur bei Confluence-Änderungen
```

Bei Schemaänderungen: neue Datei unter `db/changelog/connectors/`, Regeln in `backend/AGENTS.md`,
„Liquibase: Changelog je Modul“ (Delta-Test nur bei rein additiver DDL entbehrlich).
