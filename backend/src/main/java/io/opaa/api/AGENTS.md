# Modul app

Pakete (`io.opaa.*`): api, config. Dazu `OpaaApplication` im Wurzelpaket. Ergänzt
`backend/AGENTS.md`; die Regeln zu Spezifikation, DTOs und Statuscodes stehen dort und in der Wurzel-`AGENTS.md`.

## Zweck und Grenze

Die oberste Schicht: REST-Controller, die Abbildung zwischen Domäne und generierten DTOs und die
Startwächter der Konfiguration. app darf jedes Modul nutzen außer connectors.

## Invarianten und Stolpersteine

- **Konnektoren nur über `SourceConnectorRegistry`** und die Fähigkeiten, die sie ausgibt; kein
  Controller nennt eine Konnektorklasse.
- **Mapper:** Die Abbildung Entity → Response lebt in einer package-private, handgeschriebenen
  Mapper-Klasse im Paket des Controllers (Vorbild `BrandingResponseMapper`,
  `SpaceResponseMapper`). Domain-Services sehen keine `io.opaa.api.dto`-Typen.
- **Werden Test-Assertions von Response-Feldern auf Entity-Ableitungen umgestellt**, sichert ein
  Mapper-Unit-Test die Feldbelegung (`SpaceResponseMapperTest`,
  `SpaceAssetAssociationResponseMapperTest`). Sonst prüft kein Test mehr, dass der Mapper jedes
  Feld befüllt.
- **Statuscodes:** Eine Operation deklariert nur, was sie selbst entscheidet;
  `TransportStatusCodeSpecificationTest` hält das maschinell fest (Regel in `backend/AGENTS.md`).
- **Startwächter in `config`:** `DatabaseSchemaGuard` verlangt für `opaa.database.schema` einen
  schlichten Bezeichner in Kleinbuchstaben und prüft vor Datenquelle und Liquibase.
  `PgVectorDimensionsGuard` verweigert den Start, wenn die Spalte `embedding` eine andere Dimension
  hat als konfiguriert. `OpenAiBaseUrlGuard` verweigert eine leere Basis-URL oder eine mit
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

Für einen einzelnen Controller genügen die berührten Testklassen in `io.opaa.api` und die des
Fachpakets dahinter.
