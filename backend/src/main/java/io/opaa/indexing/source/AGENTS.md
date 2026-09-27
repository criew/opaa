# Modul connectors

Pakete: jedes direkte Unterpaket von `io.opaa.indexing.source` (confluence, filesystem, rss, s3,
upload, web). Der Vertrag selbst liegt in `indexing.source` und gehört zu knowledge (siehe
`knowledge/AGENTS.md`). Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Ein Konnektor holt die Elemente einer Quellart und übergibt sie dem Kern. Lauf, Protokoll und
Aufnahme eines Dokuments gehören dem Kern (`indexing.job`, `indexing.document`). connectors hängt nur
von foundation und knowledge ab.

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
- **Netzzugriff:** HTTP über `io.opaa.sourceaccess`, S3 über `io.opaa.s3`.
- **Objektspeicher-Suite des S3-Konnektors:** läuft in `test`, sobald Docker erreichbar ist, auf
  **einem** geteilten Speicher je Test-JVM (`S3TestFixture`, Image `rustfs/rustfs`) und im
  `@OpaaIntegrationTest`-Kontext. Eingeschränkte Schlüssel legt `S3TestFixture.createUser(policyJson)`
  über die MinIO-kompatible Admin-API an. Wer das Image wechselt, prüft zuerst diese Aufrufe sowie
  „darf auflisten, aber nicht lesen" und die gefilterte Bucket-Liste.

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

Bei Schemaänderungen zusätzlich `ConnectorsBaselineTest`.
