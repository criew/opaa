# ADR-0038: Steckbare Konnektoren — offener Typ-Schlüssel, Einstellungen je Konnektor, Geheimnisse außerhalb der Einstellungen

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ergänzt [ADR-0006](0006-openapi-dto-generation.md) · ergänzt
durch [ADR-0040](0040-google-drive-konnektor.md), [ADR-0041](0041-verbindungen-als-eigenes-modul.md) und [ADR-0045](0045-gemeinsamer-datei-abgleich.md) bis [ADR-0047](0047-sharepoint-konnektor.md)
· ersetzt Entscheidung 1 von [ADR-0017](0017-quellentypmodell-indizierung.md), den Nachtrag #1131 von
[ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md), die Entscheidungen 1, 2 und 4 von
[ADR-0023](0023-confluence-konnektor.md) und Entscheidung 1 von [ADR-0027](0027-s3-konnektor.md)

## Kontext

Die Verwaltung erreichte jeden Konnektor über die Konnektor-SPI (`SourceConnector`,
`SourceConnectorRegistry`). Ein neuer Konnektor brauchte trotzdem Änderungen an drei Stellen außerhalb
seines Pakets: am geschlossenen Enum `DocumentSourceType` in `opaa-api`, an flachen Quellfeldern der API
(`confluenceEdition`, `confluenceSpaces`, `s3Settings`) und am Schema (eigene Confluence-Spalten und
Kindtabelle, `CHECK`-Constraints je Typ). Beschluss des Maintainers vom 26.09.2026: Ein neuer Konnektor
soll ohne Änderung an OpenAPI-Spezifikation, Datenbankschema und Verwaltung ergänzt werden können.

## Entscheidung

### 1. Der Typ ist ein offener Schlüssel, die Registry die einzige Liste

Die Quellart ist ein String aus Großbuchstaben, Ziffern und Unterstrich, höchstens 20 Zeichen
(`chk_knowledge_libraries_source_type`, `io.opaa.knowledge.SourceType.MAX_LENGTH`). Welche Schlüssel es gibt, weiß nur die `SourceConnectorRegistry`: Jeder
Konnektor meldet seinen Schlüssel in seiner Beschreibung (`SourceConnectorDescriptor`), die Datenbank
prüft nur die Form. Die Beschreibung nennt `indexingRun`, `remote`, `deepLink`, `uploads`,
`pushIntake`, `fullSyncInterval` und `profileDeclaration`; `GET /api/v1/source-types` liefert je
Konnektor Schlüssel, Anzeigename und diese Fähigkeiten. Das Enum `DocumentSourceType` und das engere Lauf-Enum aus
ADR-0017 sind entfallen; der Kern kennt nur noch `UPLOAD` als Sonderfall.

Konnektoren je in einem Unterpaket von `io.opaa.indexing.source`, ihr Vertrag in `io.opaa.indexing.spi`
(Handbuch: `docs/handbuch/konnektoren/`):

| Schlüssel | Paket | seit |
|---|---|---|
| `UPLOAD` | `upload` | Kern |
| `FILESYSTEM` | `filesystem` | ADR-0017 |
| `HTTP_DIRECTORY` | `web` | ADR-0017 |
| `RSS_FEED` | `rss` | ADR-0017 |
| `CONFLUENCE` | `confluence` | ADR-0023 |
| `S3` | `s3` | ADR-0027 |
| `NEXTCLOUD` | `nextcloud` | ADR-0045 (Entscheidung 11) |
| `GOOGLE_DRIVE` | `googledrive` | ADR-0040 |
| `SMB` | `smb` | ADR-0046 |
| `SHAREPOINT` | `sharepoint` | ADR-0047 |
| `DROPBOX` | `dropbox` | #2154 |

### 2. Einstellungen je Konnektor als ein JSON-Objekt in `source_settings`

- Allgemeine Verbindungsfelder bleiben Spalten (`source_path`, `source_url`, `source_credentials`),
  weil an ihnen Regeln des Kerns hängen: Ursprungsbindung der Zugangsdaten (`SourceOriginMatcher`),
  Verschlüsselung, Zieladressprüfung (`TargetAddressValidator`), Pfad-Allowlist. `source_proxy` und
  `source_insecure_ssl` sind entfallen (#2496, #2541): Alle Verbindungen gehen über den hausweiten
  Ausgangsproxy, die Zertifikatsprüfung ist nicht aussetzbar.
- Alles Konnektoreigene steht als ein Objekt in `source_settings` (jsonb, Objekt oder `NULL`;
  `UPLOAD` ohne Quellkonfiguration). Form, Validierung, Normalisierung und Lesersicht definiert der
  Konnektor mit einem Record in seinem Paket; der Kern reicht das Objekt als `ConnectorData` durch.
  Keine konnektoreigenen Tabellen: Laufzustand je Element lebt im Synchronisationszustand
  (`SourceSyncState`), nicht in der Konfiguration.

### 3. Geheimnisse stehen nie in den Einstellungen

`source_settings` ist unverschlüsselt und erscheint in Antworten. Geheimnisse haben vier Plätze, alle
über `CredentialsEncryptor` mit demselben Schlüssel und derselben Leseregel (fehlt der Schlüssel, liest
sich der Wert als nicht gesetzt, der Geheimtext bleibt):

- `source_credentials` der Bibliothek (Format bestimmt der Konnektor; bei einer Bibliothek mit Profil
  leer),
- `source_webhook_secret` (der Kern erzeugt, rotiert, löscht es und zeigt es genau einmal),
- das Client-Secret am Verbindungsprofil und der Token-Speicher (ADR-0041).

Antworten tragen nur „gesetzt / nicht gesetzt", das Audit nur Feldnamen. **Invariante: Jedes Ziel, an
das Zugangsdaten gehen, leitet sich aus `sourceUrl` ab** — bei einer Bibliothek mit Profil aus der
Server-Adresse des Profils, gegen die `SourceOriginMatcher` und `TargetAddressValidator` prüfen. Einzige
Ausnahme ist der Token-Endpunkt aus der Beschreibung, an den Refresh-Token, Client-Secret und die
signierte Dienstkonto-Assertion gehen. Ändert sich der Ursprung, werden die Geheimnisse verworfen; ändert
sich der Mandant, ist eine neue Zustimmung nötig. Ein Konnektor führt Host oder Mandant nie allein in
`source_settings`. Ein zweiter fester Host (Dropbox: Inhalts-Host neben dem API-Host) steht in der
Betreiberkonfiguration des Konnektors (`opaa.indexing.dropbox.content-base`), nie in einer Eingabe; ein
Feld in der Adressregel käme erst, wenn ein Konnektor einen zweiten Host aus dem Zugang nähme.

Der Konnektor bekommt Ziel, Geheimnis und Einstellungen nur vom Kern als aufgelöste `SourceSettings`,
auch im Lauf und beim Originalabruf; Profil, Refresh-Token, Client-Secret, Dienstkonto-Schlüssel und
OAuth-Ablauf sieht er nie (ADR-0041, Entscheidung 3a, ArchUnit-Regel in `ModularArchitectureTest`):
Resolver und Dienstkonto-Schlüssel liegen in `io.opaa.indexing.credentials`, das kein Konnektor nutzt.

### 4. Abbildung in OpenAPI: freies Objekt, `sourceType` als Diskriminator daneben

`sourceSettings` ist ein freies Objekt (`additionalProperties: true`), kein `oneOf`; der Konnektor
validiert und antwortet `400` mit deutscher Meldung. Leser sehen die Lesersicht (was die Bibliothek
abdeckt), Verwaltende zusätzlich Verwaltungswerte. Push-Eingang (`POST /api/v1/libraries/{id}/push`),
Push-Geheimnis (`…/push-secret`) und Auflistung (`POST /api/v1/source-types/{sourceType}/browse`) sind
typneutral benannt; welcher Konnektor eine Push-Nachricht liest, entscheidet der Typ der Bibliothek.
Der Verbindungstest liefert konnektoreigene Befunde im freien Objekt `details`.
Kein JSON-Schema je Konnektor: Die Formularkomponente je Typ ist handgeschrieben und maßgeblich bleibt
die Validierung im Konnektor; ein Schema ließe sich rückwärtskompatibel ergänzen. Preis: Grenzen wie
`required` und `maxLength`, Bean-Validation und generierte Frontend-Typen entfallen für
`sourceSettings`; die Form dokumentieren Handbuchkapitel und Formularkomponente.

### 5. Übergang in zwei Schritten

Abgeschlossen: zuerst die interne Umstellung bei unveränderter API (#1986), dann der API-Bruch (#1997).

### 6. Frontend-Registrierung und Fähigkeiten

- Jede Quellart registriert sich an genau einer Stelle
  (`frontend/src/features/library/components/sources/registry.ts`): Name, Symbol, Formular mit Startwerten,
  Validierung, Anfragefeldern und Leseansichten, dazu typabhängige Beschriftungen und Wege. Die Kacheln
  des Assistenten kommen aus `GET /source-types`; ein dem Frontend unbekannter Typ erscheint unter seinem
  Anzeigenamen, nicht wählbar. Nach dem Typ verzweigt das Frontend sonst nur auf `UPLOAD`.
- **Uploads sind eine eigene Fähigkeit** (`uploads`), nicht „kein Lauf"; sie entscheidet Anlegerecht,
  Upload-Annahme, Freigabe-Obergrenze und Löschsperre. Nur `UPLOAD` darf sie tragen; die Registry prüft
  das beim Start.
- **Gleiche Antwortzeit am Push-Eingang:** Jede Anfrage läuft durch die Prüfung jedes registrierten
  Push-Eingangs, alle fremden gegen einen Platzhalter (`PushIntakeHandler#rejectForeign`); unbekannte
  Bibliothek, Bibliothek ohne Push-Eingang und falsches Geheimnis sind nicht unterscheidbar.
- Die Grenze „Kern kennt keinen Konnektor beim Namen" sichert `ModularArchitectureTest`; zwei
  Test-Konnektoren im Testcode weisen nach, dass ein Konnektor ohne Spec-Änderung über die API anlegbar,
  änderbar, testbar, auflistbar, planbar und indizierbar ist.

### 7. Profilangabe und Anmeldearten in der Beschreibung (`ProfileDeclaration`)

- **Profilangabe:** verboten, optional oder Pflicht (`ConnectionProfileSupport`). Die Registry prüft
  beim Start: verboten bei `uploads` oder ohne `remote`; Pflicht genau dann, wenn OAuth oder
  Client-Credentials angeboten werden, weil die App-Registrierung nur am Profil steht (der
  Dienstkonto-Schlüssel trägt sie selbst und macht keine Profilpflicht); ein entfernter Konnektor ohne
  Uploads muss Profile zulassen.
- **Anmeldearten** als `SignIn(method, owners, details)`: persönliches Geheimnis (Token oder
  Benutzername/Passwort), OAuth (Autorisierungscode mit PKCE, Endpunkte fest, mit Mandant aus dem
  Profil oder relativ zur Server-Adresse), Client-Credentials (`ClientCredentialsAuth`, Besitzerin nur
  die Bibliothek; Token-Endpunkt `Endpoint.Fixed`, `WithTenant` mit dem Mandanten des Profils oder
  `FromProfile`, den nur das Profil auflöst) und Dienstkonto-Schlüssel (`ServiceAccountKeyAuth`,
  JWT-Assertion nach RFC 7523, ADR-0040; Token-Endpunkt und Scopes fest, nur Besitzart Bibliothek;
  ohne Profil in `source_credentials`, mit Profil am Platz des Client-Secrets; signiert wird im Kern,
  ein Zugriffstoken lebt nur im Speicher des Laufs). `owners` nennt Bibliothek, Person oder beide. Ein Profil wählt nur eine Art, die sein
  Konnektor anbietet.
- **Adressregel** `ServerAddressRule`: zulässige Schemata oder eine feste Adresse, die das Formular
  nicht abfragt.
- **Vorgaben-Schema** `ProfileDefaults`: die Schlüssel, die ein Profil setzen darf, je mit Beschriftung
  und Art; `SourceConnector#readProfileDefaults` prüft die Vorgaben, ein anderer Schlüssel ist `400`;
  jeder Schlüssel steht in `SourceConnector#settingsKeys`. Eine Vorgabe nur des Profils
  (`DefaultKey#profileOnly`) ist unter einem Profil immer gebunden. Was Ziel oder Anmeldung bestimmt
  (etwa die Confluence-Edition), steht am Profil, nicht in der Bibliothek; Vorgaben enthalten nie ein
  Geheimnis.
- `GET /source-types` meldet `signIns`, `profileDefaults` und `serverAddress`;
  `GET /connection-profiles` meldet die Vorgaben eines Profils als `connectorDefaults`.

## Konsequenzen

**Einfacher:** Ein neuer Konnektor bringt Paket, Einstellungs-Record und Formularkomponente mit;
Spezifikation, Schema und Verwaltung bleiben unverändert. Geheimnisse haben einen Verschlüsselungs- und
Verwaltungsweg.

**Schwieriger:** Die Datenbank prüft die Konfiguration nicht mehr je Typ; Pflichtfelder sichert allein
der Konnektor, seine Record-Tests sind Pflicht. Die Spezifikation beschreibt `sourceSettings` nicht im
Einzelnen. Eine Umbenennung von Einstellungs-Schlüsseln braucht eine Datenmigration über `jsonb`, sobald
es Bestandssysteme gibt.

## Verworfene Alternativen

- `oneOf` mit einem Zweig je Konnektor — jeder neue Konnektor wäre eine Spec-Änderung.
- Bekannte Zweige plus freier Rückfallzweig — `oneOf` wäre nie eindeutig, die Spec täuschte eine Prüfung vor.
- Verschlüsselte Geheimnisfelder in `source_settings` — zweiter Verschlüsselungsweg, der Kern müsste jedes Schema kennen.
- Konnektoreigene Tabellen — Schemaänderung je Konnektor; Record und Synchronisationszustand genügen.
- Enum behalten, Registry nur zur Prüfung — jeder Wert eine Spec-Änderung.
- Formulare vollständig aus einem Schema erzeugen — lohnt nicht, die Formularkomponente je Typ bleibt.
- Zweiter Host als Feld der Adressregel — brächte keine Prüfung, die nicht schon in der Betreiberkonfiguration liegt.

## Offen

- Das Push-Geheimnis erzeugt der Kern. Ein Anbieter, der sein Signaturgeheimnis selbst ausgibt, passt
  nicht in diesen Weg; das braucht eine Erweiterung, sobald ein solcher Konnektor kommt.
