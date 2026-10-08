# Modul app

Pakete (`io.opaa.*`): api, config. Dazu `OpaaApplication` im Wurzelpaket. Ergänzt
`backend/AGENTS.md`; die Regeln zu Spezifikation, DTOs und Statuscodes stehen dort und in der Wurzel-`AGENTS.md`.

## Zweck und Grenze

Die oberste Schicht: die Endpunkte und Web-Hilfen, die jedes Modul teilt (Fehlerbehandlung,
Request-Logging, HTTP-Client, `HealthController`), und die Startwächter der Konfiguration. app nutzt
foundation, identity, rights, knowledge und library, nie connectors. Die Controller und Mapper der
Fachmodule liegen in deren `web`-Paketen (`backend/AGENTS.md`, „Web-Schicht je Modul“).

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
  schlichten Bezeichner in Kleinbuchstaben und prüft vor Datenquelle und Liquibase.
  `PgVectorDimensionsGuard` verweigert den Start, wenn die Spalte `embedding` eine andere Dimension
  hat als konfiguriert, `PgVectorVersionGuard` bei pgvector älter als 0.8.0. `OpenAiBaseUrlGuard` verweigert eine leere Basis-URL oder eine mit
  Zugangsdaten.
- **Reihenfolge für einen neuen Endpunkt:** OpenAPI-Fragment, generierte DTOs, Enum-Mappings,
  Frontend-Typen, API-Funktion, MSW-Handler (`agents/roles/developer.md`, „Repository-Praxis").

## Verweise

- ADRs (`docs/decisions/`): 0005, 0006
- Handbuch: `docs/handbuch/deployment.md`, „Konfiguration" und „Datenbank"
- Strukturtests: `TransportStatusCodeSpecificationTest`, `DatabaseSchemaGuardTest`,
  `OpenAiBaseUrlGuardTest`, `ModularArchitectureTest`

## Tests bei Änderungen

```bash
./gradlew test -PtestShard=api             # io.opaa.api.* und die Fachpakete des API-Shards
./gradlew test --tests 'io.opaa.config.*' --tests 'io.opaa.architecture.*'
```

Für einen einzelnen Controller genügen die berührten Testklassen in seinem `web`-Paket und die des
Fachpakets dahinter.
