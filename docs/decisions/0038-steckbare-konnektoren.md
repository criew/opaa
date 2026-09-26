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
  `IndexingConnectorBoundaryTest`.
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
