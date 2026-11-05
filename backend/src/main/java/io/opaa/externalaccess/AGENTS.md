# Modul external

Pakete (`io.opaa.*`): externalaccess, mcp. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Der Kanal für Fremdzugänge: Kanaleinstellungen mit dem Schalter der Installation (Vorgabe aus,
zugleich Notaus), persönliche Zugangstokens, Dienstkonten mit Bibliothekszustimmung (`externalaccess`)
und der MCP-Server mit den Werkzeugen `search`, `fetch` und `list_libraries` (`mcp`).

## Invarianten und Stolpersteine

- **`ExternalAccessSettingsService` ist der einzige Einstieg** in die Kanaleinstellungen. Er
  validiert, protokolliert jede Änderung, auch die des Anleitungstexts, und beantwortet die Abfragen
  je Aufruf. Ob eine Clientadresse zum Kanal gehört, entscheidet allein
  `ExternalAccessNetworkPolicy`.
- **Über dem Schalter steht das Feature-Flag `EXTERNAL_ACCESS`** (ADR-0044). Aus, gibt es
  Filterkette, Routen, MCP-Server (`McpServerSwitch` hält den Starter heraus), Erinnerungsmail und
  Freigabe-Nachtlauf nicht; `isEnabled()` und der Token-Authenticator sagen immer nein. Schalter
  und Daten bleiben, Lapse- und Retention-Step laufen weiter: Das Flag hält keine Löschfrist an.
- **Ein Zugangstoken ist ein opaker Zufallswert mit festem Präfix**, gespeichert nur als
  HMAC-Suchhash (eigener Zweck in `LocalAuthKeyService`), mit Pflicht-Ablaufdatum. Ein persönliches
  hat eine unveränderliche Bibliotheksauswahl; ein Dienstkonto-Token (eigene Tabelle) hat keine.
- **Ein Token folgt dem Lebenszyklus seiner Person:** Sperre, Übergabe und Ende aller Sitzungen beenden
  es in derselben Transaktion; ein Anbieterkonto braucht eine junge Anmeldung (Handbuch, Abschnitt 6).
  Dienstkonto-Tokens berührt der Personen-Lebenszyklus nie; sie enden mit dem Deaktivieren des Kontos.
- **Die wirksame Sicht wird je Aufruf berechnet, nie eingefroren** (`ExternalAccessTokenScopeService`):
  Rechte der Person ∩ Freigabe ∩ Auswahl ∩ Schalter; beim Dienstkonto Zustimmungen ∩ Schalter ∩ Token
  ∩ Konto aktiv. Die Freigabe zählt dort nicht, das Kontingent gilt je Dienstkonto (ADR-0035, E. 7).
- **Die Token-ID reist am Aufrufer** (`CurrentUser#accessTokenId`), nie über Request-Attribut oder
  Thread; ein Tokenaufrufer ohne Token-ID lässt sich nicht bauen, ein MCP-Aufruf ohne sie wird abgewiesen.
- **Kein Tokenpräfix ins Log:** Mit einem Zeitstempel ergäbe es die personenbezogene Abfragehistorie,
  die das Revisionsprotokoll ausschließt (`ExternalAccessTokenAuthenticationIntegrationTest`).
- **`mcp` ist eine Übersetzungsschicht und sonst nichts:** kein Index, keine Rechtelogik, kein
  Zugriff auf `vector_store`. Jeder Werkzeugaufruf geht über `io.opaa.search`, dieselben Dienste
  wie `POST /api/v1/search` und `POST /api/v1/query`.
- **Pakete, unten zuerst:** Wurzel (Kanaleinstellungen, Netz- und Pfadregeln), `token`,
  `security` (Filterkette und Abweisungsstil, auch von `mcp` genutzt), `web` (Einstellungen und
  Zugangstoken); ein Paket nennt nur die davor. `mcp` hat keine Controller.
- **Zustandslos:** Jede JSON-RPC-Anfrage ist eine eigene HTTP-Anfrage mit eigenem Bearer-Wert;
  Schalter, Token und Sicht werden je Aufruf geprüft.
- **Was der Spring-AI-Starter nicht tut, tut `mcp` selbst:** Die Filterkette des Kanals sichert
  den sonst unauthentifiziert registrierten Endpunkt, `tools/list` wird je Anfrage gebaut, und eine
  unbekannte Protokollversion wird abgewiesen.

## Maßgebliche ADRs

- [ADR-0035](../../../../../../../docs/decisions/0035-fremdzugaenge-mcp-server-und-zugangstokens.md): MCP-Server, Zugangstokens, Freigabe
- [ADR-0039](../../../../../../../docs/decisions/0039-ein-katalog-und-ausdrueckliche-space-zuordnung.md) (Entscheidung 5): Fremdzugang mit eigener Auswahl

## Verweise

- Handbuch: `docs/handbuch/administration/externe-ki-werkzeuge.md`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.externalaccess.*' --tests 'io.opaa.mcp.*' \
  --tests 'io.opaa.search.*' --tests 'io.opaa.architecture.*'
```
