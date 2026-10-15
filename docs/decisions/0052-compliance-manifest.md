# ADR-0052: Compliance-Manifest

## Status

Akzeptiert · zuletzt geändert 2026-10-15

## Kontext

Die Spezifikation [Behördenunterlagen](../features/behoerdenunterlagen.md#das-faktenmodell) verlangt
ein Fakten-Manifest als einzige Quelle für Faktenblätter, Update-Delta und Instanzexport: welche Daten
OPAA speichert, welche Protokollereignisse entstehen, was Rollen über andere sehen, welche Fristen,
Voreinstellungen, System-Prompts, KI-Funktionen und ausgehenden Datenflüsse es gibt. Ein Teil dieser
Fakten steht bereits im Code (Enums, Liquibase, Konfiguration), ein Teil nur in Spezifikationen
(Zweck, Sichtbarkeit, Löschweg, mögliche Auswertungen). Das Manifest muss zur Laufzeit im Klassenpfad
liegen, weil der Instanzexport es liest, und es darf bei parallelen PRs nicht in einer Datei
kollidieren. Jede zusätzliche Pflegestelle ist Folgeaufwand, und eine relevante Änderung darf nicht
unbemerkt bleiben.

## Entscheidung

### 1. Ablage im Klassenpfad des Backends

Das Manifest liegt als YAML-Datei je Bereich unter `backend/src/main/resources/compliance/manifest/`;
der Dateiname ist der Bereich. Lader und Schema liegen im Paket `io.opaa.compliance` des Moduls
foundation, das kein höheres Paket importiert; Fundstellen im Code nennt es nur als Namen. Ein eigenes
Gradle-Modul nach dem Muster von `opaa-api` gibt es nicht: Alles, wogegen das Manifest geprüft wird
(Liquibase, Prompt-Klassen, Konfiguration, ausgehende Clients), liegt im Backend, und eine geänderte
Ressource übersetzt keinen Code neu.

### 2. Die Records sind das Schema

`ComplianceManifest` und seine verschachtelten Records beschreiben jede Eintragsart; Bean-Validation-
Annotationen markieren die Pflichtfelder. `ComplianceManifestLoader` weist das Manifest als Ganzes ab,
mit allen Verstößen: fehlendes oder leeres Pflichtfeld, unbekannter Schlüssel, doppelte Kennung,
Fristverweis ohne Einstellungseintrag, KI-Funktion mit unbeschriebenem Prompt, doppelter Schlüssel in
einer Zuordnung, Prompt ohne oder mit zwei Fundstellen. Schlüssel sind
englisch und Code-Bezeichner, Werte deutsch für die Behörde.

### 3. Nur, was nicht aus dem Code folgt

Ein Eintrag wird über einen Code-Bezeichner gefunden (Enum-Konstante, Tabelle und Spalte,
Konfigurationsschlüssel, Klassenname) und trägt nur, was der Code nicht sagt: Zweck, Personenbezug,
Sichtbarkeit, Zugriffsweg, Löschweg, mögliche Auswertungen, Auslöser, Hinweise. Bewusst doppelt
stehen nur Stolperdrähte: Standardwerte von Einstellungen und Prüfsummen von Prompts, damit eine
Änderung am Code ohne Nachpflege auffällt. Rechtliche Einstufungen stehen nie im Manifest, Hinweise
darauf nur in `notes`.

### 4. Voreinstellungen je Datei

Ein Bereich kann unter `defaults` je Abschnitt gemeinsame Felder setzen; ein Eintrag überschreibt sie
Schlüssel für Schlüssel, die Pflichtfeldprüfung läuft auf dem Ergebnis. Ein neues Protokollereignis
braucht damit nur Kennung und Auslöser.

### 5. Fristen sind Einstellungen

Eine Frist ist ein Eintrag unter `settings` mit Quelle `PROPERTY`, `DATABASE` (`tabelle.spalte`) oder
`CONSTANT` (`Klasse#FELD`). Daten und Protokollereignisse verweisen in `retention.settings` darauf; ein
Verweis ohne Eintrag scheitert. Ein Datum mit festem Ende verweist auf nichts und sagt das in
`retention.rule`.

### 6. Prüfsumme eines Prompts

Ein Prompt liegt entweder in Klassen (`classes`) oder als Default einer Spalte (`databaseDefault`,
`tabelle.spalte`), nie beides. Im Code ist die Prüfsumme SHA-256 über alle Zeichenkettenkonstanten
und alle Zeichenkettenwerte von Annotationen an Klasse, Feldern, Methoden und Parametern
(`@Tool(description = …)`) der genannten Klassen, gelesen aus ihren Klassendateien, sortiert
(`PromptChecksum`). Sie erfasst Textblöcke, zusammengesetzte Konstanten und Vorlagen der
Laufzeitverkettung, auch wenn ein Prompt im Code verteilt aufgebaut wird; eine geänderte Log- oder
Fehlermeldung derselben Klasse ändert sie ebenfalls. In der Datenbank ist sie SHA-256 über den letzten
`DEFAULT`, den das Changelog der Spalte gibt. Maßgeblich ist die mit javac übersetzte Klassendatei der
Gradle-Toolchain; ein anderer Compiler (ECJ in Eclipse oder VS Code) kann einen abweichenden
Konstantenbestand und damit eine andere Prüfsumme erzeugen.

### 7. Vollständigkeitswächter ohne Datenbank

`ComplianceManifestTest` gleicht das Manifest in beiden Richtungen ab: Werte von `AuditEventType`,
`SystemRole` und `Capability`, Prompt-Prüfsummen sowie jede Tabelle und jede Spalte des Schemas. Jede
Spalte steht genau einmal, als `personalColumns` oder `nonPersonalColumns` ihrer Tabelle oder in der
Spaltenliste unter `nonPersonalTables`; so fällt auch eine neue Spalte einer Tabelle ohne
Personenbezug auf. Das Schema liest `ChangelogSchema` aus den `sql:`-Blöcken des Master-Changelogs
(ADR-0034); jede Tabellen-DDL, die es nicht vollständig liest, auch in Prozedurrümpfen außer der
Partitionspflege, lässt es scheitern. Ein Test gegen eine Testcontainers-Datenbank würde ohne
Docker still übersprungen; der Abgleich des Lesers und der festen Spalten von `vector_store` mit dem
Katalog einer migrierten Datenbank (`ChangelogSchemaParityTest`) läuft deshalb getrennt in der CI. Jede
Meldung nennt Datei und einzufügendes YAML; die Konvention steht in `backend/docs/compliance-manifest.md`.

### 8. Konfigurationsschlüssel und Voreinstellungen

Der Build liefert `META-INF/spring-configuration-metadata.json`: `spring-boot-configuration-processor`
in einem eigenen Lauf über alle Quellen, ergänzt um `additional-spring-configuration-metadata.json`
für Schlüssel ohne `@ConfigurationProperties`-Typ und um den Standardwert aus dem Basisdokument von
`application.yml`. Jeder `opaa.*`-Schlüssel trägt `@DataImpact` oder `@NoDataImpact(Grund)` an seiner
Komponente oder pauschal an seinem Typ; Schalter, Modelle, Adressen und Fristen entscheidet die
Komponente selbst. Datenwirksam ist ein Standardwert, der bestimmt, was über Personen anfällt, wie
lange es bleibt, wer es sieht, was sich auswerten lässt oder wohin Daten gehen; Schutzgrenzen,
Zeitlimits, Suchgüte und Geheimnisse sind es nicht. Ein datenwirksamer Schlüssel steht mit seinem
Standardwert unter `settings`, ebenso Modell und Adresse jeder Modellrolle (Standardmodelle). Setzt
das Helm-Chart den Schlüssel, nennt der Eintrag Wert und Voreinstellung aus `values.yaml` (`helm`).

`ConfigurationSettingsTest` prüft alles davon. Die Markierung steht dort, wo ein neuer Schlüssel
entsteht, damit sie niemand in einer zweiten Liste nachtragen muss. Der Prozessor läuft getrennt,
weil Gradle beim inkrementellen Übersetzen unveränderte Typen ohne Javadoc nachverarbeitet.

## Konsequenzen

Einfacher: Ein Bereich wächst ohne Konflikt in seiner eigenen Datei. Faktenblätter, Delta und
Instanzexport lesen typisierte Records statt YAML. Ein Tippfehler im Schlüssel scheitert, statt still
zu verschwinden.

Schwieriger: Eine Prompt-Klasse mit vielen Meldungen erzeugt Fehlalarme; die Nachpflege ist eine
neue Prüfsumme. Jede neue Spalte braucht eine Zeile im Manifest, auch ohne Personenbezug.
Jede neue Konfigurationsklasse braucht eine Markierung, jeder geänderte Standardwert eines
datenwirksamen Schlüssels einen nachgezogenen Eintrag.

Restrisiken: Das Manifest belegt Vollständigkeit, nicht Richtigkeit; die sichern Review und
Drift-Routine.

## Verworfene Alternativen

- JSON Schema neben dem Lader — zweite Quelle des Schemas, ohne zusätzliche Bibliothek nicht prüfbar.
- Eigenes Gradle-Modul — sieht die geprüften Code-Stellen nicht und spart keinen Build.
- Prüfsumme über die Quelldatei — jede Formatierung oder Javadoc-Änderung schlüge an.
- Prüfsumme über den gerenderten Prompt — braucht je Prompt eigene Eingaben und Aufrufcode.
- Personenbezogene Spalten allein nennen — eine neue Spalte bliebe unbemerkt.
- Schema aus der Testcontainers-Datenbank — ohne Docker übersprungen, nicht in jedem Testlauf.
- Liste der datenwirksamen Schlüssel im Test — ein neuer Schlüssel fiele dort nicht auf.
- Jeder Schlüssel ohne Datenwirkung einzeln markiert — rund 300 Markierungen ohne Erkenntnis.
- Eigene Eintragsart für Standardmodelle — Modell und Adresse sind gewöhnliche Voreinstellungen.
- Prozessor im regulären `compileJava` — verliert beim inkrementellen Übersetzen die Beschreibungen.
