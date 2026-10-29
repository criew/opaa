# Modul app

Pakete (`io.opaa.*`): api, config. Dazu `OpaaApplication` im Wurzelpaket. Ergänzt
`backend/AGENTS.md`; die Regeln zu Spezifikation, DTOs und Statuscodes stehen dort und in der
Wurzel-`AGENTS.md`.

## Zweck und Grenze

Die oberste Schicht: die Endpunkte und Web-Hilfen, die jedes Modul teilt (Fehlerbehandlung,
Request-Logging, HTTP-Client, `HealthController`), und die Startwächter der Konfiguration. Die
Controller und Mapper der Fachmodule liegen in deren `web`-Paketen („Web-Schicht je Modul“).

## Invarianten und Stolpersteine

- **Konnektoren nur über `SourceConnectorRegistry`** und die Fähigkeiten, die sie ausgibt; kein
  Controller nennt eine Konnektorklasse.
- **In `io.opaa.api` gehört eine Klasse nur, wenn jedes Modul sie teilt;** sie wird dann in
  `ModularArchitecture.SHARED_API_CLASSES` eingetragen. Fachliche Controller, Mapper und Hilfen
  gehören in das `web`-Paket ihres Moduls, eine Hilfe mehrerer Module in das tiefste, das alle
  erreichen (`audit.web.AuditedAdminCall`).
- **`GlobalExceptionHandler` hat keinen Selektor** und gilt damit für die Controller aller
  `web`-Pakete.
- **Statuscodes:** Eine Operation deklariert nur, was sie selbst entscheidet;
  `TransportStatusCodeSpecificationTest` hält das maschinell fest (Regel in `backend/AGENTS.md`).
- **Startwächter in `config`:** `DatabaseSchemaGuard` verlangt für `opaa.database.schema` einen
  schlichten Bezeichner in Kleinbuchstaben und prüft vor Datenquelle und Flyway.
  `PgVectorVersionGuard` verweigert den Start bei pgvector vor 0.8.0, `VectorIndexDistanceGuard` bei einer
  Distanzfunktion, die nicht zum Vektorindex passt; ein Modellwechsel nicht (`indexing.chunk.EmbeddingIndexReconciler`).
  `OpenAiBaseUrlGuard` (nur `dev`): keine leere Basis-URL, keine mit Zugangsdaten; `ModelAllowlistStartupWarning` warnt.
  `DatabasePrerequisiteCheck` läuft vor jeder `Flyway`-Bean und bricht ab, wenn pgvector auf
  dem Server nicht installiert ist; den Bericht schreibt `DatabasePrerequisiteFailureAnalyzer`
  (registriert in `META-INF/spring.factories`). Eine Fehlerkette auszuwerten genügt dafür nicht:
  SQLState 0A000 lässt den Pool die Verbindung schließen, und die Migration meldet danach nur die
  geschlossene Verbindung. Fehlende Rechte (42501) kommen dagegen lesbar an und bleiben der
  Migration überlassen, sonst würden verwaltete Dienste mit eigener Verwaltungsrolle abgewiesen.
- **`TrustStoreBuilder`** ist ein Hauptprogramm ohne Spring, das der Init-Container des Helm-Charts
  über `PropertiesLauncher` startet (`extraCACertificates`): Laufzeit-CAs plus alle PEM-Zertifikate
  in einen frischen Truststore.
- **Reihenfolge für einen neuen Endpunkt:** OpenAPI-Fragment, generierte DTOs, Enum-Mappings,
  Frontend-Typen, API-Funktion, MSW-Handler (`agents/roles/developer.md`, „Repository-Praxis").

## Maßgebliche ADRs

- [ADR-0002](../../../../../../../docs/decisions/0002-mvp-technology-stack.md) (Entscheidungen 3, 5): pgvector-Mindestversion, OpenAI-kompatible Basis-URL
- [ADR-0006](../../../../../../../docs/decisions/0006-openapi-dto-generation.md): OpenAPI-Spezifikation, generierte DTOs
- [ADR-0034](../../../../../../../docs/decisions/0034-liquibase-changeset-stil-und-schema.md) (Entscheidung 2): konfigurierbares Zielschema

## Verweise

- Handbuch: `docs/handbuch/betrieb/konfiguration.md`, `docs/handbuch/betrieb/datenbank.md`

## Tests bei Änderungen

```bash
./gradlew test -PtestShard=api             # io.opaa.api.* und die Fachpakete des API-Shards
./gradlew test --tests 'io.opaa.config.*' --tests 'io.opaa.architecture.*'
```

Für einen einzelnen Controller genügen die berührten Testklassen in seinem `web`-Paket und die des
Fachpakets dahinter.
