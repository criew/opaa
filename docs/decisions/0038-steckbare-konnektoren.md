# ADR-0038: Steckbare Konnektoren — offener Typ-Schlüssel, Einstellungen je Konnektor, Geheimnisse außerhalb der Einstellungen

## Status

**Vorgeschlagen (26.09.2026)** — Issue [#1977](https://github.com/criew/opaa/issues/1977), Epic
[#1906](https://github.com/criew/opaa/issues/1906). Ergänzt
[ADR-0006](0006-openapi-dto-generation.md). Ändert
[ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md) (Nachtrag #1131: Kindtabelle der
Space-Auswahl), [ADR-0023](0023-confluence-konnektor.md), Entscheidungen 1, 2 und 4 (Ablage von
Space-Auswahl, Edition und Vollabgleichsrhythmus), und [ADR-0027](0027-s3-konnektor.md),
Entscheidung 1 (`CHECK`-Zweige je Typ, „Ausdrücklich offen": Umzug der bestehenden Typen). Alle
drei tragen einen Nachtrag mit Verweis hierher.

**Nachtrag mit [ADR-0041](0041-verbindungen-als-eigenes-modul.md) (03.10.2026, #2159):** Die
Konnektor-Beschreibung meldet Profilangabe und Anmeldearten. Bei Profilen leitet sich das Ziel der
Zugangsdaten aus der Server-Adresse des Profils ab, und Entscheidung 3 bekommt zwei weitere Plätze
für Geheimnisse. Siehe „Nachtrag: Verbindungsprofile“ am Ende. Die vierte Anmeldeart
„Dienstkonto-Schlüssel“ kommt mit [ADR-0040](0040-google-drive-konnektor.md) dazu.

## Kontext

Seit #1976 erreicht die Verwaltung jeden Konnektor über die Konnektor-SPI
(`SourceConnector`, `SourceConnectorRegistry`). Ein neuer Konnektor braucht trotzdem noch
Änderungen an drei Stellen außerhalb seines Pakets:

1. **Typ:** `DocumentSourceType` ist ein geschlossenes Enum in `opaa-api`.
2. **API:** Die Quelleinstellungen sind flache Felder (`confluenceEdition`, `confluenceSpaces`,
   `confluenceFullSyncIntervalDays`, `s3Settings`) neben den allgemeinen Verbindungsfeldern.
3. **Schema:** Confluence hat eigene Spalten und eine eigene Kindtabelle, S3 speichert schon in
   `source_settings` (jsonb); die `CHECK`-Constraints zählen die Typen auf.

Beschluss des Maintainers (26.09.2026): Konnektoren sollen steckbar sein. Ein neuer Konnektor soll
ohne Änderung an OpenAPI-Spezifikation, Datenbankschema und Verwaltung ergänzt werden können.

## Entscheidung

### 1. Der Typ ist ein offener Schlüssel, die Registry die einzige Liste

Die Quellart ist ein String aus Großbuchstaben, Ziffern und Unterstrich, höchstens 20 Zeichen
(Breite von `source_type`). Die bestehenden Werte bleiben (`UPLOAD`, `FILESYSTEM`,
`HTTP_DIRECTORY`, `RSS_FEED`, `CONFLUENCE`, `S3`). Welche Schlüssel es gibt, weiß nur die
`SourceConnectorRegistry`: Jeder Konnektor meldet seinen Schlüssel in seiner Beschreibung. Die
Datenbank prüft nur noch die Form des Schlüssels, keine Werteliste. `GET /source-types` liefert je
Konnektor Schlüssel, Anzeigename und Fähigkeiten aus der Beschreibung. Das geschlossene Enum
`DocumentSourceType` entfällt; braucht ein Konnektor seinen Schlüssel als Konstante, liegt sie im
Konnektor.

### 2. Einstellungen je Konnektor als ein JSON-Objekt in `source_settings`

- **Allgemeine Verbindungsfelder bleiben Spalten:** `source_path`, `source_url`, `source_proxy`,
  `source_credentials`, `source_insecure_ssl`. An ihnen hängen Regeln des Kerns: Ursprungsbindung
  der Zugangsdaten (`SourceOriginMatcher`), Verschlüsselung, Zieladressprüfung, Pfad-Allowlist.
- **Alles Konnektoreigene steht als ein Objekt in `source_settings`.** Form, Validierung,
  Normalisierung und die Sicht für Leser definiert der Konnektor mit einem eigenen Record in seinem
  Paket. Der Kern reicht das Objekt nur durch (`ConnectorData`) und kennt keinen seiner Schlüssel.
  S3 behält seine Form; Confluence speichert `edition`, `spaces` (`key`, `name`) und
  `fullSyncIntervalDays`.
- **Die Datenbank prüft nur noch Typunabhängiges:** `source_settings` ist ein JSON-Objekt oder
  `NULL`, und eine `UPLOAD`-Bibliothek trägt keine Quellkonfiguration. Die Confluence-Spalten, die
  Tabelle `knowledge_library_confluence_spaces` und die `CHECK`-Zweige je Typ entfallen. Eine
  Datenmigration gibt es nicht, weil es keine Bestandssysteme gibt.
- Eine konnektoreigene Tabelle ist nicht vorgesehen. Die Einstellungen sind klein und werden als
  Ganzes ersetzt; Laufzustand je Element (etwa je Space oder Bereich) lebt wie bisher im
  Synchronisationszustand, nicht in der Konfiguration.

### 3. Geheimnisse stehen nie in den Einstellungen

`source_settings` ist unverschlüsselt und erscheint in Antworten. Ein Konnektor hat deshalb genau
zwei Plätze für Geheimnisse, beide Spalten des Kerns, beide über `SourceCredentialsConverter`
verschlüsselt:

- `source_credentials` für die Zugangsdaten. Das Format bestimmt der Konnektor (S3
  `accessKey:secretKey[:sessionToken]`, Confluence je Edition). Mehrere Zugangsgeheimnisse passen
  damit schon heute ohne Kernänderung hinein; S3 trägt bis zu drei.
- `source_webhook_secret` für das Push-Geheimnis. Der Kern erzeugt, rotiert und löscht es und zeigt
  es genau einmal an.

Antworten tragen nur Ja/Nein-Angaben (`sourceCredentialsSet`, gesetztes Push-Geheimnis). Das Audit
nennt Feldnamen, nie Werte; den Feldnamen des Push-Geheimnisses liefert der Konnektor in seiner
Beschreibung. `SourceSettings#toString` maskiert die Zugangsdaten. Ein verschlüsseltes Feld
innerhalb von `source_settings` gibt es nicht.

**Invariante: Jedes Ziel, an das Zugangsdaten gehen, leitet sich aus `sourceUrl` ab.** Die
Ursprungsbindung (`SourceOriginMatcher`, #516/#542) verwirft gespeicherte Zugangsdaten, sobald sich
der Ursprung von `sourceUrl` ändert. Ein Konnektor darf Host, Mandant oder ein anderes Ziel seiner
Zugangsdaten deshalb nicht allein in `source_settings` führen. Braucht er das doch, verlangt er bei
einer Zieländerung in den Einstellungen selbst neue Zugangsdaten.

**Offen:** Das Push-Geheimnis erzeugt heute der Kern. Ein Anbieter, der sein Signaturgeheimnis
selbst ausgibt und es nur eintragen lässt, passt nicht in diesen Weg; das braucht einen eigenen
Nachtrag, sobald ein solcher Konnektor kommt.

### 4. Abbildung in OpenAPI: freies Objekt, `sourceType` als Diskriminator daneben

`sourceSettings` ist im Schema ein freies Objekt (`type: object`, `additionalProperties: true`),
kein `oneOf`. Welcher Konnektor es liest, sagt das danebenstehende `sourceType`. Der Konnektor
validiert es und weist Fehler mit deutscher `400`-Meldung ab. Die Antwort trägt die Lese-Sicht des
Konnektors: Leser sehen, was die Bibliothek abdeckt (Spaces, Bereiche); Verwaltende sehen
zusätzlich reine Verwaltungswerte (etwa den Vollabgleichsrhythmus). Der Verbindungstest liefert
konnektoreigene Befunde in einem freien Objekt `details`. Push-Eingang, Push-Geheimnis und die
Auflistung vor dem Speichern werden typneutral benannt und über den Typ-Schlüssel an den Konnektor
geleitet. Die Form der Einstellungen je Konnektor dokumentiert sein Handbuchkapitel und seine
Formularkomponente im Frontend, die nach Typ-Schlüssel registriert wird.

Damit geht verloren, was die Spezifikation bisher je Feld leistete: Grenzen wie `required`,
`maxItems` und `maxLength`, die Bean-Validation der generierten DTOs und die Frontend-Typen (aus
`sourceSettings` wird `Record<string, unknown>`). Die Formularkomponente muss ihre Form selbst
deklarieren und aktuell halten; das prüft kein Build mehr. Als Ausgleich liefert
`GET /source-types` je Konnektor ein JSON-Schema seiner Einstellungen, wo der Konnektor eines
angibt. Das braucht keine Spec-Änderung, dokumentiert die Form für Clients und kann das Frontend
vor dem Senden prüfen lassen; maßgeblich bleibt die Validierung im Konnektor. Ob das Schema schon
mit dem API-Bruch kommt, entscheidet dessen Umsetzung.

### 5. Übergang in zwei Schritten

Zuerst stellt das Backend intern um (Einstellungen je Konnektor, Schema, Kern ohne
Konnektornamen), während der Controller die flache API in die neuen Einstellungen übersetzt.
Frontend und E2E bleiben dabei unverändert. Danach folgt der API-Bruch mit freiem
`sourceSettings`, offenem Schlüssel, `GET /source-types` und angepasstem Frontend.

## Verworfene Alternativen

- **`oneOf` mit einem Zweig je Konnektor.** Dokumentiert jede Form in der Spezifikation, verlangt
  aber für jeden neuen Konnektor eine Spec-Änderung; genau das soll entfallen.
- **Bekannte Zweige plus freier Rückfallzweig.** Ein freier Zweig passt auf jede Eingabe, `oneOf`
  wäre damit nie eindeutig; die Spezifikation täuschte eine Prüfung vor, die der Konnektor ohnehin
  selbst macht.
- **Verschlüsselte Geheimnisfelder in `source_settings`.** Der Kern müsste das Schema jedes
  Konnektors kennen, um zu verschlüsseln, zu maskieren und bei fehlendem Schlüssel (#1806) den
  Geheimtext zu schonen. Das wäre ein zweiter Verschlüsselungsweg neben dem bestehenden.
- **Konnektoreigene Tabellen.** Jede Tabelle ist eine Schemaänderung je Konnektor. Die Argumente von
  ADR-0023 (Eindeutigkeit, Nichtleere, Fortschritt je Element) beantwortet der Record
  beziehungsweise der Synchronisationszustand, wie schon ADR-0027 für S3 festgehalten hat.
- **Enum behalten, Registry nur zur Prüfung.** Jeder neue Wert wäre eine Spec-Änderung.
- **Formulare vollständig aus einem Schema erzeugen.** Lohnt bei sechs Konnektoren nicht; die
  Formularkomponente je Typ bleibt. Das Schema je Konnektor aus Entscheidung 4 dient der Prüfung
  und Dokumentation, nicht dem Erzeugen.

## Konsequenzen

### Einfacher

- Ein neuer Konnektor bringt sein Paket, seinen Einstellungs-Record und seine Formularkomponente
  mit. Spezifikation, Schema und Verwaltung bleiben unverändert.
- Verwaltung, Bestand und API kennen keinen Konnektor beim Namen; die Grenze sichert
  `IndexingConnectorBoundaryTest` (seit #2000 `ModularArchitectureTest`).
- Geheimnisse haben weiterhin genau einen Verschlüsselungs- und Verwaltungsweg.

### Schwieriger

- **Die Datenbank prüft die Konfiguration nicht mehr je Typ.** Die Pflichtfelder (etwa
  mindestens ein S3-Bereich, die Confluence-Edition) sichert allein der Konnektor. Seine
  Record-Tests sind deshalb Pflicht, nicht Stilfrage.
- **Die Spezifikation beschreibt `sourceSettings` nicht mehr im Einzelnen.** Clients sehen die
  Form im Handbuch, in der Formularkomponente und gegebenenfalls im Schema aus `GET /source-types`,
  nicht im generierten Typ; Spec-Validierung und generierte Frontend-Typen entfallen dafür.
- **Eine Umbenennung von Einstellungs-Schlüsseln braucht künftig eine Datenmigration über
  `jsonb`**, sobald es Bestandssysteme gibt.

## Nachtrag: Umsetzung des API-Bruchs (#1977, Teil B)

- **Endpunkte:** `GET /api/v1/source-types` listet je Konnektor Schlüssel, Anzeigename und
  Fähigkeiten (Indizierungslauf, Push-Eingang, Auflistung, Vorgabe des Vollabgleichsrhythmus).
  Die Auflistung vor dem Speichern heißt `POST /api/v1/source-types/{sourceType}/browse` und trägt
  die konnektoreigenen Parameter in `query`; das Push-Geheimnis heißt
  `POST|DELETE /api/v1/libraries/{libraryId}/push-secret`, der Push-Eingang
  `POST /api/v1/libraries/{libraryId}/push`. Welcher Konnektor eine Push-Nachricht liest, entscheidet
  der Typ der Bibliothek, nicht der Pfad.
- **Kein JSON-Schema je Konnektor in diesem Schritt.** `GET /source-types` liefert kein Schema der
  Einstellungen. Die Formularkomponente je Typ ist ohnehin handgeschrieben und prüft ihre Eingaben
  selbst, maßgeblich bleibt die Validierung im Konnektor, und ein Schema, das kein Client liest,
  wäre ungeprüfte Doppelpflege neben dem Einstellungs-Record. Das Feld lässt sich später
  rückwärtskompatibel ergänzen, sobald ein Client es braucht (etwa eine generische Eingabemaske für
  Konnektoren ohne eigene Formularkomponente).
- **Frontend:** Jede Quellart registriert sich an genau einer Stelle
  (`frontend/src/components/library/sources/registry.ts`): Namen, Symbol und – für eine Art mit
  Quelle – Formular samt Startwerten, Validierung, Anfragefeldern und den Leseansichten des Reiters
  „Quelle“ (Umfang, Anbindung, Kopfzeile). Die Kacheln des Anlage-Assistenten kommen aus
  `GET /source-types`. Ein Typ, den das Backend kennt, das Frontend aber nicht, erscheint unter
  seinem Anzeigenamen, ist nicht wählbar und im Reiter „Quelle“ als nicht konfigurierbar markiert.
  Schlägt die Liste fehl, nennt der Assistent den Fehler und bietet keine Art an. Auch die
  typabhängigen Beschriftungen und Wege außerhalb der Quellformulare kommen aus der Registrierung
  (Container-Bezeichnung einer Dokumentzeile, Öffnen an der Quelle, Laufart-Anzeige, Zählweise eines
  Feed-Laufs). Nach dem Typ verzweigt das Frontend sonst nur noch auf den Kerntyp `UPLOAD` und in
  den konnektoreigenen Formular- und Hilfsmodulen.
- **Uploads sind eine eigene Fähigkeit** (`uploads` in Beschreibung und `GET /source-types`), nicht
  „kein Lauf“. Sie entscheidet Anlegerecht, Upload-Annahme, Freigabe-Obergrenze und die
  Löschsperre bei Bestand. Nur `UPLOAD` darf sie tragen, das prüft die Registry beim Start: Der
  Upload-Speicher, seine Ordner und Originale sowie die Datenbankprüfungen auf `UPLOAD` hängen am
  Typ. Ein Konnektor ohne Lauf und ohne Uploads ist damit eine Konnektorbibliothek, die nichts
  hochladen lässt; die Datenbank blockiert ihn nicht.
- **Gleiche Antwortzeit am Push-Eingang:** Jede Anfrage läuft durch die Prüfung jedes registrierten
  Push-Eingangs – die der Bibliothek echt, alle anderen gegen einen Platzhalter
  (`PushIntakeHandler#rejectForeign`); die Bibliothek wird dafür genau einmal geladen und an den
  Eingang durchgereicht. Unbekannte Bibliothek, Bibliothek ohne Push-Eingang und falsches
  Geheimnis sind damit weder an der Antwort noch an der Zahl der Abfragen unterscheidbar.
- **Nachweis:** Zwei Test-Konnektoren nur im Testcode – einer ohne Lauf, einer mit Lauf und
  Auflistung – werden über die HTTP-API angelegt, geändert, getestet, aufgelistet, geplant und
  indiziert.

## Nachtrag: Verbindungsprofile (03.10.2026, #2159, Epic #2147)

Grundlage sind [connector-connections.md](../features/connector-connections.md) und
[ADR-0041](0041-verbindungen-als-eigenes-modul.md).

- **Die Beschreibung meldet die Profilangabe:** verboten, optional oder Pflicht.
  - Verboten gilt für jeden Konnektor mit `uploads` oder ohne `remote`.
  - Pflicht gilt für jeden Konnektor, der OAuth oder Client-Credentials anbietet, weil die
    App-Registrierung nur am Profil steht.
  - Die Registry prüft beides beim Start.
- **Die Beschreibung meldet die Anmeldearten:** persönliches Geheimnis, OAuth (Autorisierungscode
  mit PKCE), Client-Credentials und Dienstkonto-Schlüssel (JWT-Assertion nach RFC 7523,
  [ADR-0040](0040-google-drive-konnektor.md)), je Art mit den zulässigen Besitzarten (Bibliothek,
  Person).
  - Für OAuth nennt sie die Endpunkte des Autorisierungsservers, entweder fest, mit dem Mandanten
    aus dem Profil oder relativ zur Server-Adresse.
  - Für den Dienstkonto-Schlüssel nennt sie den Token-Endpunkt und die Scopes, beide fest. Nur die
    Besitzart Bibliothek ist zulässig. Der Schlüssel enthält seine App-Registrierung selbst; die Art
    macht deshalb keine Profilpflicht. Er liegt ohne Profil in `source_credentials` und mit Profil
    am Platz des Client-Secrets. Signiert wird im Kern, und ein Zugriffstoken lebt nur im Speicher
    des Laufs.
  - Ein Profil wählt nur eine Art, die sein Konnektor anbietet.
- **Konnektoreigene Vorgaben am Profil** sind ein `ConnectorData`-Objekt wie `source_settings`
  (Entscheidung 2). Der Konnektor prüft es, und es enthält nie ein Geheimnis. Was Ziel oder Anmeldung
  bestimmt, etwa die Confluence-Edition, steht am Profil und nicht in der Bibliothek.
- **Ziel der Zugangsdaten (Invariante aus Entscheidung 3):** Bei einer Bibliothek mit Profil ist der
  Ursprung die Server-Adresse des Profils, und `sourceUrl` muss diesen Ursprung haben.
  - `SourceOriginMatcher` und `TargetAddressValidator` prüfen gegen das Profil.
  - Persönliches Geheimnis und Zugriffstoken gehen nur an diesen Ursprung. Refresh-Token,
    Client-Secret und die signierte Dienstkonto-Assertion gehen nur an den Token-Endpunkt aus der
    Beschreibung. Das gilt ebenso ohne Profil: Der Token-Endpunkt aus der Beschreibung ist das
    einzige Ziel außerhalb von `sourceUrl`, an das Material aus Zugangsdaten gehen darf.
  - Ändern sich Server-Adresse oder Mandant, gilt das als Zieländerung: Server-Adresse verwirft die
    Geheimnisse, Mandant verlangt eine neue Zustimmung.
- **Entscheidung 3 hat vier Plätze statt zwei:** `source_credentials` und `source_webhook_secret`
  der Bibliothek, das **Client-Secret am Profil** und den **Token-Speicher** (Besitzer Bibliothek
  oder Person).
  - Alle vier laufen über `CredentialsEncryptor` mit demselben Schlüssel und derselben Leseregel:
    Fehlt der Schlüssel, liest sich der Wert als nicht gesetzt, und der Geheimtext bleibt (#1806).
    Einen zweiten Verschlüsselungsweg gibt es nicht.
  - Eine Bibliothek mit Profil trägt kein `source_credentials`, ihr Geheimnis steht nur im
    Token-Speicher.
  - Antworten tragen „gesetzt / nicht gesetzt“ und den Status, das Audit nur Feldnamen.
- **Der Konnektor bekommt Ziel, Geheimnis und Einstellungen nur vom Kern**, als aufgelöste
  `SourceSettings`, auch im Lauf und beim Originalabruf. Das Geheimnis kommt mit Art (persönliches
  Geheimnis oder Zugriffstoken). Profil, Refresh-Token, Client-Secret, Dienstkonto-Schlüssel und
  OAuth-Ablauf sieht er nie,
  und aus `KnowledgeLibrary` liest er weder Zugangsdaten noch Adresse noch Einstellungen. Das
  verlangt einen Umbau der Lauf-SPI mit eigener ArchUnit-Regel (ADR-0041, Entscheidung 3a, #2178).

## Nachtrag: Form der Profilangabe (04.10.2026, #2218)

Die drei Felder `profileSupport`, `authMethods` und `serviceAccountKey` der Beschreibung sind in
einem Wert `ProfileDeclaration` (`io.opaa.indexing.source`) aufgegangen. Er nennt nichts aus dem
Modul connections.

- **Profilangabe:** verboten, optional oder Pflicht wie oben.
- **Anmeldearten** als Liste von `SignIn(method, owners, details)`. `owners` nennt Bibliothek,
  Person oder beide. `details` ist versiegelt: keine Angaben, die Form des persönlichen Geheimnisses
  (Token oder Benutzername und Passwort) oder der Dienstkonto-Schlüssel mit Token-Endpunkt und
  Scope. Eine weitere Art mit eigenen Endpunkten (OAuth) ergänzt einen Typ, eine weitere Besitzart
  einen Wert. Ein Konnektor ohne Profile nennt nur die Anmeldung, die der Kern für den eigenen
  Schlüssel einer Bibliothek ausführt (Google Drive).
- **Adressregel** `ServerAddressRule`: zulässige Schemata (`https`, `http`, `smb`) oder eine feste
  Adresse, die das Formular nicht abfragt. Ein zweiter Host wäre ein weiteres Feld.
- **Vorgaben-Schema** `ProfileDefaults`: die Einstellungsschlüssel, die ein Profil setzen darf, je
  mit Beschriftung und Art (Text, Ja/Nein, Auswahl). `SourceConnector#readProfileDefaults` prüft die
  Vorgaben eines Profils dagegen; ein anderer Schlüssel ist ein `400`. Die Form der
  Bibliothekseinstellungen (`readSettings`) gilt für Vorgaben nicht mehr.
- **Registry beim Start:** Profile verboten bei `uploads` oder ohne `remote`; Pflicht genau dann,
  wenn OAuth oder Client-Credentials angeboten werden; jeder Vorgaben-Schlüssel steht in
  `SourceConnector#settingsKeys`; ein Konnektor mit Dienstkonto-Schlüssel lässt noch kein Profil
  zu. Seit die mitgelieferten entfernten Konnektoren Profile zulassen (#2219), prüft die Registry
  auch die Umkehrung: Ein Konnektor mit entferntem Ziel und ohne Uploads muss Profile zulassen;
  ausgenommen bleibt nur einer mit Dienstkonto-Schlüssel (Google Drive, bis #2220).
- `GET /source-types` meldet `signIns`, `profileDefaults` und `serverAddress`; die Auswahl der
  Profile (`GET /connection-profiles`) meldet die Vorgaben eines Profils als `connectorDefaults`.
