# Modul connectors

Pakete (`io.opaa.indexing.source.*`): confluence, dropbox, filesystem, googledrive, nextcloud, rss,
s3, sharepoint, smb, upload, web. Der Vertrag liegt in `indexing.source` (Modul knowledge).

## Zweck und Grenze

Ein Konnektor holt die Elemente einer Quellart und übergibt sie dem Kern; Lauf, Protokoll und
Aufnahme eines Dokuments gehören dem Kern (`indexing.job`, `indexing.document`). Handbuch:
`docs/handbuch/konnektoren/README.md` und `docs/handbuch/konnektoren/*.md`.

## Invarianten und Stolpersteine

- **Selbstregistrierung:** Jede Quellart ist eine `SourceConnector`-Bean im eigenen Unterpaket, registriert durch
  dessen `@Configuration`, aufgelöst über `SourceConnectorRegistry`; der `SourceConnectorDescriptor` beantwortet,
  worauf die Verwaltung sonst verzweigen würde. Optionale Fähigkeiten sind weitere Schnittstellen derselben Bean
  (`SourceBrowser`, `OriginalAccess`, `PushIntakeHandler`); laufbasiert zusätzlich ein `SourceIndexingExecutor`.
- **Typ, Einstellungen, Geheimnisse** nach ADR-0038, Entscheidungen 1–3: offener Typ-Schlüssel (`A-Z0-9_`, bis 20
  Zeichen), Konnektoreigenes als Record in `source_settings` (`ConnectorData`), Geheimnisse nie dort; Antwort Ja/Nein.
- **Was ein neuer Konnektor überschreibt:** `descriptor`, `validate`, `testConnection`, mit Einstellungen
  `readSettings` und `settingsKeys`. `validateChange` prüft per Default über `validate`/`readSettings`;
  `configureNew`/`applyChange` speichern vorhandene Einstellungen; überschreiben nur bei eigener Regel (S3,
  Confluence). Feste API-Adresse: `ConnectorChecks.validateAtFixedAddress`, `requireFixedAddress`, `fixedSourceUrl`.
- **Ziel, Geheimnis und Einstellungen kommen nur vom Kern:** im Lauf über `IndexingRun#settings()` (ohne Geheimnis)
  und `#credentials()`, vor jeder Anfrage bzw. Anmeldung gefragt (`RunCredentials`; `FileSync` vor jedem
  Store-Zugriff; nach `401` einmal neu, `renewableAfterRejection`, sonst `SourceCredentialsRejectedException` - nur
  für das Geheimnis selbst, nie für `403` oder ein Einzelobjekt); sonst im Aufruf. Kein Konnektor hält oder erzeugt
  einen `SourceConnectionResolver` oder liest aus `KnowledgeLibrary` mehr als `getSourcePath`/`getWebhookSecret`.
- **Dienstkonto-Schlüssel signiert der Kern**, auch den des Zugangs (`ServiceAccountTokens`, `assertionSubject`).
- **Jedes Ziel von Zugangsdaten leitet sich aus `sourceUrl` ab** (bei einem Zugang aus dessen Server-Adresse), enger
  gebunden nur über `credentialBinding`; ändert sich Ursprung oder Bindung, verwirft der Kern sie. Profilangabe:
  `ProfileDeclaration`, „verboten“ genau ohne entferntes Ziel oder bei Uploads.
- **Änderungen nur über `SourceChangeGate`**, auch Profiländerung, Zuordnen und Lösen; `stored` = effektiv,
  `applyChange` = eigener Teil. `validateChange` hängt nur von seinen Argumenten ab; Ziel nicht erreichbar oder
  Anmeldung abgelehnt lehnt er mit `SourceTargetRefusedException` ab.
- **Der Laufrahmen ist `IndexingRunTemplate`:** Der Körper zählt nur auf und meldet einen `ListingOutcome`;
  Fortschritt, Protokoll und Abgleich durch Abwesenheit besitzt der Rahmen. Gelöscht wird nur über
  `IndexingRun#removeWithAttachments`, `SourceSyncState` nur über `IndexingRun#adopt` (Fingerabdruck `SyncStateBasis`).
- **Dateiablagen** implementieren `FileStore`/`ChangeFeed`; Abgleich über Läufe besitzt `FileSync` (ADR-0045).
- **Push-Adapter** (`confluence.webhook`, `s3.events`) über ihrem Konnektor, eigener Port.
- **Netzzugriff:** HTTP (auch WebDAV) über `io.opaa.sourceaccess`, S3 über `io.opaa.s3`, Graph über
  `io.opaa.msgraph`, SMB nur über `smb.SmbShareClient`. Testdoppel: `S3TestFixture`, `FakeNextcloudServer`,
  `FakeGraphServer`, `SambaFixture`, `FakeDropboxServer`.

## Maßgebliche ADRs

- [ADR-0017](../../../../../../../../docs/decisions/0017-quellentypmodell-indizierung.md) (Entscheidungen 2, 3, 5): Registrierung, Löschung je Betriebsart
- [ADR-0018](../../../../../../../../docs/decisions/0018-quellkonfiguration-in-der-bibliothek.md) (Entscheidung 6): Pfad-Allowlist des Dateisystem-Konnektors
- [ADR-0038](../../../../../../../../docs/decisions/0038-steckbare-konnektoren.md): steckbare Konnektoren, Einstellungen, Geheimnisse
- [ADR-0041](../../../../../../../../docs/decisions/0041-verbindungen-als-eigenes-modul.md) (Entscheidung 3a): Lauf-SPI ohne eigene Zugangsdaten
- [ADR-0045](../../../../../../../../docs/decisions/0045-gemeinsamer-datei-abgleich.md): Datei-Abgleich, Laufrahmen, Nextcloud-Store
- [ADR-0023](../../../../../../../../docs/decisions/0023-confluence-konnektor.md), [ADR-0027](../../../../../../../../docs/decisions/0027-s3-konnektor.md), [ADR-0040](../../../../../../../../docs/decisions/0040-google-drive-konnektor.md), [ADR-0046](../../../../../../../../docs/decisions/0046-smb-konnektor.md), [ADR-0047](../../../../../../../../docs/decisions/0047-sharepoint-konnektor.md): je ein Konnektor (Confluence, S3, Google Drive, SMB, SharePoint)

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.indexing.source.*' --tests 'io.opaa.architecture.*'
OPAA_CONFLUENCE_IT=true ./gradlew confluenceIntegrationTest   # nur bei Confluence-Änderungen
./gradlew nextcloudIntegrationTest   # bei Nextcloud- oder filesync-Änderungen, braucht Docker
```
