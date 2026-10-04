# Modul connectors

Pakete (`io.opaa.indexing.source.*`): confluence, filesystem, googledrive, nextcloud, rss, s3,
smb, upload, web — jedes direkte Unterpaket ist ein Konnektor, der Vertrag in `indexing.source`.

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
  `IndexingRun#settings()` (ohne Geheimnis) und `#credentials()`, vor jeder Anfrage bzw. Anmeldung
  gefragt (`RunCredentials`; `FileSync` vor jedem Store-Zugriff; nach `401` einmal neu, `renewableAfterRejection`,
  sonst `SourceCredentialsRejectedException` - nur für das Geheimnis selbst, nie für `403` oder ein Einzelobjekt); sonst im Aufruf. Kein Konnektor hält oder erzeugt einen `SourceConnectionResolver` oder liest aus `KnowledgeLibrary` mehr als `getSourcePath`/`getWebhookSecret`.
- **Dienstkonto-Schlüssel signiert der Kern** (ADR-0040): Der Konnektor bekommt nur das Token aus
  `ServiceAccountTokens` und meldet imitiertes Konto und feste Adresse (`assertionSubject`).
- **Geheimnisse stehen nie in `source_settings`** (unverschlüsselt, in Antworten), nur verschlüsselt
  in `source_credentials`, `source_webhook_secret`, am Zugang, im Token-Speicher; Antworten Ja/Nein.
- **Jedes Ziel von Zugangsdaten leitet sich aus `sourceUrl` ab**, bei einem Zugang aus dessen
  Server-Adresse; was sie enger bindet (Freigabe, imitiertes Konto), meldet nur `credentialBinding`.
  Ändert sich Ursprung oder Bindung, verwirft der Kern sie. Profilangabe: `ProfileDeclaration`.
- **Änderungen nur über `SourceChangeGate`**, auch Profiländerung, Zuordnen und Lösen; `stored` =
  effektiv, `applyChange` = eigener Teil. `validateChange` hängt nur von seinen Argumenten ab.
- **Der Laufrahmen ist `IndexingRunTemplate`:** Der Körper zählt nur die Quelle auf und meldet
  einen `ListingOutcome`; Fortschritt, Protokoll und Abgleich durch Abwesenheit besitzt der Rahmen.
- **Dateiablagen** implementieren `FileStore` (mit Änderungsprotokoll auch `ChangeFeed`) aus
  `indexing.filesync`; Abgleich und Änderungslauf besitzt `FileSync`. Tests: `FileStoreContract`.
- **Push-Adapter** (`confluence.webhook`, `s3.events`) über ihrem Konnektor, eigener Port.
- **Netzzugriff:** HTTP (auch WebDAV) über `io.opaa.sourceaccess`, S3 über `io.opaa.s3`, SMB nur
  über `smb.SmbShareClient`. Testdoppel: `S3TestFixture`, `FakeNextcloudServer`, `SambaFixture`.

## Verweise

- ADRs (`docs/decisions/`): 0017, 0018, 0023, 0027, 0038, 0040, 0041 (Entscheidung 3a: Lauf-SPI)
- Handbuch: `docs/handbuch/indexierung.md`, Abschnitt 4; `docs/handbuch/konnektor-*.md`
- Strukturtests: `ModularArchitectureTest` (`connectorsDoNotKnowEachOther`,
  `noOneOutsideAConnectorKnowsIt`, `connectorsTakeTheirSourceConfigurationFromTheCore`)

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.indexing.source.*' --tests 'io.opaa.architecture.*'
OPAA_CONFLUENCE_IT=true ./gradlew confluenceIntegrationTest   # nur bei Confluence-Änderungen
./gradlew nextcloudIntegrationTest   # bei Nextcloud- oder filesync-Änderungen, braucht Docker
```

Schemaänderungen: neue Datei unter `db/changelog/connectors/`, Regeln in `backend/AGENTS.md`.
