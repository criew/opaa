# Modul connectors

Pakete (`io.opaa.indexing.source.*`): confluence, filesystem, rss, s3, upload, web — jedes direkte
Unterpaket ist ein Konnektor. Der Vertrag selbst liegt in `indexing.source` und gehört zu knowledge (siehe
`knowledge/AGENTS.md`). Ergänzt `backend/AGENTS.md`.

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
- **Der Typ ist ein offener Schlüssel** (Großbuchstaben, Ziffern, Unterstrich, höchstens 20
  Zeichen). Welche es gibt, weiß nur die Registry; eine Schlüssel-Konstante liegt im Konnektor.
- **Einstellungen:** Alles Konnektoreigene ist ein Record im Konnektorpaket und steht als ein
  JSON-Objekt in `source_settings`; der Kern reicht es als `ConnectorData` durch.
- **Geheimnisse stehen nie in `source_settings`** — die Spalte ist unverschlüsselt und erscheint in
  Antworten. Plätze für Geheimnisse sind nur `source_credentials` und `source_webhook_secret`,
  beide verschlüsselt. Antworten tragen nur Ja/Nein, das Audit nur Feldnamen.
- **Jedes Ziel, an das Zugangsdaten gehen, leitet sich aus `sourceUrl` ab.** Die Ursprungsbindung
  (`SourceOriginMatcher`) verwirft Zugangsdaten, sobald sich der Ursprung ändert. Steht ein Ziel nur
  in `source_settings`, verlangt der Konnektor bei dessen Änderung selbst neue Zugangsdaten.
- **Der Laufrahmen ist `IndexingRunTemplate`:** Der Körper zählt nur die Quelle auf, gibt jedes
  Element über `IndexingRun` weiter und meldet einen `ListingOutcome`. Fortschritt, Protokoll,
  Fehlerübersetzung und Abgleich durch Abwesenheit besitzt der Rahmen.
- **Push-Adapter** (`confluence.webhook`, `s3.events`) liegen über ihrem Konnektor; der Konnektor
  erreicht sie über einen eigenen Port (`ConfluencePushReceiver`, `S3PushReceiver`).
- **Netzzugriff:** HTTP über `io.opaa.sourceaccess`, S3 über `io.opaa.s3`.
- **Die S3-Tests** nutzen die geteilte `S3TestFixture` (foundation, siehe `backend/AGENTS.md`,
  „Spring-Testkontexte"); ohne Docker werden sie übersprungen.

## Verweise

- ADRs (`docs/decisions/`): 0017, 0018, 0023, 0027, 0038
- Handbuch: `docs/handbuch/indexierung.md`, Abschnitt 4; `docs/handbuch/konnektor-*.md`
- Strukturtests: `ModularArchitectureTest` (`connectorsDoNotKnowEachOther`,
  `noOneOutsideAConnectorKnowsIt`)

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.indexing.source.*' --tests 'io.opaa.architecture.*'
OPAA_CONFLUENCE_IT=true ./gradlew confluenceIntegrationTest   # nur bei Confluence-Änderungen
```

Bei Schemaänderungen: neue Datei unter `db/changelog/connectors/`. Regeln und
Tests in `backend/AGENTS.md`, „Liquibase: Changelog je Modul“ — ein eigener Delta-Test ist nur
für rein additive DDL entbehrlich.
