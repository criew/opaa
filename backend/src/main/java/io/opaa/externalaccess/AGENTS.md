# Modul external

Pakete (`io.opaa.*`): externalaccess, mcp. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Der Kanal für Fremdzugänge: Kanaleinstellungen mit dem Schalter der Installation (Vorgabe aus,
zugleich Notaus), persönliche Zugangstokens (`externalaccess`) und der MCP-Server mit den Werkzeugen
`search`, `fetch` und `list_libraries` (`mcp`). external hängt von allen Modulen außer workspace,
connectors und app ab.

## Invarianten und Stolpersteine

- **`ExternalAccessSettingsService` ist der einzige Einstieg** in die Kanaleinstellungen. Er
  validiert, protokolliert jede Änderung außer dem Anleitungstext und beantwortet die Abfragen je
  Aufruf. Ob eine Clientadresse zum Kanal gehört, entscheidet allein
  `ExternalAccessNetworkPolicy`.
- **Ein Zugangstoken ist ein opaker Zufallswert mit festem Präfix**, gespeichert nur als
  HMAC-Suchhash (eigener Zweck in `LocalAuthKeyService`). Es ist an eine Person, eine
  unveränderliche Auswahl von Bibliotheken und ein Pflicht-Ablaufdatum gebunden.
- **Die wirksame Sicht wird je Aufruf berechnet, nie im Token eingefroren:** Rechte der Person,
  Freigabe der Bibliothek, Auswahl des Tokens, Schalter der Installation
  (`ExternalAccessTokenScopeService`).
- **Kein Tokenpräfix ins Log:** Mit einem Zeitstempel ergäbe es die personenbezogene
  Abfragehistorie, die das Revisionsprotokoll bewusst ausschließt
  (`ExternalAccessTokenAuthenticationIntegrationTest#thePrefixAppearsInNoLogLineOfTheApplication`).
- **`mcp` ist eine Übersetzungsschicht und sonst nichts:** kein Index, keine Rechtelogik, kein
  Zugriff auf `vector_store`. Jeder Werkzeugaufruf geht über `io.opaa.search`, dieselben Dienste
  wie `POST /api/v1/search` und `POST /api/v1/query`.
- **Web-Schicht:** `externalaccess.web` (Einstellungen und Zugangstoken); `mcp` hat keine
  Controller.
- **Zustandslos:** Jede JSON-RPC-Anfrage ist eine eigene HTTP-Anfrage mit eigenem Bearer-Wert;
  Schalter, Token und Sicht werden je Aufruf geprüft.
- **Was der Spring-AI-Starter nicht tut, tut `mcp` selbst:** Die Filterkette des Kanals sichert
  den sonst unauthentifiziert registrierten Endpunkt, `tools/list` wird je Anfrage gebaut, und eine
  unbekannte Protokollversion wird abgewiesen.

## Verweise

- ADR (`docs/decisions/`): 0035
- Handbuch: `docs/handbuch/fremdzugaenge.md`
- Strukturtests: `McpDependencyStructureTest`, `SearchDependencyStructureTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.externalaccess.*' --tests 'io.opaa.mcp.*' \
  --tests 'io.opaa.search.*' --tests 'io.opaa.architecture.*'
```

Bei Schemaänderungen: neue Datei unter `db/changelog/external/` mit eigenem Delta-Test
(`MasterChangelog.filesExcept(...)`), Regeln in `backend/AGENTS.md`, „Liquibase: Changelog je
Modul“; dazu `ChangelogLayoutTest`, `ChangelogModuleBoundaryTest`, `ChangelogOrderTest`.
